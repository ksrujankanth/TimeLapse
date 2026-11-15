#include "mfsr_engine.h"
#include <cmath>
#include <algorithm>

using namespace cv;

MFSREngineV2::MFSREngineV2()
    : scaleFactor_(4.0f), hrCols_(0), hrRows_(0),
      ransacThreshold_(2.0f), sigmaClipMultiplier_(2.0f), claheEnabled_(false) {
    orbDetector_ = ORB::create(2000);
}

MFSREngineV2::~MFSREngineV2() {
    storedTransforms_.clear();
    storedWeights_.clear();
    accumSum_.release();
    accumCount_.release();
}

void MFSREngineV2::init(const Mat& anchor, float scale) {
    scaleFactor_ = scale;

    // Safely convert RGBA to RGB natively — JNI wrapper may corrupt 4-channel Mats
    if (anchor.channels() == 4) {
        cvtColor(anchor, anchorRGB_, COLOR_RGBA2RGB);
    } else {
        anchorRGB_ = anchor.clone();
    }

    cvtColor(anchorRGB_, anchorGray_, COLOR_RGB2GRAY);

    // Blur slightly for ORB to reduce noise-induced false keypoints
    Mat blurGray;
    GaussianBlur(anchorGray_, blurGray, Size(3, 3), 1.0);
    orbDetector_->detectAndCompute(blurGray, noArray(), anchorKeypoints_, anchorDescriptors_);

    hrCols_ = (int)(anchor.cols * scale);
    hrRows_ = (int)(anchor.rows * scale);

    storedTransforms_.clear();
    storedWeights_.clear();
    accumSum_.release();
    accumCount_.release();
    telemetry_.clear();

    MFSR_LOGI("Init: %dx%d → %dx%d (%.0fx), %zu anchor keypoints",
              anchor.cols, anchor.rows, hrCols_, hrRows_, scale, anchorKeypoints_.size());
}

void MFSREngineV2::setSceneStats(double medianMse, double p90Mse, double spread, double medianBrightness) {
    // --- Spread-based tuning (existing) ---
    if (spread < 1.5) { ransacThreshold_ = 1.5f; sigmaClipMultiplier_ = 2.0f; }
    else if (spread > 2.5) { ransacThreshold_ = 3.0f; sigmaClipMultiplier_ = 2.5f; }
    else { ransacThreshold_ = 2.0f; sigmaClipMultiplier_ = 2.0f; }

    // --- Low-Light Loosener ---
    // In dark scenes (brightness < 60), camera sensors inject massive Gaussian noise.
    // This noise artificially inflates per-pixel variance across frames, causing
    // sigma clipping to reject healthy shadow pixels as "outliers" → splotchy output.
    // Fix: linearly relax sigmaClipMultiplier_ as brightness drops below 60.
    // At brightness 30 (very dark), sigma rises to 3.5 (from 2.0 base).
    // At brightness 0 (pitch black), sigma rises to 4.0 (maximum leniency).
    if (medianBrightness < 60.0) {
        float darkFactor = (float)(1.0 - medianBrightness / 60.0); // 0.0 at 60, 1.0 at 0
        float relaxedSigma = sigmaClipMultiplier_ + darkFactor * 1.5f; // +0 to +1.5
        sigmaClipMultiplier_ = std::min(relaxedSigma, 4.0f); // cap at 4.0
    }

    MFSR_LOGI("Scene: spread=%.2f brightness=%.0f → ransac=%.1f, sigma=%.1f",
              spread, medianBrightness, ransacThreshold_, sigmaClipMultiplier_);
}

// ============================================================================
// Alignment: ORB coarse (4 DOF affine) + Pyramid ECC fine (Euclidean)
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
    if (des.empty()) return Mat();

    BFMatcher matcher(NORM_HAMMING, false);
    std::vector<std::vector<DMatch>> knn;
    matcher.knnMatch(des, anchorDescriptors_, knn, 2);

    std::vector<Point2f> ptsCurr, ptsAnchor;
    for (auto& m : knn) {
        if (m.size() >= 2 && m[0].distance < 0.75f * m[1].distance) {
            ptsCurr.push_back(kp[m[0].queryIdx].pt);
            ptsAnchor.push_back(anchorKeypoints_[m[0].trainIdx].pt);
        }
    }
    if (ptsCurr.size() < 10) return Mat();

    try {
        Mat mask;
        Mat warp = estimateAffinePartial2D(Mat(ptsCurr), Mat(ptsAnchor), mask, RANSAC, ransacThreshold_);
        if (warp.empty()) return Mat();
        if (outInlierCount) *outInlierCount = countNonZero(mask);
        Mat w32f;
        warp.convertTo(w32f, CV_32F);
        return w32f;
    } catch (...) { return Mat(); }
}

Mat MFSREngineV2::alignPyramidECC(const Mat& currGray, const Mat& initialWarp) {
    const int LEVELS = 3;
    std::vector<Mat> cPyr(LEVELS), aPyr(LEVELS);
    cPyr[0] = currGray;
    aPyr[0] = anchorGray_;
    for (int i = 1; i < LEVELS; i++) {
        pyrDown(cPyr[i-1], cPyr[i]);
        pyrDown(aPyr[i-1], aPyr[i]);
    }

    Mat warp = initialWarp.clone();
    float s = std::pow(2.0f, LEVELS - 1);
    warp.at<float>(0, 2) /= s;
    warp.at<float>(1, 2) /= s;

    for (int lv = LEVELS - 1; lv >= 0; lv--) {
        try {
            findTransformECC(cPyr[lv], aPyr[lv], warp, MOTION_EUCLIDEAN,
                TermCriteria(TermCriteria::COUNT + TermCriteria::EPS, 100, 1e-4));
        } catch (...) {}
        if (lv > 0) {
            warp.at<float>(0, 2) *= 2.0f;
            warp.at<float>(1, 2) *= 2.0f;
        }
    }
    return warp;
}

// ============================================================================
// processFrame — align and store
// ============================================================================

bool MFSREngineV2::processFrame(const Mat& frame) {
    MFSRFrameTelemetry t;
    t.frameIndex = (int)telemetry_.size();
    t.mseScore = -1.0;
    bool ok = processFrameInternal(frame, t);
    telemetry_.push_back(t);
    return ok;
}

bool MFSREngineV2::processFrameWithIndex(const Mat& frame, int idx, double mse) {
    MFSRFrameTelemetry t;
    t.frameIndex = idx;
    t.mseScore = mse;
    bool ok = processFrameInternal(frame, t);
    telemetry_.push_back(t);
    return ok;
}

bool MFSREngineV2::processFrameInternal(const Mat& frame, MFSRFrameTelemetry& t) {
    t.accepted = false;
    if (frame.empty() || frame.size() != anchorRGB_.size()) {
        t.rejectionReason = "size mismatch";
        return false;
    }

    Mat gray;
    int ch = frame.channels();
    if (ch == 1) gray = frame.clone();
    else if (ch == 4) cvtColor(frame, gray, COLOR_RGBA2GRAY);
    else cvtColor(frame, gray, COLOR_RGB2GRAY);

    // Metrics
    Scalar mg = mean(gray);
    t.brightness = (float)mg[0];
    Mat lap; Laplacian(gray, lap, CV_64F);
    Scalar mu, sig; meanStdDev(lap, mu, sig);
    double sharp = sig[0] * sig[0];
    t.sharpness = (float)sharp;

    // ORB
    int inliers = 0;
    Mat orbW = alignORBCoarse(gray, &inliers);
    t.orbMatchCount = inliers;
    if (orbW.empty()) { t.rejectionReason = "ORB failed"; return false; }

    // Pyramid ECC
    Mat finalW = alignPyramidECC(gray, orbW);
    float wd = 0;
    for (int r = 0; r < 2; r++)
        for (int c = 0; c < 3; c++)
            wd += std::abs(finalW.at<float>(r,c) - orbW.at<float>(r,c));
    t.eccConverged = wd > 0.001f;

    // Shift check
    float dx = finalW.at<float>(0,2), dy = finalW.at<float>(1,2);
    float shift = std::sqrt(dx*dx + dy*dy);
    t.shiftPx = shift;
    float maxShift = anchorGray_.cols * 0.15f;
    if (shift > maxShift) {
        char b[64]; snprintf(b, 64, "shift %.1f > %.1f", shift, maxShift);
        t.rejectionReason = b;
        return false;
    }

    // Weight
    float sdiv = (t.brightness < 80) ? 30.0f : 100.0f;
    float w = std::max(0.1f, std::min(1.0f, (float)sharp / sdiv) * (1.0f - shift / maxShift));
    t.accumulationWeight = w;

    storedTransforms_.push_back(finalW.clone());
    storedWeights_.push_back(w);
    t.accepted = true;

    if (storedTransforms_.size() % 10 == 0)
        MFSR_LOGI("Stored %zu transforms", storedTransforms_.size());
    return true;
}

// ============================================================================
// buildHRWarpMatrix — THE CRITICAL MATH
//
// The stored affine T maps: source_pixel → anchor_pixel (at 1x resolution).
// We want a matrix M that maps: source_pixel → HR_tile_pixel.
//
// HR_pixel = Scale * anchor_pixel - tile_offset
//          = Scale * T * source_pixel - tile_offset
//
// So M = S * T, then subtract tile offset from translation.
//
// S = [[scale, 0, 0], [0, scale, 0]]
// T = [[a, b, tx], [c, d, ty]]
// M = [[scale*a, scale*b, scale*tx - tileX],
//      [scale*c, scale*d, scale*ty - tileY]]
//
// Note: we scale the ENTIRE row (including rotation components) because
// S * T multiplies each element in the row by scale. This is correct —
// the rotation/scale components DO get multiplied because the output
// coordinate system is in HR pixels, not anchor pixels.
// ============================================================================

Mat MFSREngineV2::buildHRWarpMatrix(const Mat& affine, int tileX, int tileY) {
    Mat M(2, 3, CV_32F);
    float s = scaleFactor_;
    M.at<float>(0, 0) = affine.at<float>(0, 0) * s;
    M.at<float>(0, 1) = affine.at<float>(0, 1) * s;
    M.at<float>(0, 2) = affine.at<float>(0, 2) * s - tileX;
    M.at<float>(1, 0) = affine.at<float>(1, 0) * s;
    M.at<float>(1, 1) = affine.at<float>(1, 1) * s;
    M.at<float>(1, 2) = affine.at<float>(1, 2) * s - tileY;
    return M;
}

// ============================================================================
// Two-pass memory architecture: accumulate without caching frames
// ============================================================================

// ============================================================================
// initAccumulation — prepare buffers for weighted accumulation
// ============================================================================

void MFSREngineV2::initAccumulation() {
    accumSum_.create(hrRows_, hrCols_, CV_32FC3);
    accumSum_.setTo(Scalar(0, 0, 0));
    accumCount_.create(hrRows_, hrCols_, CV_32FC1);
    accumCount_.setTo(Scalar(0));

    // Accumulate anchor frame with weight 2.0
    const float SENTINEL = -1.0f;
    const Scalar sentinelScalar(SENTINEL, SENTINEL, SENTINEL);
    const float anchorWeight = 2.0f;

    Mat anchorFloat;
    anchorRGB_.convertTo(anchorFloat, CV_32FC3);
    Mat anchorAffine = Mat::eye(2, 3, CV_32F);

    const int TS = 512;
    for (int ty = 0; ty < hrRows_; ty += TS) {
        for (int tx = 0; tx < hrCols_; tx += TS) {
            int tw = std::min(TS, hrCols_ - tx);
            int th = std::min(TS, hrRows_ - ty);
            Size tileSize(tw, th);

            Mat M = buildHRWarpMatrix(anchorAffine, tx, ty);
            Mat warped;
            warpAffine(anchorFloat, warped, M, tileSize, INTER_LANCZOS4, BORDER_CONSTANT, sentinelScalar);

            for (int y = 0; y < th; y++) {
                const Vec3f* wp = warped.ptr<Vec3f>(y);
                Vec3f* sp = accumSum_.ptr<Vec3f>(ty + y);
                float* cp = accumCount_.ptr<float>(ty + y);
                for (int x = 0; x < tw; x++) {
                    if (wp[x][0] >= 0) {
                        sp[tx + x] += wp[x] * anchorWeight;
                        cp[tx + x] += anchorWeight;
                    }
                }
            }
            warped.release();
        }
    }
    anchorFloat.release();
    MFSR_LOGI("initAccumulation: anchor accumulated into %dx%d HR grid", hrCols_, hrRows_);
}

void MFSREngineV2::accumulateFrame(const cv::Mat& frame, int index) {
    if (frame.empty()) return;
    if (index < 0 || index >= (int)storedTransforms_.size()) return;

    if (accumSum_.empty()) {
        initAccumulation();
    }

    const int TS = 512;
    const float SENTINEL = -1.0f;
    const Scalar sentinelScalar(SENTINEL, SENTINEL, SENTINEL);

    Mat frameFloat;
    if (frame.channels() == 4) {
        Mat rgb;
        cvtColor(frame, rgb, COLOR_RGBA2RGB);
        rgb.convertTo(frameFloat, CV_32FC3);
    } else {
        frame.convertTo(frameFloat, CV_32FC3);
    }

    for (int ty = 0; ty < hrRows_; ty += TS) {
        for (int tx = 0; tx < hrCols_; tx += TS) {
            int tw = std::min(TS, hrCols_ - tx);
            int th = std::min(TS, hrRows_ - ty);
            Size tileSize(tw, th);

            Mat M = buildHRWarpMatrix(storedTransforms_[index], tx, ty);
            float weight = storedWeights_[index];

            Mat warped;
            warpAffine(frameFloat, warped, M, tileSize, INTER_LANCZOS4, BORDER_CONSTANT, sentinelScalar);

            for (int y = 0; y < th; y++) {
                const Vec3f* wp = warped.ptr<Vec3f>(y);
                Vec3f* sp = accumSum_.ptr<Vec3f>(ty + y);
                float* cp = accumCount_.ptr<float>(ty + y);
                for (int x = 0; x < tw; x++) {
                    if (wp[x][0] >= 0) {
                        sp[tx + x] += wp[x] * weight;
                        cp[tx + x] += weight;
                    }
                }
            }
            warped.release();
        }
    }
    frameFloat.release();
}

// ============================================================================
// finalize — normalize weighted sum, sharpen, convert to BGR
// ============================================================================

void MFSREngineV2::finalize(cv::Mat& output) {
    if (accumSum_.empty() || accumCount_.empty()) {
        MFSR_LOGE("finalize: no accumulation data");
        output.create(hrRows_, hrCols_, CV_8UC3);
        output.setTo(Scalar(0));
        return;
    }

    MFSR_LOGI("Finalize: %dx%d from accumulated buffers", hrCols_, hrRows_);

    output.create(hrRows_, hrCols_, CV_8UC3);

    for (int y = 0; y < hrRows_; y++) {
        Vec3f* sp = accumSum_.ptr<Vec3f>(y);
        float* cp = accumCount_.ptr<float>(y);
        Vec3b* op = output.ptr<Vec3b>(y);
        for (int x = 0; x < hrCols_; x++) {
            if (cp[x] > 0) {
                Vec3f v = sp[x] / cp[x];
                op[x] = Vec3b(
                    saturate_cast<uchar>(v[0]),
                    saturate_cast<uchar>(v[1]),
                    saturate_cast<uchar>(v[2]));
            } else {
                op[x] = Vec3b(0, 0, 0);
            }
        }
    }

    // RGB → BGR for imwrite
    cvtColor(output, output, COLOR_RGB2BGR);

    // Stronger unsharp mask for HR output — 4× upscaling softens edges,
    // so we need more aggressive sharpening than a 1× pipeline.
    // sigma=2.0 at 4× resolution ≈ sigma=0.5 at 1× (proportional to image size).
    Mat blur;
    GaussianBlur(output, blur, Size(0, 0), 2.0);
    addWeighted(output, 1.5, blur, -0.5, 0, output);

    MFSR_LOGI("Done: %dx%d", output.cols, output.rows);
}

// ============================================================================
// CLAHE — optional contrast enhancement on the final BGR output
// ============================================================================

void MFSREngineV2::applyCLAHE(Mat& labImage) {
    Ptr<CLAHE> clahe = createCLAHE(1.5, Size(16, 16));
    std::vector<Mat> labPlanes;
    split(labImage, labPlanes);
    clahe->apply(labPlanes[0], labPlanes[0]); // Apply only to L channel
    merge(labPlanes, labImage);
}

void MFSREngineV2::enhanceOutputContrast(Mat& bgrImage) {
    if (bgrImage.empty()) return;

    // Convert BGR → LAB, apply CLAHE to L channel, convert back
    Mat lab;
    cvtColor(bgrImage, lab, COLOR_BGR2Lab);
    applyCLAHE(lab);
    cvtColor(lab, bgrImage, COLOR_Lab2BGR);
    lab.release();

    claheEnabled_ = true;
    MFSR_LOGI("CLAHE contrast enhancement applied");
}
