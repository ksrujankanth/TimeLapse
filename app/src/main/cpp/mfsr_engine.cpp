#include "mfsr_engine.h"
#include <cmath>
#include <cstdio>
#include <algorithm>

using namespace cv;

namespace {

// Reject frames whose alignment is not trustworthy. With a correct pipeline,
// good handheld frames land at 0.95-1.00; scene changes / failed ECC drop below 0.5.
constexpr double kMinEccCorrelation = 0.45;

// Max displacement of any image corner, as a fraction of the image diagonal.
// Catches rotation / scale outliers that a translation-only test misses.
constexpr float kMaxCornerShiftFrac = 0.12f;

// Photometric correction limits (per RGB channel).
constexpr float kMinGain = 0.70f;
constexpr float kMaxGain = 1.45f;
constexpr float kMaxOffset = 40.0f;

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// Deposit one low-res sample into the HR grid with bilinear weight.
inline void deposit(cv::Mat& sum, cv::Mat& cnt, int x, int y, float w,
                    const float* v, int cols, int rows) {
    if (w <= 0.0f || x < 0 || y < 0 || x >= cols || y >= rows) return;
    cv::Vec3f* sp = sum.ptr<cv::Vec3f>(y) + x;
    float* cp = cnt.ptr<float>(y) + x;
    (*sp)[0] += v[0] * w;
    (*sp)[1] += v[1] * w;
    (*sp)[2] += v[2] * w;
    *cp += w;
}

} // namespace

MFSREngineV2::MFSREngineV2()
    : scaleFactor_(2.0f), hrCols_(0), hrRows_(0), anchorSharpness_(0.0),
      anchorGradMedian_(1.0f), sourceHalo_(-1.0f),
      ransacThreshold_(2.0f), sigmaClipMultiplier_(2.0f),
      motionThresh_(12.0f),
      holeCount_(0), accumulatedFrames_(0), meanDensity_(0.0),
      motionRejectedSamples_(0) {
    orbDetector_ = ORB::create(2000);
}

MFSREngineV2::~MFSREngineV2() {
    storedTransforms_.clear();
    storedWeights_.clear();
    storedGains_.clear();
    storedOffsets_.clear();
    accumSum_.release();
    accumCount_.release();
}

void MFSREngineV2::init(const Mat& anchor, float scale) {
    scaleFactor_ = std::max(1.0f, scale);

    // Normalise to 3-channel RGB whatever the caller supplied.
    if (anchor.channels() == 4) {
        cvtColor(anchor, anchorRGB_, COLOR_RGBA2RGB);
    } else if (anchor.channels() == 1) {
        cvtColor(anchor, anchorRGB_, COLOR_GRAY2RGB);
    } else {
        anchorRGB_ = anchor.clone();
    }
    if (anchorRGB_.depth() != CV_8U) anchorRGB_.convertTo(anchorRGB_, CV_8U);

    cvtColor(anchorRGB_, anchorGray_, COLOR_RGB2GRAY);

    Mat blurGray;
    GaussianBlur(anchorGray_, blurGray, Size(3, 3), 1.0);
    orbDetector_->detectAndCompute(blurGray, noArray(), anchorKeypoints_, anchorDescriptors_);

    {
        Mat lap;
        Laplacian(anchorGray_, lap, CV_64F);
        Scalar mu, sig;
        meanStdDev(lap, mu, sig);
        anchorSharpness_ = sig[0] * sig[0];
    }

    // Median gradient magnitude of the anchor. Used to convert a residual
    // (levels) into an alignment error estimate in pixels: residual/gradient.
    // Computed on a half-resolution copy so the temporary float planes stay small
    // (a full-res pair at 18MP would be ~200MB); the x2 compensates the downscale.
    {
        Mat smallGray;
        pyrDown(anchorGray_, smallGray);

        Mat gx, gy;
        Sobel(smallGray, gx, CV_32F, 1, 0, 3);
        Sobel(smallGray, gy, CV_32F, 0, 1, 3);
        Mat mag;
        magnitude(gx, gy, mag);

        std::vector<float> vals;
        vals.reserve((size_t)(mag.rows / 2 + 1) * (mag.cols / 2 + 1));
        for (int y = 0; y < mag.rows; y += 2) {
            const float* p = mag.ptr<float>(y);
            for (int x = 0; x < mag.cols; x += 2) vals.push_back(p[x]);
        }
        if (!vals.empty()) {
            size_t mid = vals.size() / 2;
            std::nth_element(vals.begin(), vals.begin() + mid, vals.end());
            anchorGradMedian_ = std::max(vals[mid] * 2.0f, 0.5f);
        } else {
            anchorGradMedian_ = 1.0f;
        }
    }

    // Small RGB reference for photometric matching (cheap warps, robust statistics).
    double ds = std::min(1.0, 512.0 / std::max(1, anchorRGB_.cols));
    if (ds < 1.0) {
        resize(anchorRGB_, anchorSmall_, Size(), ds, ds, INTER_AREA);
    } else {
        anchorSmall_ = anchorRGB_.clone();
    }

    hrCols_ = (int)std::lround(anchor.cols * (double)scaleFactor_);
    hrRows_ = (int)std::lround(anchor.rows * (double)scaleFactor_);

    storedTransforms_.clear();
    storedWeights_.clear();
    storedGains_.clear();
    storedOffsets_.clear();
    storedMotionDiff_.clear();
    transformOfCall_.clear();
    telemetry_.clear();
    accumSum_.release();
    accumCount_.release();
    holeCount_ = 0;
    accumulatedFrames_ = 0;
    meanDensity_ = 0.0;
    motionThresh_ = 12.0f;
    motionRejectedSamples_ = 0;

    MFSR_LOGI("Init: %dx%d -> %dx%d (%.2fx), %zu anchor keypoints, sharpness %.1f, grad median %.2f",
              anchor.cols, anchor.rows, hrCols_, hrRows_, scaleFactor_,
              anchorKeypoints_.size(), anchorSharpness_, anchorGradMedian_);
}

void MFSREngineV2::setSceneStats(double medianMse, double p90Mse, double spread, double medianBrightness) {
    if (spread < 1.5) { ransacThreshold_ = 1.5f; sigmaClipMultiplier_ = 2.0f; }
    else if (spread > 2.5) { ransacThreshold_ = 3.0f; sigmaClipMultiplier_ = 2.5f; }
    else { ransacThreshold_ = 2.0f; sigmaClipMultiplier_ = 2.0f; }

    // Low light: relax the (future) robust-fusion threshold. Kept for telemetry parity.
    if (medianBrightness < 60.0) {
        float dark = (float)(1.0 - medianBrightness / 60.0);
        sigmaClipMultiplier_ = std::min(sigmaClipMultiplier_ + dark * 1.5f, 4.0f);
    }

    MFSR_LOGI("Scene: spread=%.2f brightness=%.0f -> ransac=%.1f sigma=%.1f",
              spread, medianBrightness, ransacThreshold_, sigmaClipMultiplier_);
}

// ============================================================================
// Alignment
// ============================================================================

Mat MFSREngineV2::alignORBCoarse(const Mat& currGray, int* outInlierCount) {
    if (outInlierCount) *outInlierCount = 0;
    if (anchorDescriptors_.empty()) return Mat();

    std::vector<KeyPoint> kp;
    Mat des;
    try {
        Mat blur;
        GaussianBlur(currGray, blur, Size(3, 3), 1.0);
        orbDetector_->detectAndCompute(blur, noArray(), kp, des);
    } catch (...) { return Mat(); }
    if (des.empty() || kp.size() < 10) return Mat();

    BFMatcher matcher(NORM_HAMMING, false);
    std::vector<std::vector<DMatch>> knn;
    try { matcher.knnMatch(des, anchorDescriptors_, knn, 2); } catch (...) { return Mat(); }

    std::vector<Point2f> ptsCurr, ptsAnchor;
    ptsCurr.reserve(knn.size());
    ptsAnchor.reserve(knn.size());
    for (auto& m : knn) {
        if (m.size() >= 2 && m[0].distance < 0.75f * m[1].distance) {
            ptsCurr.push_back(kp[m[0].queryIdx].pt);
            ptsAnchor.push_back(anchorKeypoints_[m[0].trainIdx].pt);
        }
    }
    if (ptsCurr.size() < 10) return Mat();

    try {
        Mat mask;
        Mat warp = estimateAffinePartial2D(Mat(ptsCurr), Mat(ptsAnchor), mask,
                                           RANSAC, ransacThreshold_);
        if (warp.empty()) return Mat();
        int inliers = mask.empty() ? 0 : countNonZero(mask);
        if (outInlierCount) *outInlierCount = inliers;
        Mat w32f;
        warp.convertTo(w32f, CV_32F);
        return w32f;
    } catch (...) { return Mat(); }
}

Mat MFSREngineV2::alignPyramidECC(const Mat& currGray, const Mat& initialWarp, double* outCC) {
    if (outCC) *outCC = 0.0;

    const int LEVELS = 3;
    std::vector<Mat> cPyr(LEVELS), aPyr(LEVELS);
    cPyr[0] = currGray;
    aPyr[0] = anchorGray_;
    for (int i = 1; i < LEVELS; i++) {
        pyrDown(cPyr[i - 1], cPyr[i]);
        pyrDown(aPyr[i - 1], aPyr[i]);
    }

    Mat warp;
    initialWarp.convertTo(warp, CV_32F);
    if (warp.rows != 2 || warp.cols != 3) warp = Mat::eye(2, 3, CV_32F);

    // Bring the full-res warp down to the coarsest pyramid level.
    const float s = std::pow(2.0f, (float)(LEVELS - 1));
    warp.at<float>(0, 2) /= s;
    warp.at<float>(1, 2) /= s;

    double cc = 0.0;
    for (int lv = LEVELS - 1; lv >= 0; lv--) {
        try {
            // findTransformECC(template, input) -> W such that warpAffine(template, W) ~= input.
            // template = current frame, input = anchor  =>  W maps current -> anchor. Correct.
            cc = findTransformECC(cPyr[lv], aPyr[lv], warp, MOTION_EUCLIDEAN,
                                  TermCriteria(TermCriteria::COUNT + TermCriteria::EPS, 120, 1e-5));
        } catch (...) {
            // Keep the coarse estimate for this level.
        }
        if (lv > 0) {
            warp.at<float>(0, 2) *= 2.0f;
            warp.at<float>(1, 2) *= 2.0f;
        }
    }

    if (outCC) *outCC = cc;
    return warp;
}

// ============================================================================
// Photometric matching: per-channel gain/offset that maps this frame onto the anchor
// ============================================================================

void MFSREngineV2::estimatePhotometry(const Mat& frame, const Mat& warp,
                                      float* gain, float* offset, double* outCorrelation) {
    for (int c = 0; c < 3; c++) { gain[c] = 1.0f; offset[c] = 0.0f; }
    if (outCorrelation) *outCorrelation = 0.0;
    if (anchorSmall_.empty()) return;

    Mat rgb;
    if (frame.channels() == 4) cvtColor(frame, rgb, COLOR_RGBA2RGB);
    else if (frame.channels() == 1) cvtColor(frame, rgb, COLOR_GRAY2RGB);
    else rgb = frame;

    const double ds = (double)anchorSmall_.cols / (double)std::max(1, rgb.cols);
    Mat small;
    if (ds < 0.999)
        resize(rgb, small, Size(std::max(1, (int)(rgb.cols * ds)), std::max(1, (int)(rgb.rows * ds))),
               0, 0, INTER_AREA);
    else
        small = rgb;

    Mat ws;
    Mat T;
    warp.convertTo(T, CV_32F);
    T.at<float>(0, 2) *= (float)ds;
    T.at<float>(1, 2) *= (float)ds;
    warpAffine(small, ws, T, anchorSmall_.size(), INTER_LINEAR, BORDER_CONSTANT, Scalar(0, 0, 0));

    // Validity mask: warp of an all-ones image.
    Mat ones(anchorSmall_.size(), CV_8UC1, Scalar(1));
    Mat mask;
    warpAffine(ones, mask, T, anchorSmall_.size(), INTER_NEAREST, BORDER_CONSTANT, Scalar(0));
    const int valid = countNonZero(mask);
    if (valid < (int)(0.15 * anchorSmall_.total())) return;

    // Blur before measuring statistics. Noise inflates stddev; using raw stddev
    // would systematically under-estimate the gain of noisy frames and darken them.
    Mat aRef, bRef;
    GaussianBlur(anchorSmall_, aRef, Size(0, 0), 1.5, 1.5, BORDER_REPLICATE);
    GaussianBlur(ws, bRef, Size(0, 0), 1.5, 1.5, BORDER_REPLICATE);

    std::vector<Mat> aCh, bCh;
    split(aRef, aCh);
    split(bRef, bCh);

    double corrSum = 0.0;
    for (int c = 0; c < 3; c++) {
        Scalar ma, sa, mb, sb;
        meanStdDev(aCh[c], ma, sa, mask);
        meanStdDev(bCh[c], mb, sb, mask);
        if (sb[0] < 1e-3) continue;

        float g = (float)(sa[0] / sb[0]);
        g = clampf(g, kMinGain, kMaxGain);
        float o = (float)(ma[0] - g * mb[0]);
        o = clampf(o, -kMaxOffset, kMaxOffset);
        gain[c] = g;
        offset[c] = o;
        corrSum += 1.0;
    }

    if (outCorrelation) *outCorrelation = corrSum / 3.0;
}

// Full-resolution sparse motion estimate. Samples a regular grid (1/64 of pixels),
// compares the photometrically-corrected frame to the anchor at the aligned position,
// and returns the median absolute difference. Cheap and immune to the downscale bias
// a proxy-based estimate would have.
float MFSREngineV2::estimateMotionDiff(const Mat& frame, const Mat& warp,
                                       const float* gain, const float* offset) {
    if (frame.empty() || anchorRGB_.empty()) return 0.0f;

    Mat rgb;
    if (frame.channels() == 4) cvtColor(frame, rgb, COLOR_RGBA2RGB);
    else if (frame.channels() == 1) cvtColor(frame, rgb, COLOR_GRAY2RGB);
    else rgb = frame;

    const float t00 = warp.at<float>(0, 0), t01 = warp.at<float>(0, 1), t02 = warp.at<float>(0, 2);
    const float t10 = warp.at<float>(1, 0), t11 = warp.at<float>(1, 1), t12 = warp.at<float>(1, 2);

    const int STEP = 8;
    std::vector<float> diffs;
    diffs.reserve((size_t)(rgb.rows / STEP + 1) * (rgb.cols / STEP + 1));

    for (int y = 0; y < rgb.rows; y += STEP) {
        const Vec3b* row = rgb.ptr<Vec3b>(y);
        for (int x = 0; x < rgb.cols; x += STEP) {
            const float u = t00 * x + t01 * y + t02;
            const float v = t10 * x + t11 * y + t12;
            if (u < 0 || v < 0 || u >= anchorRGB_.cols - 1 || v >= anchorRGB_.rows - 1) continue;

            const int x0 = (int)u, y0 = (int)v;
            const float fx = u - x0, fy = v - y0;
            const Vec3b* a0 = anchorRGB_.ptr<Vec3b>(y0);
            const Vec3b* a1 = anchorRGB_.ptr<Vec3b>(y0 + 1);

            float d = 0.0f;
            for (int c = 0; c < 3; c++) {
                const float av = (1 - fx) * (1 - fy) * a0[x0][c] + fx * (1 - fy) * a0[x0 + 1][c]
                               + (1 - fx) * fy * a1[x0][c] + fx * fy * a1[x0 + 1][c];
                const float corrected = std::min(255.0f, std::max(0.0f, row[x][c] * gain[c] + offset[c]));
                d = std::max(d, std::fabs(corrected - av));
            }
            diffs.push_back(d);
        }
    }
    if (diffs.empty()) return 0.0f;
    size_t mid = diffs.size() / 2;
    std::nth_element(diffs.begin(), diffs.begin() + mid, diffs.end());
    return diffs[mid];
}

// ============================================================================
// Pass 1: accept + store transform + photometry
// ============================================================================

bool MFSREngineV2::processFrame(const Mat& frame) {
    MFSRFrameTelemetry t;
    t.frameIndex = (int)telemetry_.size();
    bool ok = false;
    try {
        ok = processFrameInternal(frame, t);
    } catch (const std::exception& e) {
        t.rejectionReason = std::string("exception: ") + e.what();
    } catch (...) {
        t.rejectionReason = "unknown exception";
    }
    telemetry_.push_back(t);
    transformOfCall_.push_back(ok ? (int)storedTransforms_.size() - 1 : -1);
    return ok;
}

bool MFSREngineV2::processFrameWithIndex(const Mat& frame, int idx, double mse) {
    MFSRFrameTelemetry t;
    t.frameIndex = idx;
    t.mseScore = mse;
    bool ok = false;
    try {
        ok = processFrameInternal(frame, t);
    } catch (const std::exception& e) {
        t.rejectionReason = std::string("exception: ") + e.what();
    } catch (...) {
        t.rejectionReason = "unknown exception";
    }
    telemetry_.push_back(t);
    transformOfCall_.push_back(ok ? (int)storedTransforms_.size() - 1 : -1);
    return ok;
}

bool MFSREngineV2::processFrameInternal(const Mat& frame, MFSRFrameTelemetry& t) {
    // Pass 1 only. Every gate below (photometry, motion diff, ECC) reads Vec3b in
    // 8-bit levels; a CV_16U frame here would read two bytes per sample and run off
    // the row, i.e. an out-of-bounds read rather than a wrong value. The 16-bit path
    // exists only in pass 2 (accumulateFrame), so reject the wrong depth loudly.
    CV_Assert(frame.depth() == CV_8U);
    t.accepted = false;
    if (frame.empty()) {
        t.rejectionReason = "empty frame";
        return false;
    }
    if (frame.size() != anchorRGB_.size()) {
        t.rejectionReason = "size mismatch";
        return false;
    }

    Mat gray;
    const int ch = frame.channels();
    if (ch == 1) gray = frame;
    else if (ch == 4) cvtColor(frame, gray, COLOR_RGBA2GRAY);
    else cvtColor(frame, gray, COLOR_RGB2GRAY);

    Scalar mg = mean(gray);
    t.brightness = (float)mg[0];
    Mat lap;
    Laplacian(gray, lap, CV_64F);
    Scalar mu, sig;
    meanStdDev(lap, mu, sig);
    t.sharpness = (float)(sig[0] * sig[0]);

    int inliers = 0;
    Mat orbW = alignORBCoarse(gray, &inliers);
    t.orbMatchCount = inliers;

    Mat finalW;
    double cc = 0.0;
    const bool orbReliable = !orbW.empty() && inliers >= 12;

    if (orbReliable) {
        finalW = alignPyramidECC(gray, orbW, &cc);
    } else {
        // Low-texture scenes (bright sky, water, walls): ORB gives nothing usable.
        // A pure ECC refinement of identity is still valid for a tripod / near-static
        // frame, but on a smooth background it can lock onto a completely wrong large
        // translation (measured 100-240 px on a backlit portrait) while still reporting
        // a high correlation, because smooth gradients correlate at any shift.
        // Only trust it when the correlation is very high AND the shift stays tiny.
        finalW = alignPyramidECC(gray, Mat::eye(2, 3, CV_32F), &cc);
        const float dx0 = finalW.empty() ? 0.0f : finalW.at<float>(0, 2);
        const float dy0 = finalW.empty() ? 0.0f : finalW.at<float>(1, 2);
        const float fallbackShift = std::sqrt(dx0 * dx0 + dy0 * dy0);
        const float maxFallbackShift = 0.02f * (float)anchorGray_.cols;
        if (cc < 0.90 || fallbackShift > maxFallbackShift) {
            char b[128];
            snprintf(b, sizeof(b), "no ORB match, weak ECC (cc=%.2f shift=%.0f)", cc, fallbackShift);
            t.rejectionReason = b;
            t.eccCorrelation = cc;
            return false;
        }
    }

    t.eccCorrelation = cc;
    t.eccConverged = cc >= kMinEccCorrelation;
    if (finalW.empty() || finalW.rows != 2 || finalW.cols != 3) {
        t.rejectionReason = "degenerate warp";
        return false;
    }
    if (cc < kMinEccCorrelation) {
        char b[96];
        snprintf(b, sizeof(b), "low ECC correlation %.2f", cc);
        t.rejectionReason = b;
        return false;
    }

    // Corner displacement (catches rotation/scale outliers, not just translation).
    const float w = (float)anchorGray_.cols;
    const float h = (float)anchorGray_.rows;
    const float corners[4][2] = {{0, 0}, {w, 0}, {0, h}, {w, h}};
    float maxDisp = 0.0f;
    for (auto& p : corners) {
        const float dx = finalW.at<float>(0, 0) * p[0] + finalW.at<float>(0, 1) * p[1] + finalW.at<float>(0, 2) - p[0];
        const float dy = finalW.at<float>(1, 0) * p[0] + finalW.at<float>(1, 1) * p[1] + finalW.at<float>(1, 2) - p[1];
        maxDisp = std::max(maxDisp, std::sqrt(dx * dx + dy * dy));
    }
    t.shiftPx = std::sqrt(finalW.at<float>(0, 2) * finalW.at<float>(0, 2) +
                          finalW.at<float>(1, 2) * finalW.at<float>(1, 2));

    const float maxAllowed = kMaxCornerShiftFrac * std::sqrt(w * w + h * h);
    if (maxDisp > maxAllowed) {
        char b[96];
        snprintf(b, sizeof(b), "corner shift %.1f > %.1f", maxDisp, maxAllowed);
        t.rejectionReason = b;
        return false;
    }

    // Photometry (gain/offset) for this frame vs anchor.
    float gain[3], offset[3];
    double pcc = 0.0;
    try {
        estimatePhotometry(frame, finalW, gain, offset, &pcc);
    } catch (...) {
        for (int c = 0; c < 3; c++) { gain[c] = 1.0f; offset[c] = 0.0f; }
    }
    for (int c = 0; c < 3; c++) {
        t.gain[c] = gain[c];
        t.offset[c] = offset[c];
    }

    // NOTE: deliberately NO hard "exposure mismatch" rejection here.
    // A global gain estimate cannot separate a bracketed exposure from a scene that
    // legitimately changed brightness. Measured on a real sunset timelapse (80 frames,
    // which is inside the +-90 frame scan window) every frame would have been rejected,
    // collapsing a core use case to anchor-only. Photometric correction plus the motion
    // gate degrade a true bracket gracefully instead; MFSR is simply not an HDR merge.

    float motionDiff = 0.0f;
    try {
        motionDiff = estimateMotionDiff(frame, finalW, gain, offset);
    } catch (...) { motionDiff = 0.0f; }
    t.motionDiff = motionDiff;

    // Residual-based alignment check. A residual in levels divided by the anchor's
    // median gradient is approximately the alignment error in pixels. Measured on real
    // bursts: good alignments land at 0.16-0.28 px, a wrong-but-self-consistent ORB+ECC
    // solution (5-9 deg rotation on repetitive rock) at 1.55-1.75 px while still
    // reporting ECC correlation 0.77-0.90. Neither the correlation nor the corner-shift
    // gate catches that; this does.
    t.alignErrPx = motionDiff / std::max(anchorGradMedian_, 1.0f);

    // Photometric saturation invalidates this test. If gain or offset is pinned at its
    // clamp, the frame could not be mapped onto the anchor, so the residual reflects an
    // EXPOSURE difference, not a bad alignment. Measured on a real sunset timelapse on a
    // Pixel 4a: 21 of 50 frames were rejected as "misaligned" at 1.5-1.8 px while their
    // ECC correlation was 0.95 and shift 0.3 px, purely because offset sat at -40.
    // In that state only a gross mismatch should reject.
    bool photoSaturated = false;
    for (int c = 0; c < 3; c++) {
        if (gain[c] <= kMinGain + 0.01f || gain[c] >= kMaxGain - 0.01f ||
            offset[c] <= -kMaxOffset + 0.01f || offset[c] >= kMaxOffset - 0.01f) {
            photoSaturated = true;
        }
    }

    const bool gradReliable = anchorGradMedian_ >= 2.0f;
    const float errLimit = photoSaturated ? 4.0f : 1.2f;
    const bool misaligned = photoSaturated
        ? (motionDiff > 60.0f)          // only a gross mismatch survives saturation
        : ((gradReliable && t.alignErrPx > errLimit) ||
           (!gradReliable && motionDiff > 10.0f));

    if (misaligned) {
        char b[128];
        snprintf(b, sizeof(b), "misaligned (%.2f px, residual %.1f)", t.alignErrPx, motionDiff);
        t.rejectionReason = b;
        return false;
    }

    // Weight: relative sharpness (less motion blur -> higher weight) x alignment quality.
    // NOTE: shift magnitude is NOT penalised. Sub-pixel displacement is the signal SR
    // needs, not a defect; penalising it threw away the most useful frames.
    float rel = anchorSharpness_ > 1e-3 ? (float)(t.sharpness / anchorSharpness_) : 1.0f;
    rel = clampf(rel, 0.25f, 1.5f);
    float alignQ = (float)((cc - kMinEccCorrelation) / (0.98 - kMinEccCorrelation));
    alignQ = clampf(alignQ, 0.35f, 1.0f);

    t.accumulationWeight = rel * alignQ;
    t.accepted = true;

    storedTransforms_.push_back(finalW.clone());
    storedWeights_.push_back(t.accumulationWeight);
    for (int c = 0; c < 3; c++) {
        storedGains_.push_back(gain[c]);
        storedOffsets_.push_back(offset[c]);
    }
    storedMotionDiff_.push_back(motionDiff);

    if (storedTransforms_.size() % 10 == 0) {
        MFSR_LOGI("Stored %zu transforms", storedTransforms_.size());
    }
    return true;
}

// ============================================================================
// Pass 2: forward splatting
// ============================================================================

// ============================================================================
// Overshoot ("halo") measurement
//
// A camera JPEG that the ISP already sharpened carries a bright/dark fringe just
// outside every strong edge. Re-sharpening such an image on top of the MFSR stack
// makes those fringes visibly crunchy. We measure the fringe on isolated steps
// only (both plateaus must be flat) so natural texture does not masquerade as a
// halo, then cap the sharpening to a budget derived from it.
// ============================================================================

namespace {

struct EdgeSample {
    float mag;
    int x, y;
};

// Returns the median (overshoot / step height) over isolated step edges, or a
// negative value when the image has too few clean steps to judge.
float haloRatio(const cv::Mat& grayIn, int half, int maxEdges) {
    if (grayIn.empty()) return -1.0f;
    const int H = grayIn.rows, W = grayIn.cols;
    if (W < 4 * half + 8 || H < 4 * half + 8) return -1.0f;

    Mat gray;
    if (grayIn.channels() != 1) cvtColor(grayIn, gray, COLOR_BGR2GRAY);
    else gray = grayIn;

    Mat gf;
    gray.convertTo(gf, CV_32F);

    Mat gx, gy;
    Sobel(gf, gx, CV_32F, 1, 0, 3);
    Sobel(gf, gy, CV_32F, 0, 1, 3);

    std::vector<EdgeSample> cand;
    cand.reserve(60000);
    const int border = half + 2;
    for (int y = border; y < H - border; y += 2) {
        const float* px = gx.ptr<float>(y);
        const float* py = gy.ptr<float>(y);
        for (int x = border; x < W - border; x += 2) {
            const float m = std::sqrt(px[x] * px[x] + py[x] * py[x]);
            if (m > 12.0f) cand.push_back({m, x, y});
        }
    }
    if (cand.size() < 40) return -1.0f;

    size_t keep = std::min<size_t>(cand.size(), (size_t)maxEdges * 3);
    std::partial_sort(cand.begin(), cand.begin() + keep, cand.end(),
                      [](const EdgeSample& a, const EdgeSample& b) { return a.mag > b.mag; });

    const int PL = 2 * half + 1;
    const int k = std::max(2, PL / 4);
    std::vector<float> prof(PL);
    std::vector<float> ratios;
    ratios.reserve((size_t)maxEdges);
    std::vector<unsigned char> used((size_t)W * H, 0);

    for (size_t i = 0; i < keep && ratios.size() < (size_t)maxEdges; i++) {
        const EdgeSample& e = cand[i];
        if (used[(size_t)e.y * W + e.x]) continue;

        const float dx = gx.at<float>(e.y, e.x);
        const float dy = gy.at<float>(e.y, e.x);
        const float len = std::sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) continue;
        const float ux = dx / len, uy = dy / len;

        bool okProfile = true;
        for (int t = -half; t <= half; t++) {
            const float fx = (float)e.x + (float)t * ux;
            const float fy = (float)e.y + (float)t * uy;
            const int x0 = (int)std::floor(fx), y0 = (int)std::floor(fy);
            const float ax = fx - (float)x0, ay = fy - (float)y0;
            if (x0 < 0 || y0 < 0 || x0 + 1 >= W || y0 + 1 >= H) { okProfile = false; break; }
            const float* r0 = gf.ptr<float>(y0);
            const float* r1 = gf.ptr<float>(y0 + 1);
            prof[t + half] = (1 - ax) * (1 - ay) * r0[x0] + ax * (1 - ay) * r0[x0 + 1]
                           + (1 - ax) * ay * r1[x0] + ax * ay * r1[x0 + 1];
        }
        if (!okProfile) continue;

        auto segMedian = [&](int s, int n) {
            std::vector<float> v(prof.begin() + s, prof.begin() + s + n);
            std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
            return v[v.size() / 2];
        };
        auto segStd = [&](int s, int n, float m) {
            double acc = 0;
            for (int j = 0; j < n; j++) {
                const double d = (double)prof[s + j] - (double)m;
                acc += d * d;
            }
            return (float)std::sqrt(acc / (double)n);
        };

        float lo = segMedian(0, k);
        float hi = segMedian(PL - k, k);
        if (std::fabs(hi - lo) < 45.0f) continue;
        // Both sides must be flat: this is what rejects texture edges.
        if (segStd(0, k, lo) > 6.0f || segStd(PL - k, k, hi) > 6.0f) continue;

        if (hi < lo) {
            for (int j = 0; j < PL; j++) prof[j] = -prof[j];
            std::swap(lo, hi);
        }
        const float mx = *std::max_element(prof.begin(), prof.end());
        const float mn = *std::min_element(prof.begin(), prof.end());
        const float over = std::max(0.0f, std::max(mx - hi, lo - mn) - 1.0f);
        ratios.push_back(over / (hi - lo));

        for (int yy = std::max(0, e.y - 5); yy <= std::min(H - 1, e.y + 5); yy++) {
            for (int xx = std::max(0, e.x - 5); xx <= std::min(W - 1, e.x + 5); xx++) {
                used[(size_t)yy * W + xx] = 1;
            }
        }
    }

    if (ratios.size() < 8) return -1.0f;
    const size_t mid = ratios.size() / 2;
    std::nth_element(ratios.begin(), ratios.begin() + mid, ratios.end());
    return ratios[mid];
}

// Pick the crop with the most edge energy: halos live on edges, so measuring a
// flat sky region would report "no halo" no matter how hard we sharpened.
cv::Rect pickEdgeCrop(const cv::Mat& bgr, int cw, int ch) {
    const int W = bgr.cols, H = bgr.rows;
    cw = std::min(cw, W);
    ch = std::min(ch, H);
    if (cw <= 0 || ch <= 0) return Rect(0, 0, 0, 0);

    Mat gray;
    if (bgr.channels() == 1) gray = bgr;
    else cvtColor(bgr, gray, COLOR_BGR2GRAY);

    const double s = std::min(1.0, 800.0 / (double)W);
    Mat small;
    resize(gray, small, Size(std::max(8, (int)(W * s)), std::max(8, (int)(H * s))), 0, 0, INTER_AREA);

    Mat gx, gy;
    Sobel(small, gx, CV_32F, 1, 0, 3);
    Sobel(small, gy, CV_32F, 0, 1, 3);
    Mat mag;
    magnitude(gx, gy, mag);

    const int kw = std::max(1, std::min(small.cols, (int)(cw * s)));
    const int kh = std::max(1, std::min(small.rows, (int)(ch * s)));
    Mat box;
    boxFilter(mag, box, CV_32F, Size(kw, kh), Point(-1, -1), false, BORDER_REPLICATE);

    double mn, mx;
    Point loc;
    minMaxLoc(box, &mn, &mx, nullptr, &loc);

    const int x = (int)(loc.x / s) - cw / 2;
    const int y = (int)(loc.y / s) - ch / 2;
    const int cx = std::max(0, std::min(x, W - cw));
    const int cy = std::max(0, std::min(y, H - ch));
    return Rect(cx, cy, cw, ch);
}

}  // namespace

int MFSREngineV2::suggestMaxScale() const {
    // Coverage of an S x S HR phase grid depends on how the accepted frames' sampling
    // phases are distributed, NOT on how many frames there are. A locked-off tripod can
    // supply hundreds of frames that all sit on the same phase and add no resolution.
    //
    // Measured on real material: a 158-frame tripod timelapse filled only 7/16 cells at
    // 4x (44%) and 6/9 at 3x (67%) -> 4x would have produced a 44MP image with more than
    // half the grid unsampled. Even a 100-frame handheld-style set left 27.7% of the 4x
    // grid empty. The same rule leaves normal handheld bursts untouched.
    const int n = (int)storedTransforms_.size();
    if (n < 4) return 2;

    for (int S = 4; S >= 3; --S) {
        if (n < S * S) continue;          // too few frames to judge this scale
        std::vector<char> occ((size_t)S * S, 0);
        int used = 0;
        for (const auto& w : storedTransforms_) {
            const float tx = w.at<float>(0, 2);
            const float ty = w.at<float>(1, 2);
            const float fx = tx - std::floor(tx);
            const float fy = ty - std::floor(ty);
            int bx = (int)(fx * (float)S);
            int by = (int)(fy * (float)S);
            bx = std::min(S - 1, std::max(0, bx));
            by = std::min(S - 1, std::max(0, by));
            if (!occ[(size_t)by * S + bx]) {
                occ[(size_t)by * S + bx] = 1;
                used++;
            }
        }
        const int need = (int)std::ceil(0.85f * (float)(S * S));
        MFSR_LOGI("Phase coverage at %dx: %d/%d cells (need %d)", S, used, S * S, need);
        if (used >= need) return S;
    }
    return 2;
}

void MFSREngineV2::setRenderScale(float s) {
    if (s <= 0.0f) return;
    if (!accumSum_.empty()) {
        MFSR_LOGW("setRenderScale(%.2f) ignored: accumulation already allocated", s);
        return;
    }
    scaleFactor_ = s;
    if (!anchorRGB_.empty()) {
        hrCols_ = (int)std::lround(anchorRGB_.cols * s);
        hrRows_ = (int)std::lround(anchorRGB_.rows * s);
    }
    MFSR_LOGI("Render scale -> %.2fx (%dx%d)", s, hrCols_, hrRows_);
}

void MFSREngineV2::initAccumulation() {
    accumSum_.create(hrRows_, hrCols_, CV_32FC3);
    accumSum_.setTo(Scalar(0, 0, 0));
    accumCount_.create(hrRows_, hrCols_, CV_32FC1);
    accumCount_.setTo(Scalar(0));

    const float one[3] = {1.0f, 1.0f, 1.0f};
    const float zero[3] = {0.0f, 0.0f, 0.0f};
    splatFrame(anchorRGB_, Mat::eye(2, 3, CV_32F), 1.0f, one, zero, false);
    accumulatedFrames_ = 1;
    motionRejectedSamples_ = 0;

    // Threshold for motion-aware fusion, derived from the per-frame median
    // |frame - anchor| measured during pass 1. Static scenes sit at the noise floor;
    // moving water/leaves/clouds sit far above it.
    //
    // IMPORTANT: the upper clamp must stay generous. That median is not pure noise —
    // it also contains legitimate sub-pixel interpolation error, which scales with
    // local contrast (measured 18.7 levels on a high-contrast synthetic grating vs
    // 3.2 on a soft waterfall). A tight cap rejected 75% of samples and destroyed the
    // super-resolution signal (recovered amplitude 4.3% -> 2.4%).
    if (!storedMotionDiff_.empty()) {
        std::vector<float> v = storedMotionDiff_;
        size_t mid = v.size() / 2;
        std::nth_element(v.begin(), v.begin() + mid, v.end());
        const float med = v[mid];
        motionThresh_ = std::min(96.0f, std::max(8.0f, 3.5f * med));
        MFSR_LOGI("Motion gate: median diff %.2f -> threshold %.1f levels", med, motionThresh_);
    } else {
        motionThresh_ = 12.0f;
    }

    MFSR_LOGI("initAccumulation: anchor splatted into %dx%d HR grid", hrCols_, hrRows_);

    // Measure the overshoot the camera/ISP already applied. Done once, on an
    // edge-rich full-resolution crop of the anchor. A negative result means the
    // scene has too few clean steps to judge, in which case we keep full strength.
    sourceHalo_ = -1.0f;
    if (!anchorGray_.empty()) {
        const int cw = std::min(1400, anchorGray_.cols);
        const int ch = std::min(1050, anchorGray_.rows);
        const Rect c = pickEdgeCrop(anchorGray_, cw, ch);
        if (c.width > 0 && c.height > 0) {
            sourceHalo_ = haloRatio(anchorGray_(c), 10, 400);
        }
        if (sourceHalo_ < 0) {
            MFSR_LOGI("Source halo: not measurable (too few clean edges)");
        } else {
            MFSR_LOGI("Source halo: %.1f%% overshoot", sourceHalo_ * 100.0);
        }
    }
}

void MFSREngineV2::accumulateFrame(const cv::Mat& frame, int callIndex) {
    if (frame.empty()) return;
    if (accumSum_.empty()) initAccumulation();

    if (highBitOutput_) {
        static bool logged = false;
        if (!logged) {
            logged = true;
            MFSR_LOGI("accumulateFrame: depth=%d channels=%d (16U=%d), highBit=%d",
                      frame.depth(), frame.channels(), frame.depth() == CV_16U, (int)highBitOutput_);
        }
    }

    if (callIndex < 0 || callIndex >= (int)transformOfCall_.size()) {
        MFSR_LOGW("accumulateFrame: callIndex %d out of range (%zu calls)", callIndex, transformOfCall_.size());
        return;
    }
    const int ti = transformOfCall_[callIndex];
    if (ti < 0) {
        // This frame was rejected during alignment (or failed to decode).
        // Skipping it is the whole point of the mapping table.
        return;
    }
    if (ti >= (int)storedTransforms_.size()) return;

    const float* g = &storedGains_[(size_t)ti * 3];
    const float* o = &storedOffsets_[(size_t)ti * 3];
    splatFrame(frame, storedTransforms_[ti], storedWeights_[ti], g, o, true);
    accumulatedFrames_++;
}

void MFSREngineV2::splatFrame(const Mat& frame, const Mat& affine, float weight,
                              const float* gain, const float* offset, bool motionReject) {
    Mat rgb;
    const int ch = frame.channels();
    if (ch == 4) cvtColor(frame, rgb, COLOR_RGBA2RGB);
    else if (ch == 1) cvtColor(frame, rgb, COLOR_GRAY2RGB);
    else rgb = frame;

    // Work in float 0..255. For CV_16U this keeps 1/257 of a level, so every gate
    // (8..96 levels) and the photometric correction stay on their calibrated scale
    // while the accumulation really carries 16-bit precision. 8-bit input is
    // converted to the identical values, so its numbers do not change.
    Mat rgbf;
    if (rgb.depth() == CV_16U) rgb.convertTo(rgbf, CV_32F, 1.0 / 257.0);
    else if (rgb.depth() == CV_32F) rgbf = rgb;
    else rgb.convertTo(rgbf, CV_32F);

    const float s = scaleFactor_;
    const float a = s * affine.at<float>(0, 0);
    const float b = s * affine.at<float>(0, 1);
    const float tx = s * affine.at<float>(0, 2);
    const float c = s * affine.at<float>(1, 0);
    const float d = s * affine.at<float>(1, 1);
    const float ty = s * affine.at<float>(1, 2);

    // 1x-space affine, used to look up the anchor for motion rejection.
    const float l00 = affine.at<float>(0, 0), l01 = affine.at<float>(0, 1), l02 = affine.at<float>(0, 2);
    const float l10 = affine.at<float>(1, 0), l11 = affine.at<float>(1, 1), l12 = affine.at<float>(1, 2);
    const bool canCheckMotion = motionReject && !anchorRGB_.empty();
    const int acw = anchorRGB_.cols, ach = anchorRGB_.rows;

    const int fw = rgbf.cols;
    const int fh = rgbf.rows;

    for (int y = 0; y < fh; y++) {
        const Vec3f* row = rgbf.ptr<Vec3f>(y);
        const float u0 = b * y + tx;
        const float v0 = d * y + ty;

        for (int x = 0; x < fw; x++) {
            const float u = a * x + u0;
            const float v = c * x + v0;
            const int x0 = (int)std::floor(u);
            const int y0 = (int)std::floor(v);
            const float fx = u - (float)x0;
            const float fy = v - (float)y0;

            const Vec3f& px = row[x];
            float vals[3];
            vals[0] = std::min(255.0f, std::max(0.0f, px[0] * gain[0] + offset[0]));
            vals[1] = std::min(255.0f, std::max(0.0f, px[1] * gain[1] + offset[1]));
            vals[2] = std::min(255.0f, std::max(0.0f, px[2] * gain[2] + offset[2]));

            if (canCheckMotion) {
                const float au = l00 * x + l01 * y + l02;
                const float av = l10 * x + l11 * y + l12;
                if (au >= 0 && av >= 0 && au < acw - 1 && av < ach - 1) {
                    const int ax = (int)au, ay = (int)av;
                    const float afx = au - ax, afy = av - ay;
                    const Vec3b* a0 = anchorRGB_.ptr<Vec3b>(ay);
                    const Vec3b* a1 = anchorRGB_.ptr<Vec3b>(ay + 1);
                    float dmax = 0.0f;
                    for (int cc = 0; cc < 3; cc++) {
                        const float aval = (1 - afx) * (1 - afy) * a0[ax][cc]
                                         + afx * (1 - afy) * a0[ax + 1][cc]
                                         + (1 - afx) * afy * a1[ax][cc]
                                         + afx * afy * a1[ax + 1][cc];
                        dmax = std::max(dmax, std::fabs(vals[cc] - aval));
                    }
                    if (dmax > motionThresh_) {
                        motionRejectedSamples_++;
                        continue;   // moving content: leave it to the anchor
                    }
                }
            }

            deposit(accumSum_, accumCount_, x0,     y0,     (1 - fx) * (1 - fy) * weight, vals, hrCols_, hrRows_);
            deposit(accumSum_, accumCount_, x0 + 1, y0,     fx       * (1 - fy) * weight, vals, hrCols_, hrRows_);
            deposit(accumSum_, accumCount_, x0,     y0 + 1, (1 - fx) * fy       * weight, vals, hrCols_, hrRows_);
            deposit(accumSum_, accumCount_, x0 + 1, y0 + 1, fx       * fy       * weight, vals, hrCols_, hrRows_);
        }
    }
}

// ============================================================================
// Finalize
// ============================================================================

void MFSREngineV2::normalizedConvolution(Mat& output) {
    // 16-bit mode: the accumulator holds 0..255 floats with 1/257 precision, so the
    // output keeps that precision instead of quantising back to 8 bits.
    output.create(hrRows_, hrCols_, highBitOutput_ ? CV_16UC3 : CV_8UC3);
    output.setTo(Scalar(0, 0, 0));

    // Density compensation: blur numerator and weight by the same Gaussian, then
    // divide. This makes the deposition PSF stationary and fills any sampling
    // holes, which is what removes the splat "dither" without destroying detail.
    //
    // The blur being compensated is the splat footprint (one HR pixel), which does not
    // grow with S, so sigma must NOT scale linearly. Measured on a 20-frame 2x stack:
    //   0.56 px (0.28*S) -> HF 0.88, noise 0.21
    //   0.45 px (capped) -> HF 1.07, noise 0.25
    //   0.20 px          -> HF 1.21, noise 0.28
    // Capping at 0.45 recovers ~22% more detail for +0.04 noise while keeping the
    // 1.5x behaviour that was tuned on real bursts (0.28*1.5 = 0.42).
    const float sg = clampf(0.28f * scaleFactor_, 0.25f, 0.45f);
    const int B = (int)std::ceil(3.0f * sg);
    const int TS = 384;

    for (int ty = 0; ty < hrRows_; ty += TS) {
        const int th = std::min(TS, hrRows_ - ty);
        for (int tx = 0; tx < hrCols_; tx += TS) {
            const int tw = std::min(TS, hrCols_ - tx);

            const int ex0 = std::max(0, tx - B);
            const int ey0 = std::max(0, ty - B);
            const int ex1 = std::min(hrCols_, tx + tw + B);
            const int ey1 = std::min(hrRows_, ty + th + B);

            const Rect ext(ex0, ey0, ex1 - ex0, ey1 - ey0);
            Mat a = accumSum_(ext);
            Mat c = accumCount_(ext);

            Mat ab, cb;
            GaussianBlur(a, ab, Size(0, 0), sg, sg, BORDER_REPLICATE);
            GaussianBlur(c, cb, Size(0, 0), sg, sg, BORDER_REPLICATE);

            Mat abd = ab(Rect(tx - ex0, ty - ey0, tw, th));
            Mat cbd = cb(Rect(tx - ex0, ty - ey0, tw, th));
            cbd += Scalar(1e-6);

            // accumSum_ is 3-channel, accumCount_ is 1-channel. divide() requires
            // matching channel counts, so broadcast the weight to BGR first. Before
            // this fix EVERY render at EVERY scale threw here (the comment used to
            // claim "the 3x/4x path", which is wrong) and the caller saved a 292 KB
            // black JPEG for two days.
            Mat cbd3;
            cvtColor(cbd, cbd3, COLOR_GRAY2BGR);

            Mat tile;
            divide(abd, cbd3, tile);
            if (highBitOutput_) {
                static bool loggedTile = false;
                if (!loggedTile) {
                    loggedTile = true;
                    double mn, mx;
                    minMaxLoc(tile.reshape(1), &mn, &mx);
                    MFSR_LOGI("normalizedConvolution tile: depth=%d range=[%.3f, %.3f]",
                              tile.depth(), mn, mx);
                }
                tile.convertTo(tile, CV_16UC3, 257.0);
            } else {
                tile.convertTo(tile, CV_8UC3);
            }
            tile.copyTo(output(Rect(tx, ty, tw, th)));
        }
    }
}

void MFSREngineV2::sharpenBanded(Mat& bgr, float amountA, float amountB) {
    if (bgr.empty()) return;
    if (amountA <= 0.0f && amountB <= 0.0f) return;

    const float sigmaA = std::max(1.0f, 1.15f * scaleFactor_);
    const float sigmaB = std::max(1.6f, 2.65f * scaleFactor_);
    const int B = (int)std::ceil(3.0f * sigmaB);
    const int TS = 512;
    const float limit = 15.0f;
    const int H = bgr.rows;
    const int W = bgr.cols;

    auto applyMasked = [&](Mat& Lm, float sigma, float amount) {
        if (amount <= 0.0f) return;
        Mat blur;
        GaussianBlur(Lm, blur, Size(0, 0), sigma, sigma, BORDER_REPLICATE);

        Mat diff;
        subtract(Lm, blur, diff);

        // Soft clamp: d * limit / (limit + |d|) -> compresses overshoot, no hard banding.
        Mat absd;
        absdiff(diff, Scalar(0), absd);
        absd += Scalar(limit);
        Mat scaledNum = diff * limit;
        Mat scaled;
        divide(scaledNum, absd, scaled);

        // Edge mask from the blurred luminance: 0 in flat areas, 1 on real edges.
        Mat gx, gy;
        Sobel(blur, gx, CV_32F, 1, 0, 3);
        Sobel(blur, gy, CV_32F, 0, 1, 3);
        Mat mag;
        magnitude(gx, gy, mag);
        mag = (mag - 1.5f) * 0.25f;
        cv::max(mag, Scalar(0), mag);
        cv::min(mag, Scalar(1), mag);

        Mat g;
        multiply(scaled, mag, g);
        Lm += g * amount;
    };

    for (int ty = 0; ty < H; ty += TS) {
        const int th = std::min(TS, H - ty);
        const int ey0 = std::max(0, ty - B);
        const int ey1 = std::min(H, ty + th + B);

        Mat sub = bgr(Rect(0, ey0, W, ey1 - ey0));
        Mat lab;
        cvtColor(sub, lab, COLOR_BGR2Lab);
        std::vector<Mat> ch;
        split(lab, ch);

        Mat L;
        ch[0].convertTo(L, CV_32F);

        applyMasked(L, sigmaA, amountA);
        applyMasked(L, sigmaB, amountB);

        cv::max(L, Scalar(0), L);
        cv::min(L, Scalar(255), L);

        const Rect core(0, ty - ey0, W, th);
        Mat Lcore = L(core);
        Mat ch0core = ch[0](core);
        Lcore.convertTo(ch0core, CV_8U);

        Mat labCore = lab(core);
        Mat outTmp;
        cvtColor(labCore, outTmp, COLOR_Lab2BGR);
        outTmp.copyTo(bgr(Rect(0, ty, W, th)));
    }
}

void MFSREngineV2::detailEnhance(Mat& bgr) {
    if (bgr.empty()) return;

    if (bgr.depth() == CV_16U) {
        // True 16-bit fusion: sharpen in 8-bit space (every threshold in this
        // function is calibrated in levels), then add that delta back at 16-bit
        // scale so the 1/257 gradations survive.
        //
        // MEMORY: this branch used to hold ~84 bytes per HR pixel at once (seven full
        // CV_32FC3 buffers plus two clones). At 12 MP x 1.5 that is ~1.45 GB and the
        // allocation failed into the bicubic anchor fallback on a 4 GB phone. Every
        // intermediate is now converted in place or released as soon as it is dead;
        // peak is ~45 B/px. Do not reintroduce a `Mat x = y.clone()` here without
        // releasing the source first.
        Mat base8;
        bgr.convertTo(base8, CV_8UC3, 1.0 / 257.0);
        // Keep the UNSHARPENED 8-bit base: the delta must be (sharpened8 - base8),
        // not (sharpened8 - base16/257) or the addition cancels base16 exactly and
        // the output degenerates to sharpened8 * 257 (all values multiples of 257).
        Mat base8Unsharpened = base8.clone();
        detailEnhance(base8);                    // 8-bit path, unchanged

        Mat base16f;
        bgr.convertTo(base16f, CV_32FC3);        // read bgr before it is overwritten

        Mat base8f;
        base8Unsharpened.convertTo(base8f, CV_32FC3);
        base8Unsharpened.release();

        Mat sharp8f;
        base8.convertTo(sharp8f, CV_32FC3);
        base8.release();

        subtract(sharp8f, base8f, sharp8f);      // in place: delta = S(I8) - I8
        base8f.release();

        // 257 == 65535/255, so a full 8-bit level maps to a full 16-bit level.
        addWeighted(base16f, 1.0, sharp8f, 257.0, 0.0, base16f);
        sharp8f.release();

        base16f.convertTo(bgr, CV_16UC3);        // saturate_cast<ushort>, 0..65535
        base16f.release();
        return;
    }

    // Two-scale, edge-masked unsharp on L*, processed in horizontal bands so the
    // float working buffers stay small even for a 48MP output.
    //
    //   pass 1 (radius 1.15*S): micro-contrast / deconvolution of the lens + fusion PSF
    //   pass 2 (radius 2.65*S): coarser lens blur, low amount, restores local contrast
    //
    // Strength is chosen per image instead of being fixed. A soft f/4 SLR frame and
    // an already-ISP-sharpened phone JPEG need very different treatment: ground-truth
    // tests showed the fidelity optimum at amount ~0.6 for soft input but 0.0 for
    // pre-sharpened input, and a fixed 1.05 cost 1.07 dB PSNR on the latter while
    // pushing visible overshoot from 26% to 19%+ on real Pixel frames.
    //
    // So: measure the overshoot the source already has, allow a budget of
    // source + 5 points, and use the largest amount that stays inside it.
    float amountA = 1.05f;
    float amountB = 0.30f;

    if (sourceHalo_ >= 0.0f) {
        // The overshoot metric cannot tell a genuine bright rim (water against rock)
        // from a sharpening halo, so it is only allowed to TRIM, never disable:
        // the ladder stops at 0.75 and the budget floor is 10%.
        const float budget = std::min(0.15f, std::max(0.10f, sourceHalo_ + 0.05f));
        const int profileHalf = std::max(10, (int)(10.0f * scaleFactor_));
        const Rect c = pickEdgeCrop(bgr, 1400, 1050);
        if (c.width > 0 && c.height > 0) {
            static const float cands[] = {1.05f, 0.90f, 0.75f};
            Mat crop = bgr(c).clone();
            float picked = 0.75f;              // floor: never disable sharpening
            for (float a : cands) {
                Mat t = crop.clone();
                sharpenBanded(t, a, 0.30f * a / 1.05f);
                Mat gr;
                cvtColor(t, gr, COLOR_BGR2GRAY);
                const float h = haloRatio(gr, profileHalf, 300);
                if (h < 0.0f || h <= budget) { picked = a; break; }
            }
            amountA = picked;
            amountB = 0.30f * picked / 1.05f;
            MFSR_LOGI("Detail: source halo %.1f%% -> budget %.1f%%, sharpen %.2f/%.2f",
                      sourceHalo_ * 100.0, budget * 100.0, amountA, amountB);
        } else {
            MFSR_LOGI("Detail: no edge crop, fixed sharpen %.2f/%.2f", amountA, amountB);
        }
    } else {
        MFSR_LOGI("Detail: source halo unknown, fixed sharpen %.2f/%.2f", amountA, amountB);
    }

    sharpenBanded(bgr, amountA, amountB);
}

void MFSREngineV2::finalize(cv::Mat& output) {
    if (accumSum_.empty() || accumCount_.empty()) {
        // No accumulation (initAccumulation threw, e.g. OOM on the 64 MP grid, or no
        // pass-2 frame decoded). This used to return a ZEROED but non-empty Mat, which
        // the Kotlin side accepted (it only rejects empty output) and saved as a black
        // JPEG while reporting success. Degrade to the anchor instead, like every
        // other failure path in this function.
        MFSR_LOGE("finalize: no accumulation data — anchor bicubic fallback");
        if (!anchorRGB_.empty()) {
            if (hrRows_ <= 0 || hrCols_ <= 0) {
                hrCols_ = (int)std::lround(anchorRGB_.cols * (double)scaleFactor_);
                hrRows_ = (int)std::lround(anchorRGB_.rows * (double)scaleFactor_);
            }
            try {
                resize(anchorRGB_, output, Size(hrCols_, hrRows_), 0, 0, INTER_CUBIC);
                cvtColor(output, output, COLOR_RGB2BGR);
                detailEnhance(output);
                return;
            } catch (const cv::Exception& e) {
                MFSR_LOGE("finalize: anchor fallback failed (%s)", e.what());
            } catch (...) {
                MFSR_LOGE("finalize: anchor fallback failed (unknown)");
            }
        }
        output.create(hrRows_, hrCols_, CV_8UC3);
        output.setTo(Scalar(0));
        return;
    }

    MFSR_LOGI("Finalize: %dx%d from %d frames", hrCols_, hrRows_, accumulatedFrames_);

    // Only the anchor contributed (all other frames rejected). A direct bicubic
    // upscale is cleaner and faster than splat + density compensation.
    if (accumulatedFrames_ <= 1) {
        MFSR_LOGW("finalize: anchor-only — using bicubic upscale");
        resize(anchorRGB_, output, Size(hrCols_, hrRows_), 0, 0, INTER_CUBIC);
        cvtColor(output, output, COLOR_RGB2BGR);
        detailEnhance(output);
        return;
    }

    // Diagnostics: how much of the HR grid actually received samples.
    holeCount_ = hrRows_ * hrCols_ - countNonZero(accumCount_);
    meanDensity_ = mean(accumCount_)[0];

    // Pathological case: almost nothing landed inside the HR grid (all frames
    // wiped out by gating or a degenerate warp). Never emit a black image.
    if (meanDensity_ < 0.01) {
        MFSR_LOGW("finalize: density %.4f too low — falling back to bicubic anchor", meanDensity_);
        resize(anchorRGB_, output, Size(hrCols_, hrRows_), 0, 0, INTER_CUBIC);
        cvtColor(output, output, COLOR_RGB2BGR);
        detailEnhance(output);
        return;
    }

    try {
        normalizedConvolution(output);

        const double holeFrac = (double)holeCount_ / (double)std::max(1, hrRows_ * hrCols_);
        MFSR_LOGI("Coverage: %.2f%% holes, mean density %.2f samples/px",
                  100.0 * holeFrac, meanDensity_);

        // Free the large accumulation buffers before the sharpening pass.
        accumSum_.release();
        accumCount_.release();

        // RGB -> BGR for imwrite / Android decoding.
        cvtColor(output, output, COLOR_RGB2BGR);

        detailEnhance(output);

        MFSR_LOGI("Done: %dx%d", output.cols, output.rows);
    } catch (const cv::Exception& e) {
        // Never emit black. A geometry/type mismatch here used to escape to the
        // JNI wrapper, which left the caller's output Mat empty and the app saved
        // a 292 KB black JPEG. Fall back to the anchor at render scale instead.
        MFSR_LOGE("finalize failed (%s) — anchor bicubic fallback", e.what());
        resize(anchorRGB_, output, Size(hrCols_, hrRows_), 0, 0, INTER_CUBIC);
        cvtColor(output, output, COLOR_RGB2BGR);
        detailEnhance(output);
    } catch (...) {
        MFSR_LOGE("finalize failed (unknown) — anchor bicubic fallback");
        resize(anchorRGB_, output, Size(hrCols_, hrRows_), 0, 0, INTER_CUBIC);
        cvtColor(output, output, COLOR_RGB2BGR);
        detailEnhance(output);
    }
}

// ============================================================================
// Optional CLAHE (kept for API parity; disabled by the Kotlin side)
// ============================================================================

void MFSREngineV2::applyCLAHE(Mat& labImage) {
    Ptr<CLAHE> clahe = createCLAHE(1.5, Size(16, 16));
    std::vector<Mat> labPlanes;
    split(labImage, labPlanes);
    clahe->apply(labPlanes[0], labPlanes[0]);
    merge(labPlanes, labImage);
}

void MFSREngineV2::enhanceOutputContrast(Mat& bgrImage) {
    if (bgrImage.empty()) return;
    Mat lab;
    cvtColor(bgrImage, lab, COLOR_BGR2Lab);
    applyCLAHE(lab);
    cvtColor(lab, bgrImage, COLOR_Lab2BGR);
    lab.release();
    MFSR_LOGI("CLAHE contrast enhancement applied");
}
