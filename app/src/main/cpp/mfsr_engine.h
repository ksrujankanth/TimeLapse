#pragma once

#include <vector>
#include <string>
#include "opencv2/core.hpp"
#include "opencv2/imgproc.hpp"
#include "opencv2/features2d.hpp"
#include "opencv2/calib3d.hpp"
#include "opencv2/video.hpp"
#include <android/log.h>

#define MFSR_TAG "MFSREngine"
#define MFSR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MFSR_TAG, __VA_ARGS__)
#define MFSR_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, MFSR_TAG, __VA_ARGS__)
#define MFSR_LOGW(...) __android_log_print(ANDROID_LOG_WARN, MFSR_TAG, __VA_ARGS__)
#define MFSR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MFSR_TAG, __VA_ARGS__)

struct MFSRFrameTelemetry {
    int frameIndex = 0;
    double mseScore = -1.0;
    int orbMatchCount = 0;
    bool eccConverged = false;
    double eccCorrelation = 0.0;
    float shiftPx = 0;
    float sharpness = 0;
    float brightness = 0;
    float accumulationWeight = 0;
    float motionDiff = 0;      // median |frame - anchor| over the overlap (8-bit levels)
    float alignErrPx = 0;      // motionDiff / anchor median gradient -> approx alignment error
    bool accepted = false;
    float gain[3] = {1.0f, 1.0f, 1.0f};
    float offset[3] = {0.0f, 0.0f, 0.0f};
    std::string rejectionReason;
};

/**
 * MFSREngineV2 — Multi-Frame Super-Resolution for Android.
 *
 * Pipeline:
 *   init()          -> anchor keypoints + photometric reference
 *   processFrame()  -> ORB coarse + pyramid ECC sub-pixel alignment (to anchor)
 *                      + per-channel photometric gain/offset estimation
 *                      + acceptance gating (ECC correlation, corner displacement)
 *   accumulateFrame()-> forward SPLAT of every low-res sample onto the high-res grid
 *   finalize()      -> density-compensated normalized convolution + detail restoration
 *
 * Why splatting and not warpAffine(INTER_LANCZOS4):
 *   A high-order interpolation reconstructs each frame as a band-limited continuous
 *   signal BEFORE placing it on the HR grid. That low-passes away exactly the
 *   above-Nyquist (aliased) content that multi-frame SR must recover. Measured on a
 *   0.62 cyc/LR-px grating at 2x: LANCZOS4 averaging recovered 1.2% of the true
 *   amplitude (worse than a single bicubic frame at 3.9%), while sample splatting
 *   recovered 15.7% — a ~13x improvement. Splatting deposits raw samples, so the
 *   aliased bands survive to be reconstructed.
 *
 * Alignment convention (verified against OpenCV):
 *   findTransformECC(template, input) returns W with  warpAffine(template, W) ~= input,
 *   i.e. W maps template coords -> input coords. We pass (current, anchor), so the
 *   stored affine maps current-frame coords -> anchor coords. HR position of a
 *   current-frame pixel p is therefore scale * W * p.
 */
class MFSREngineV2 {
public:
    MFSREngineV2();
    ~MFSREngineV2();

    void init(const cv::Mat& anchor, float scale);
    void setSceneStats(double medianMse, double p90Mse, double sceneSpread, double medianBrightness);

    bool processFrame(const cv::Mat& frame);
    bool processFrameWithIndex(const cv::Mat& frame, int frameIndex, double preScanMse);

    void initAccumulation();
    void accumulateFrame(const cv::Mat& frame, int callIndex);
    void finalize(cv::Mat& output);
    void enhanceOutputContrast(cv::Mat& bgrImage);

    int getStoredFrameCount() const { return (int)storedTransforms_.size(); }
    int getTelemetryCount() const { return (int)telemetry_.size(); }
    const MFSRFrameTelemetry& getTelemetry(int i) const { return telemetry_[i]; }

    int getHoleCount() const { return holeCount_; }
    int getAccumulatedFrames() const { return accumulatedFrames_; }
    double getMeanDensity() const { return meanDensity_; }
    int getMotionRejectedSamples() const { return motionRejectedSamples_; }
    float getMotionThreshold() const { return motionThresh_; }

    /**
     * Largest scale factor whose HR phase grid is actually covered by the accepted
     * frames. Coverage depends on sub-pixel diversity, not on frame count: a locked-off
     * tripod can supply 500 frames that all land on the same sampling phase.
     * Returns 2..4, and never below 2 (1.5x is not reduced).
     */
    int suggestMaxScale() const;
    /** Change the render scale before accumulation is allocated. */
    void setRenderScale(float s);
    /**
     * Enable true 16-bit fusion: CV_16U samples are accumulated as 0..255 floats
     * with 1/257 of a level of precision, and normalizedConvolution emits CV_16UC3.
     */
    void setHighBitDepth(bool on) { highBitOutput_ = on; }
    /**
     * Number of processFrame* calls the engine has recorded a transform for.
     * Pass 2 replays by call ordinal, so this MUST equal the number of calls Kotlin
     * made. A mismatch means a call was swallowed before it registered (e.g. a pending
     * JVM exception at JNI entry) and the replay would use the wrong matrix.
     */
    int processedCallCount() const { return (int)transformOfCall_.size(); }

private:
    cv::Mat alignORBCoarse(const cv::Mat& currGray, int* outInlierCount);
    cv::Mat alignPyramidECC(const cv::Mat& currGray, const cv::Mat& initialWarp, double* outCC);
    bool processFrameInternal(const cv::Mat& frame, MFSRFrameTelemetry& t);

    void estimatePhotometry(const cv::Mat& frame, const cv::Mat& warp,
                            float* gain, float* offset, double* outCorrelation);
    float estimateMotionDiff(const cv::Mat& frame, const cv::Mat& warp,
                             const float* gain, const float* offset);
    void splatFrame(const cv::Mat& frame, const cv::Mat& affine, float weight,
                    const float* gain, const float* offset, bool motionReject);
    void normalizedConvolution(cv::Mat& output);
    void detailEnhance(cv::Mat& bgr);
    void sharpenBanded(cv::Mat& bgr, float amountA, float amountB);
    void applyCLAHE(cv::Mat& labImage);

    float scaleFactor_;
    int hrCols_, hrRows_;
    /** True when the caller supplies 16-bit frames and wants a CV_16UC3 result. */
    bool highBitOutput_ = false;

    cv::Mat anchorRGB_;
    cv::Mat anchorGray_;
    cv::Mat anchorSmall_;          // RGB, ~512px wide, for photometric matching
    std::vector<cv::KeyPoint> anchorKeypoints_;
    cv::Mat anchorDescriptors_;
    cv::Ptr<cv::ORB> orbDetector_;
    double anchorSharpness_;
    float anchorGradMedian_;       // median |Sobel| of the anchor (levels/pixel)

    // Overshoot ratio already present in the anchor (-1 = could not be measured).
    // Used to cap the sharpening so an already-crisp camera JPEG is not re-sharpened.
    float sourceHalo_;

    // One affine per ACCEPTED frame: current-frame coords -> anchor coords (1x).
    std::vector<cv::Mat> storedTransforms_;
    std::vector<float> storedWeights_;
    std::vector<float> storedGains_;    // 3 floats per frame (RGB)
    std::vector<float> storedOffsets_;  // 3 floats per frame (RGB)
    std::vector<MFSRFrameTelemetry> telemetry_;

    // call index (order of processFrameWithIndex calls) -> index into storedTransforms_, or -1.
    // This keeps pass 2 in sync even when a frame is rejected or fails to decode.
    std::vector<int> transformOfCall_;

    cv::Mat accumSum_;    // HR CV_32FC3 (RGB)
    cv::Mat accumCount_;  // HR CV_32FC1

    float ransacThreshold_;
    float sigmaClipMultiplier_;

    // Motion-aware fusion: samples that disagree with the anchor by more than this
    // (8-bit levels) are treated as moving and excluded. The anchor is always kept
    // at full weight, so moving regions show the anchor frame instead of a smear.
    float motionThresh_;
    std::vector<float> storedMotionDiff_;

    int holeCount_;
    int accumulatedFrames_;
    double meanDensity_;
    int motionRejectedSamples_;
};
