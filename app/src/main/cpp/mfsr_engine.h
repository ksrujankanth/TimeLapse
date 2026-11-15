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
    float shiftPx = 0;
    float sharpness = 0;
    float brightness = 0;
    float accumulationWeight = 0;
    bool accepted = false;
    int pixelsAccumulated = 0;
    int pixelsTotal = 0;
    std::string rejectionReason;
};

/**
 * MFSREngineV2 — State-of-the-art Multi-Frame Super-Resolution for Android.
 *
 * Pipeline:
 *   init()         → Set anchor, compute ORB keypoints once
 *   processFrame() → Align each frame to anchor via ORB + Pyramid ECC, store if good
 *   finalize()     → Two-pass tiled fusion:
 *                     Pass 1: warpAffine each frame to HR grid, accumulate sum + sum²
 *                     Compute per-pixel mean + stddev
 *                     Pass 2: warpAffine again, reject outliers via sigma clipping
 *                     Result: clean, sharp HR image
 *
 * Key design choices:
 *   - Affine (4 DOF), NOT homography — stable for handheld video
 *   - MOTION_EUCLIDEAN for ECC — robust, fast convergence
 *   - warpAffine with INTER_LANCZOS4 — OpenCV's best interpolation, SIMD-optimized
 *   - Two-pass sigma clipping — rejects motion/occlusion without deep buffers
 *   - No CLAHE on output — preserves natural tones
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
    void accumulateFrame(const cv::Mat& frame, int index);
    void finalize(cv::Mat& output);
    void enhanceOutputContrast(cv::Mat& bgrImage);

    int getStoredFrameCount() const { return (int)storedTransforms_.size(); }
    int getTelemetryCount() const { return (int)telemetry_.size(); }
    const MFSRFrameTelemetry& getTelemetry(int i) const { return telemetry_[i]; }

private:
    cv::Mat alignORBCoarse(const cv::Mat& currGray, int* outInlierCount);
    cv::Mat alignPyramidECC(const cv::Mat& currGray, const cv::Mat& initialWarp);
    bool processFrameInternal(const cv::Mat& frame, MFSRFrameTelemetry& t);

    // Build the 2x3 matrix that warps a source frame directly onto the HR tile grid.
    // This correctly composes: source → anchor (via stored affine) → HR (via scale).
    cv::Mat buildHRWarpMatrix(const cv::Mat& affine2x3, int tileX, int tileY);
    void applyCLAHE(cv::Mat& labImage);

    float scaleFactor_;
    int hrCols_, hrRows_;

    cv::Mat anchorRGB_;
    cv::Mat anchorGray_;

    std::vector<cv::KeyPoint> anchorKeypoints_;
    cv::Mat anchorDescriptors_;
    cv::Ptr<cv::ORB> orbDetector_;

    // Each stored frame has a single 2x3 affine: source → anchor at 1x resolution
    std::vector<cv::Mat> storedTransforms_;
    std::vector<float> storedWeights_;
    std::vector<MFSRFrameTelemetry> telemetry_;

    // Accumulation buffers
    cv::Mat accumSum_;    // HR-sized CV_32FC3 weighted sum
    cv::Mat accumCount_;  // HR-sized CV_32FC1 weight count

    float ransacThreshold_;
    float sigmaClipMultiplier_;
    bool claheEnabled_;
};
