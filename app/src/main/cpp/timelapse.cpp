#include <jni.h>
#include <vector>
#include "opencv2/core.hpp"
#include "opencv2/imgproc.hpp"
#include "opencv2/features2d.hpp"  // Required for cv::ORB
#include "opencv2/calib3d.hpp"     // Required for cv::estimateAffinePartial2D
#include "opencv2/video.hpp"       // Required for cv::findTransformECC
#include <android/log.h>

#include <algorithm>
#include <queue>
#include <cmath>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <string>
#include <cstdio>

#define LOG_TAG "NativeTimelapse"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using namespace cv;

// Helper function: Manual RGB to Grayscale (bypasses cvtColor ABI issue)
static void convertToGray(const cv::Mat& src, cv::Mat& dst) {
    dst.create(src.rows, src.cols, CV_8UC1);
    for (int y = 0; y < src.rows; ++y) {
        const cv::Vec3b* srcRow = src.ptr<cv::Vec3b>(y);
        uchar* dstRow = dst.ptr<uchar>(y);
        for (int x = 0; x < src.cols; ++x) {
            dstRow[x] = static_cast<uchar>(0.299f * srcRow[x][0] + 0.587f * srcRow[x][1] + 0.114f * srcRow[x][2]);
        }
    }
}

// 1. The Bare-Metal, Any-Channel C++ Engine
static void mergePixelsByLightness(const std::vector<cv::Mat>& images, cv::Mat& output, bool findDarkest) {
    if (images.empty()) return;

    // Ensure output is allocated
    if (output.empty() || output.size() != images[0].size() || output.type() != images[0].type()) {
        output.create(images[0].size(), images[0].type());
    }

    int channels = images[0].channels();
    int rows = images[0].rows;
    int cols = images[0].cols;
    
    // OpenCV Optimization: Treat continuous memory as one massive 1D row for speed
    if (images[0].isContinuous() && output.isContinuous()) {
        cols *= rows;
        rows = 1;
    }

    for (int r = 0; r < rows; r++) {
        uint8_t* outRow = output.ptr<uint8_t>(r);
        
        // Grab row pointers for all frames in the stack
        std::vector<const uint8_t*> inRows(images.size());
        for (size_t i = 0; i < images.size(); i++) {
            inRows[i] = images[i].ptr<const uint8_t>(r);
        }

        for (int c = 0; c < cols; c++) {
            int pxIdx = c * channels;
            
            // Calculate baseline luminance from the first frame in the stack
            int bestLum = 0;
            if (channels >= 3) {
                // Approximate Luminance for BGR/RGB
                bestLum = (int)(0.299 * inRows[0][pxIdx + 2] + 0.587 * inRows[0][pxIdx + 1] + 0.114 * inRows[0][pxIdx]);
            } else {
                // Grayscale/YUV fallback: use the first byte as brightness
                bestLum = inRows[0][pxIdx]; 
            }
            
            size_t bestImgIdx = 0;

            // Compare against all other frames in the buffer
            for (size_t i = 1; i < images.size(); i++) {
                int lum = 0;
                if (channels >= 3) {
                    lum = (int)(0.299 * inRows[i][pxIdx + 2] + 0.587 * inRows[i][pxIdx + 1] + 0.114 * inRows[i][pxIdx]);
                } else {
                    lum = inRows[i][pxIdx];
                }

                if (findDarkest) {
                    if (lum < bestLum) {
                        bestLum = lum;
                        bestImgIdx = i;
                    }
                } else {
                    if (lum > bestLum) {
                        bestLum = lum;
                        bestImgIdx = i;
                    }
                }
            }

            // Copy the winning pixel's bytes to the output matrix
            for (int ch = 0; ch < channels; ch++) {
                outRow[pxIdx + ch] = inRows[bestImgIdx][pxIdx + ch];
            }
        }
    }
}

// Updated JNI Wrappers passing the boolean flag
extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_mergeLightestPixelsNative(
    JNIEnv *env, jobject thiz, jlongArray images_array, jlong output_addr) {

    LOGD("mergeLightestPixelsNative called, len=%d", env->GetArrayLength(images_array));

    jlong* addresses = env->GetLongArrayElements(images_array, nullptr);
    jsize len = env->GetArrayLength(images_array);

    std::vector<cv::Mat> images;
    images.reserve(len);
    for (int i = 0; i < len; i++) {
        images.push_back(*(cv::Mat*)addresses[i]);
    }

    cv::Mat& output = *(cv::Mat*)output_addr;
    LOGD("Output addr: %lld, empty=%d", (long long)output_addr, output.empty());
    
    mergePixelsByLightness(images, output, false); // false = Lightest

    LOGD("mergeLightestPixelsNative done, output empty=%d", output.empty());

    env->ReleaseLongArrayElements(images_array, addresses, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_mergeDarkestPixelsNative(
    JNIEnv *env, jobject thiz, jlongArray images_array, jlong output_addr) {

    jlong* addresses = env->GetLongArrayElements(images_array, nullptr);
    jsize len = env->GetArrayLength(images_array);

    std::vector<cv::Mat> images;
    images.reserve(len);
    for (int i = 0; i < len; i++) {
        images.push_back(*(cv::Mat*)addresses[i]);
    }

    cv::Mat& output = *(cv::Mat*)output_addr;
    mergePixelsByLightness(images, output, true);  // true = Darkest

    env->ReleaseLongArrayElements(images_array, addresses, JNI_ABORT);
}

// Median filter native functions
extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_calculateMedianFrameNative(JNIEnv* env, jobject, jlongArray sourceFramesAddrs_jarray, jlong outputFrameAddr) {
    if (sourceFramesAddrs_jarray == nullptr || outputFrameAddr == 0) return;
    cv::Mat& outputFrame = *(cv::Mat*)outputFrameAddr;
    jlong* sourceFramesAddrs = env->GetLongArrayElements(sourceFramesAddrs_jarray, nullptr);
    jsize frameCount = env->GetArrayLength(sourceFramesAddrs_jarray);
    if (frameCount == 0) {
        env->ReleaseLongArrayElements(sourceFramesAddrs_jarray, sourceFramesAddrs, JNI_ABORT);
        return;
    }
    std::vector<cv::Mat> sourceFrames;
    for (jsize i = 0; i < frameCount; ++i) sourceFrames.push_back(*(cv::Mat*)sourceFramesAddrs[i]);
    env->ReleaseLongArrayElements(sourceFramesAddrs_jarray, sourceFramesAddrs, 0);

    if (outputFrame.empty() || outputFrame.size() != sourceFrames[0].size() || outputFrame.type() != sourceFrames[0].type()) {
        outputFrame.create(sourceFrames[0].size(), sourceFrames[0].type());
    }

    size_t median_index = frameCount / 2;
    outputFrame.forEach<Vec3b>([&sourceFrames, frameCount, median_index](Vec3b& pixel, const int* position) -> void {
        std::vector<uint8_t> channel_values(frameCount);
        for (int ch = 0; ch < 3; ++ch) {
            for (int i = 0; i < frameCount; ++i) channel_values[i] = sourceFrames[i].at<Vec3b>(position[0], position[1])[ch];
            std::nth_element(channel_values.begin(), channel_values.begin() + median_index, channel_values.end());
            uint8_t m = channel_values[median_index];
            if (frameCount % 2 == 0) {
                std::nth_element(channel_values.begin(), channel_values.begin() + median_index - 1, channel_values.end());
                m = (uint8_t)((static_cast<int>(channel_values[median_index - 1]) + static_cast<int>(m)) / 2);
            }
            pixel[ch] = m;
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_accumulateMedianNative(JNIEnv* env, jobject, jlong medianAddr, jlong frameAddr) {
    cv::Mat& median = *(cv::Mat*)medianAddr;
    cv::Mat& frame = *(cv::Mat*)frameAddr;
    if (median.empty() || frame.empty() || median.size() != frame.size() || median.type() != frame.type()) return;

    median.forEach<Vec3b>([&frame](Vec3b& m_pixel, const int* pos) -> void {
        const Vec3b& f_pixel = frame.at<Vec3b>(pos[0], pos[1]);
        for (int i = 0; i < 3; ++i) {
            if (f_pixel[i] > m_pixel[i]) m_pixel[i]++;
            else if (f_pixel[i] < m_pixel[i]) m_pixel[i]--;
        }
    });
}

// Helper for running median to satisfy N log N complexity
struct RunningMedian {
    std::priority_queue<uint8_t> left;
    std::priority_queue<uint8_t, std::vector<uint8_t>, std::greater<uint8_t>> right;

    void add(uint8_t val) {
        if (left.empty() || val <= left.top()) left.push(val);
        else right.push(val);

        if (left.size() > right.size() + 1) {
            right.push(left.top());
            left.pop();
        } else if (right.size() > left.size()) {
            left.push(right.top());
            right.pop();
        }
    }

    uint8_t get() {
        if (left.size() > right.size()) return left.top();
        if (right.size() > left.size()) return right.top();
        return (uint8_t)((static_cast<int>(left.top()) + static_cast<int>(right.top())) / 2);
    }
};

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_processEndlessMedianStripNative(
    JNIEnv* env, jobject,
    jobject inputBuffer, jobject outputBuffer,
    jint width, jint stripHeight, jint frameCount) {

    uint8_t* input = (uint8_t*)env->GetDirectBufferAddress(inputBuffer);
    uint8_t* output = (uint8_t*)env->GetDirectBufferAddress(outputBuffer);

    if (input == nullptr || output == nullptr) return;

    size_t pixelsInStripFrame = (size_t)width * stripHeight * 3;

    for (size_t p = 0; p < pixelsInStripFrame; ++p) {
        RunningMedian rm;
        for (int f = 0; f < frameCount; ++f) {
            rm.add(input[(size_t)f * pixelsInStripFrame + p]);
            output[(size_t)f * pixelsInStripFrame + p] = rm.get();
        }
    }
}

// 1. POLYFILL: Catch old Java Wrapper GC calls and prevent UnsatisfiedLinkError crashes
extern "C" JNIEXPORT void JNICALL
Java_org_opencv_core_Mat_n_1delete(JNIEnv* env, jclass, jlong nativeObj) {
    cv::Mat* mat = reinterpret_cast<cv::Mat*>(nativeObj);
    if (mat != nullptr) {
        delete mat;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_opencv_core_Mat_n_1delete__J(JNIEnv* env, jclass, jlong nativeObj) {
    cv::Mat* mat = reinterpret_cast<cv::Mat*>(nativeObj);
    if (mat != nullptr) {
        delete mat;
    }
}

// 2. SAFE BYPASS: Manual RGBA to RGB conversion (no imgproc dependency)
extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_dropAlphaChannelNative(JNIEnv *env, jobject, jlong rgbaAddr, jlong rgbAddr) {
    cv::Mat* rgba = reinterpret_cast<cv::Mat*>(rgbaAddr);
    cv::Mat* rgb = reinterpret_cast<cv::Mat*>(rgbAddr);
    
    if (rgba == nullptr || rgb == nullptr || rgba->empty()) return;
    
    // Create output with 3 channels
    rgb->create(rgba->rows, rgba->cols, CV_8UC3);
    
    // Manual RGBA to RGB conversion (copy R, G, B, skip A)
    int rows = rgba->rows;
    int cols = rgba->cols;
    
    if (rgba->isContinuous() && rgb->isContinuous()) {
        // Fast path for continuous memory
        const uint8_t* src = rgba->ptr<uint8_t>();
        uint8_t* dst = rgb->ptr<uint8_t>();
        size_t srcStep = rgba->step[0];
        size_t dstStep = rgb->step[0];
        
        for (int i = 0; i < rows * cols; i++) {
            dst[i * 3 + 0] = src[i * 4 + 0]; // R
            dst[i * 3 + 1] = src[i * 4 + 1]; // G
            dst[i * 3 + 2] = src[i * 4 + 2]; // B
        }
    } else {
        // Slow path for non-continuous memory
        for (int y = 0; y < rows; y++) {
            const uint8_t* srcRow = rgba->ptr<uint8_t>(y);
            uint8_t* dstRow = rgb->ptr<uint8_t>(y);
            for (int x = 0; x < cols; x++) {
                dstRow[x * 3 + 0] = srcRow[x * 4 + 0]; // R
                dstRow[x * 3 + 1] = srcRow[x * 4 + 1]; // G
                dstRow[x * 3 + 2] = srcRow[x * 4 + 2]; // B
            }
        }
    }
}

// ============================================================================
// MFSREngine - Stateful Multi-Frame Super-Resolution Engine
// Hybrid Pipeline: Attempts ECC (Sub-pixel Affine), Falls back to ORB on failure
// Tiled Processing: Accumulates frames in RAM, processes tiles in finalize()
// ============================================================================

class MFSREngine {
public:
    cv::Mat anchorFrameGray;
    cv::Mat anchorFrameFloat;
    cv::Mat anchorFrame;
    cv::Mat anchorAlignGray;  // Task 1: CLAHE alignment copy
    cv::Mat previousFrameGray;
    cv::Mat cumulativeTransform;
    cv::Mat hanningWindow;
    cv::Mat lastValidTransform; // For trajectory tracking
    
    // Tiled Processing: Store frames and transforms instead of accumulating to huge canvas
    std::vector<cv::Mat> storedFrames;
    std::vector<cv::Mat> storedTransforms;
    cv::Mat anchorStored;
    cv::Mat anchorTransformStored;
    
    int hrCols;
    int hrRows;
    float scaleFactor;
    float dynamicSigma; // Task 3: Dynamic noise tolerance
    bool useOrbFallback;
    
    // Directive 1: JNI Signature Sync - Store boolean flags
    bool isGrayscale;
    bool isHdr;
    
    // Cache directory path for temp files
    std::string cacheDirPath;
    
    // ORB detector for fallback path
    cv::Ptr<cv::ORB> orbDetector;
    
    MFSREngine() : scaleFactor(1.0f), dynamicSigma(30.0f), useOrbFallback(false), 
        isGrayscale(false), isHdr(false), hrCols(0), hrRows(0) {
        cumulativeTransform = Mat::eye(3, 3, CV_32F);
        anchorTransformStored = Mat::eye(3, 3, CV_32F);
        orbDetector = cv::ORB::create(500);
    }
    
    ~MFSREngine() {
        cleanup();
    }
    
    void cleanup() {
        storedFrames.clear();
        storedTransforms.clear();
        anchorStored.release();
        anchorTransformStored.release();
    }
    
    void init(const cv::Mat& anchorFrameInput, float scale, bool forceOrbFallback = false, const std::string& cacheDir = "", bool grayscale = false, bool hdr = false) {
        scaleFactor = scale;
        useOrbFallback = forceOrbFallback;
        isGrayscale = grayscale;
        isHdr = hdr;
        cacheDirPath = cacheDir;

        LOGI("MFSR Engine init: isGrayscale=%d, isHdr=%d", isGrayscale, isHdr);
        
        // Handle Grayscale input - convert to BGR for consistent processing
        if (anchorFrameInput.channels() == 1) {
            cv::cvtColor(anchorFrameInput, anchorFrameGray, cv::COLOR_GRAY2BGR);
            isGrayscale = true;
        } else {
            anchorFrameGray = anchorFrameInput.clone();
        }
        
        // Convert to grayscale for alignment
        cv::cvtColor(anchorFrameGray, anchorFrameGray, cv::COLOR_BGR2GRAY);
        
        // Task 1: Create CLAHE alignment copy for better low-light feature detection
        cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
        clahe->apply(anchorFrameGray, anchorAlignGray);
        
        // Task 3: Calculate dynamic noise tolerance
        cv::Scalar meanBrightness = cv::mean(anchorFrameGray);
        float baseBrightness = static_cast<float>(meanBrightness[0]);
        dynamicSigma = (baseBrightness < 80.0f) ? 45.0f : 30.0f;
        LOGD("MFSR: Base brightness %.1f, dynamicSigma %.1f", baseBrightness, dynamicSigma);
        
        // Create float version for phase correlation and ECC
        anchorFrameGray.convertTo(anchorFrameFloat, CV_32F);
        
        // Create Hanning window once for performance
        cv::createHanningWindow(hanningWindow, anchorFrameGray.size(), CV_32F);
        
        previousFrameGray = anchorFrameGray.clone();
        
        // Store the original anchor frame for temporal robustness comparison
        this->anchorFrame = anchorFrameGray.clone();

        cumulativeTransform = Mat::eye(3, 3, CV_32F);

        // Tiled Processing: Store dimensions for finalization
        hrCols = static_cast<int>(anchorFrameInput.cols * scale);
        hrRows = static_cast<int>(anchorFrameInput.rows * scale);
        
        LOGI("MFSR: Tiled mode init - Output size: %dx%d", hrCols, hrRows);
        
        // Clear previous stored frames
        storedFrames.clear();
        storedTransforms.clear();
        
        // Store the anchor frame and identity transform for tiled processing
        cv::Mat anchorBgr;
        if (anchorFrameInput.channels() == 1) {
            cv::cvtColor(anchorFrameInput, anchorBgr, cv::COLOR_GRAY2BGR);
        } else {
            anchorBgr = anchorFrameInput.clone();
        }
        anchorStored = anchorBgr.clone();
        anchorTransformStored = Mat::eye(3, 3, CV_32F);
        
        // Note: We DON'T splat here - we store frames and process in finalize()
        // This avoids the massive memory allocation
    }

    void resetToAnchor() {
        previousFrameGray = anchorFrameGray.clone();
        cumulativeTransform = Mat::eye(3, 3, CV_32F);
    }

    bool processFrame(const cv::Mat& currentFrame) {
        // Directive 2: Handle Grayscale input - don't convert if already grayscale
        cv::Mat currGray;
        if (currentFrame.channels() == 1) {
            currGray = currentFrame.clone(); // Already grayscale
        } else {
            cv::cvtColor(currentFrame, currGray, cv::COLOR_BGR2GRAY);
        }
        
        // Defensive: Log if cvtColor failed
        if (currGray.empty()) {
            LOGE("CRASH AVERTED: currGray is empty after cvtColor!");
            return false;
        }
        
        // Directive 3: Blur Detection - Skip heavily blurred frames (but don't reject ALL, just warn)
        cv::Mat laplacian, mean, stddev;
        cv::Laplacian(currGray, laplacian, CV_64F);
        cv::meanStdDev(laplacian, mean, stddev);
        double variance = stddev.at<double>(0) * stddev.at<double>(0);
        
        // Changed: Log but don't auto-skip - let Top-80 similarity decide
        if (variance < 30.0) {
            LOGW("MFSR: Low variance frame (variance: %.2f) - allowing through similarity filter", variance);
        }
        
        cv::Mat currFloat;
        currGray.convertTo(currFloat, CV_32F);
        
        // ============================================================
        // DEFENSIVE ALIGNMENT PIPELINE WITH HEAVY LOGGING
        // ============================================================
        
        // 1. Verify Grayscale State
        if (currGray.empty() || currGray.type() != CV_8UC1) {
            LOGE("CRASH AVERTED: currGray is empty or not CV_8UC1!");
            return false;
        }

        cv::Mat warpMatrix = cv::Mat::eye(2, 3, CV_32F);
        bool alignmentSuccess = false;

        // 2. Safely apply CLAHE
        LOGI("Attempting CLAHE...");
        cv::Mat currAlignGray;
        cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
        if (anchorAlignGray.empty()) {
            LOGE("CRASH AVERTED: anchorAlignGray is empty! Did you apply CLAHE in init()?");
            return false;
        }
        clahe->apply(currGray, currAlignGray);
        LOGI("CLAHE Successful.");

        // 3. Coarse Alignment (ORB)
        LOGI("Attempting ORB Coarse Alignment...");
        if (orbDetector.empty()) {
            orbDetector = cv::ORB::create(500); 
        }

        std::vector<cv::KeyPoint> kp1, kp2;
        cv::Mat des1, des2;
        
        // Directive 3: Alignment Safety Net - Wrap in try-catch
        try {
            orbDetector->detectAndCompute(currAlignGray, cv::noArray(), kp1, des1);
            orbDetector->detectAndCompute(anchorAlignGray, cv::noArray(), kp2, des2);
            LOGI("ORB detectAndCompute finished. KP1: %zu, KP2: %zu", kp1.size(), kp2.size());
        } catch (const cv::Exception& e) {
            LOGE("ORB exception caught: %s - Dropping frame gracefully", e.what());
            return false; // Directive 3: Drop gracefully, don't force alignment
        } catch (...) {
            LOGE("FATAL NATIVE EXCEPTION inside ORB detectAndCompute! - Dropping frame gracefully");
            return false;
        }

        if (!des1.empty() && !des2.empty() && des1.type() == des2.type()) {
            cv::BFMatcher matcher(cv::NORM_HAMMING, true);
            std::vector<cv::DMatch> matches;
            matcher.match(des1, des2, matches);
            LOGI("ORB Matches found: %zu", matches.size());

            if (matches.size() >= 10) {
                std::vector<cv::Point2f> ptsCurr, ptsAnchor;
                for (const auto& match : matches) {
                    ptsCurr.push_back(kp1[match.queryIdx].pt);
                    ptsAnchor.push_back(kp2[match.trainIdx].pt);
                }
                
                LOGI("Attempting estimateAffinePartial2D...");
                try {
                    cv::Mat orbWarp = cv::estimateAffinePartial2D(ptsCurr, ptsAnchor, cv::noArray(), cv::RANSAC);
                    if (!orbWarp.empty()) {
                        orbWarp.convertTo(warpMatrix, CV_32F);
                        alignmentSuccess = true;
                        LOGI("ORB Coarse Alignment SUCCESS.");
                    } else {
                        LOGE("estimateAffinePartial2D returned empty matrix - Dropping frame");
                    }
                } catch (const cv::Exception& e) {
                    LOGE("estimateAffinePartial2D exception: %s - Dropping frame", e.what());
                    return false;
                }
            }
        }

        // 4. Fine Alignment (ECC)
        if (alignmentSuccess && !useOrbFallback) {
            LOGI("Attempting ECC Fine Tuning...");
            try {
                cv::findTransformECC(
                    currAlignGray, 
                    anchorAlignGray, 
                    warpMatrix, 
                    cv::MOTION_AFFINE, 
                    cv::TermCriteria(cv::TermCriteria::COUNT + cv::TermCriteria::EPS, 50, 0.001)
                );
                LOGI("ECC Fine Tuning SUCCESS.");
            } catch (const cv::Exception& e) {
                // Directive 3: ECC failed to converge - DON'T force warp, just use ORB result
                LOGI("ECC Failed to converge (caught cv::Exception): %s - Using ORB result only", e.what());
                // alignmentSuccess remains true from ORB, continue with that
            } catch (...) {
                LOGE("FATAL NATIVE EXCEPTION inside ECC! - Using ORB result only");
            }
        }

        if (alignmentSuccess) {
            // --- TELEPORTATION FILTER ---
            LOGI("Checking teleportation limits...");

            // 1. Safety Check: If lastValidTransform is empty or invalid, initialize it safely
            if (lastValidTransform.empty() || lastValidTransform.type() != CV_32F || lastValidTransform.rows < 2) {
                LOGI("lastValidTransform was empty. Initializing to identity matrix.");
                lastValidTransform = cv::Mat::eye(2, 3, CV_32F);
            }

            // 2. Safely read the matrix values
            float dx = warpMatrix.at<float>(0, 2);
            float dy = warpMatrix.at<float>(1, 2);
            float lastDx = lastValidTransform.at<float>(0, 2);
            float lastDy = lastValidTransform.at<float>(1, 2);
            
            // 3. Calculate distance
            float jumpDistance = std::sqrt(std::pow(dx - lastDx, 2) + std::pow(dy - lastDy, 2));
            
            if (jumpDistance > 40.0f) {
                LOGI("MFSR: Frame skipped due to teleportation (jump: %f pixels) - Dropping gracefully", jumpDistance);
                return false; // Directive 3: Drop gracefully
            }
            
            // 4. Update the path and store frame for tiled processing
            lastValidTransform = warpMatrix.clone();
            LOGI("Frame passed gatekeepers. Storing for tiled processing...");
            
            // Convert 2x3 affine matrix to 3x3
            cumulativeTransform = Mat::eye(3, 3, CV_32F);
            cumulativeTransform.at<float>(0, 0) = warpMatrix.at<float>(0, 0);
            cumulativeTransform.at<float>(0, 1) = warpMatrix.at<float>(0, 1);
            cumulativeTransform.at<float>(0, 2) = warpMatrix.at<float>(0, 2);
            cumulativeTransform.at<float>(1, 0) = warpMatrix.at<float>(1, 0);
            cumulativeTransform.at<float>(1, 1) = warpMatrix.at<float>(1, 1);
            cumulativeTransform.at<float>(1, 2) = warpMatrix.at<float>(1, 2);
            
            // Store frame and transform for tiled processing (avoid massive RAM allocation)
            storedFrames.push_back(currentFrame.clone());
            storedTransforms.push_back(cumulativeTransform.clone());
            
            previousFrameGray = currGray.clone();
            return true;
        }
        
        LOGW("MFSR: Both ECC and ORB failed for this frame - Dropping gracefully");
        return false;
    }

    void finalize(cv::Mat& outputFrame) {
        // Tiled Processing: Process the output in 512x512 tiles to avoid memory crash
        const int TILE_SIZE = 512;
        outputFrame.create(hrRows, hrCols, CV_8UC3);
        outputFrame.setTo(Scalar::all(0));
        
        LOGI("MFSR: Starting tiled finalization with %d tiles (%dx%d)", 
             ((hrCols + TILE_SIZE - 1) / TILE_SIZE) * ((hrRows + TILE_SIZE - 1) / TILE_SIZE),
             hrCols, hrRows);
        
        // Process each tile
        for (int tileY = 0; tileY < hrRows; tileY += TILE_SIZE) {
            for (int tileX = 0; tileX < hrCols; tileX += TILE_SIZE) {
                // Calculate actual tile bounds (may be smaller at edges)
                int tileW = std::min(TILE_SIZE, hrCols - tileX);
                int tileH = std::min(TILE_SIZE, hrRows - tileY);
                
                // Allocate small tile accumulators (~4MB each for 512x512x3ch float)
                cv::Mat tileCanvas(tileH, tileW, CV_32FC3, Scalar(0,0,0));
                cv::Mat tileWeights(tileH, tileW, CV_32FC1, Scalar(0));
                
                // Process anchor frame for this tile
                processTile(anchorStored, anchorTransformStored, tileCanvas, tileWeights, tileX, tileY, tileW, tileH, true);
                
                // Process all stored frames for this tile
                for (size_t i = 0; i < storedFrames.size(); i++) {
                    processTile(storedFrames[i], storedTransforms[i], tileCanvas, tileWeights, tileX, tileY, tileW, tileH, false);
                }
                
                // Normalize and write tile to output
                for (int y = 0; y < tileH; y++) {
                    Vec3f* canvasRow = tileCanvas.ptr<Vec3f>(y);
                    float* weightRow = tileWeights.ptr<float>(y);
                    Vec3b* outRow = outputFrame.ptr<Vec3b>(tileY + y);
                    
                    for (int x = 0; x < tileW; x++) {
                        float w = weightRow[x];
                        if (w > 0.001f) {
                            Vec3f normalized = canvasRow[x] / w;
                            outRow[tileX + x] = Vec3b(
                                saturate_cast<uchar>(std::round(normalized[2])), // BGR for OpenCV
                                saturate_cast<uchar>(std::round(normalized[1])),
                                saturate_cast<uchar>(std::round(normalized[0]))
                            );
                        }
                    }
                }
                
                LOGD("MFSR: Tile (%d,%d) %dx%d processed", tileX, tileY, tileW, tileH);
            }
        }
        
        LOGI("MFSR: Tiled finalization complete");
    }
    
void processTile(const cv::Mat& frame, const cv::Mat& transform, 
                      cv::Mat& tileCanvas, cv::Mat& tileWeights,
                      int tileX, int tileY, int tileW, int tileH, bool isAnchor) {
        // Plan B: Two-Pass Sigma-Clipping with Inverse Mapping
        // Pass 1: Compute mean and stddev using temporary accumulators
        // Pass 2: Accumulate directly into pipeline buffers (tileCanvas/tileWeights) with sigma clipping
        // Fallback: Protect against zero-weight pixels

        const float SIGMA_MULTIPLIER = 1.5f;

        // Temporary accumulators for Pass 1
        cv::Mat sum = cv::Mat::zeros(tileH, tileW, CV_32FC3);
        cv::Mat sqSum = cv::Mat::zeros(tileH, tileW, CV_32FC3);
        cv::Mat count = cv::Mat::zeros(tileH, tileW, CV_32FC1);

        // Helper: Calculate true inverse mapping from high-res tile to low-res source
        auto getInvMatrix = [&](const cv::Mat& M) {
            cv::Mat M_inv = cv::Mat::zeros(2, 3, CV_32F);
            float s = scaleFactor;
            M_inv.at<float>(0,0) = M.at<float>(0,0) / s;
            M_inv.at<float>(0,1) = M.at<float>(0,1) / s;
            M_inv.at<float>(0,2) = (M.at<float>(0,0) * tileX + M.at<float>(0,1) * tileY) / s + M.at<float>(0,2);
            M_inv.at<float>(1,0) = M.at<float>(1,0) / s;
            M_inv.at<float>(1,1) = M.at<float>(1,1) / s;
            M_inv.at<float>(1,2) = (M.at<float>(1,0) * tileX + M.at<float>(1,1) * tileY) / s + M.at<float>(1,2);
            return M_inv;
        };

        // Pass 1: Accumulate sum, sqSum, count
        auto processFramePass1 = [&](const cv::Mat& f, const cv::Mat& M_inv, float weight) {
            cv::Mat floatFrame;
            f.convertTo(floatFrame, CV_32FC3);
            cv::Mat warpedTile;
            warpAffine(floatFrame, warpedTile, M_inv, cv::Size(tileW, tileH),
                       cv::INTER_LANCZOS4 | cv::WARP_INVERSE_MAP, 
                       cv::BORDER_CONSTANT, cv::Scalar(-1.0f, -1.0f, -1.0f));
            for (int y = 0; y < tileH; y++) {
                const float* warpPtr = warpedTile.ptr<float>(y);
                float* sumPtr = sum.ptr<float>(y);
                float* sqSumPtr = sqSum.ptr<float>(y);
                float* countPtr = count.ptr<float>(y);
                for (int x = 0; x < tileW; x++) {
                    int px = x * 3;
                    if (warpPtr[px] >= 0) {
                        sumPtr[px]   += warpPtr[px]   * weight;
                        sumPtr[px+1] += warpPtr[px+1] * weight;
                        sumPtr[px+2] += warpPtr[px+2] * weight;
                        sqSumPtr[px]   += warpPtr[px]   * warpPtr[px] * weight;
                        sqSumPtr[px+1] += warpPtr[px+1] * warpPtr[px+1] * weight;
                        sqSumPtr[px+2] += warpPtr[px+2] * warpPtr[px+2] * weight;
                        countPtr[x] += weight;
                    }
                }
            }
        };

        // Execute Pass 1
        cv::Mat M_identity = cv::Mat::eye(2, 3, CV_32F);
        processFramePass1(anchorStored, getInvMatrix(M_identity), 2.0f);
        for (size_t i = 0; i < storedFrames.size(); i++) {
            processFramePass1(storedFrames[i], getInvMatrix(storedTransforms[i]), 1.0f);
        }

        // Compute mean and stddev from Pass 1 accumulators
        cv::Mat mean(tileH, tileW, CV_32FC3, cv::Scalar(0,0,0));
        cv::Mat stddev(tileH, tileW, CV_32FC3, cv::Scalar(0,0,0));
        for (int y = 0; y < tileH; y++) {
            const float* sumPtr = sum.ptr<float>(y);
            const float* sqSumPtr = sqSum.ptr<float>(y);
            const float* countPtr = count.ptr<float>(y);
            float* meanPtr = mean.ptr<float>(y);
            float* stdPtr = stddev.ptr<float>(y);
            for (int x = 0; x < tileW; x++) {
                float c = countPtr[x];
                if (c > 0) {
                    int px = x * 3;
                    for (int ch = 0; ch < 3; ch++) {
                        meanPtr[px+ch] = sumPtr[px+ch] / c;
                        float variance = (sqSumPtr[px+ch] / c) - (meanPtr[px+ch] * meanPtr[px+ch]);
                        stdPtr[px+ch] = std::sqrt(std::max(0.0f, variance));
                    }
                }
            }
        }

        // --- PASS 2: Filter Outliers & Accumulate DIRECTLY into Pipeline Buffers ---
        // Accumulates directly into tileCanvas (sums) and tileWeights (counts)
        auto processFramePass2 = [&](const cv::Mat& f, const cv::Mat& M_inv, float weight) {
            cv::Mat floatFrame;
            f.convertTo(floatFrame, CV_32FC3);
            cv::Mat warpedTile;
            warpAffine(floatFrame, warpedTile, M_inv, cv::Size(tileW, tileH),
                       cv::INTER_LANCZOS4 | cv::WARP_INVERSE_MAP, 
                       cv::BORDER_CONSTANT, cv::Scalar(-1.0f, -1.0f, -1.0f));
            for (int y = 0; y < tileH; y++) {
                const float* warpPtr = warpedTile.ptr<float>(y);
                const float* meanPtr = mean.ptr<float>(y);
                const float* stdPtr = stddev.ptr<float>(y);
                
                // Write DIRECTLY to the pipeline buffers expected by finalization
                float* canvasPtr = tileCanvas.ptr<float>(y);
                float* weightsPtr = tileWeights.ptr<float>(y);

                for (int x = 0; x < tileW; x++) {
                    int px = x * 3;
                    if (warpPtr[px] >= 0) {
                        // Check if all 3 channels are within the Sigma threshold
                        bool isGood = true;
                        for (int c_idx = 0; c_idx < 3; c_idx++) {
                            if (std::abs(warpPtr[px+c_idx] - meanPtr[px+c_idx]) > (SIGMA_MULTIPLIER * stdPtr[px+c_idx])) {
                                isGood = false;
                                break;
                            }
                        }
                        
                        if (isGood) {
                            canvasPtr[px] += warpPtr[px] * weight;
                            canvasPtr[px+1] += warpPtr[px+1] * weight;
                            canvasPtr[px+2] += warpPtr[px+2] * weight;
                            weightsPtr[x] += weight;
                        }
                    }
                }
            }
        };

        // Execute Pass 2
        processFramePass2(anchorStored, getInvMatrix(M_identity), 2.0f);
        for (size_t i = 0; i < storedFrames.size(); i++) {
            processFramePass2(storedFrames[i], getInvMatrix(storedTransforms[i]), 1.0f);
        }

        // --- THE FALLBACK FIX: Protect downstream division ---
        // If a pixel was completely rejected (weight == 0), inject the Pass 1 mean so it isn't black
        for (int y = 0; y < tileH; y++) {
            float* canvasPtr = tileCanvas.ptr<float>(y);
            float* weightsPtr = tileWeights.ptr<float>(y);
            const float* meanPtr = mean.ptr<float>(y);

            for (int x = 0; x < tileW; x++) {
                if (weightsPtr[x] <= 0.0f) {
                    int px = x * 3;
                    canvasPtr[px] = meanPtr[px];
                    canvasPtr[px+1] = meanPtr[px+1];
                    canvasPtr[px+2] = meanPtr[px+2];
                    weightsPtr[x] = 1.0f; // Set weight to 1.0 so downstream division yields the exact mean
                }
            }
        }
    }
};

// Legacy splatFrame - no longer used, tiled processing handles all warping in finalize()

// Global engine instance pointer
static MFSREngine* g_mfsrEngine = nullptr;

extern "C" JNIEXPORT jlong JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_createMFSREngineNative(JNIEnv* env, jobject, jfloat scale, jlong anchorAddr) {
    cv::Mat* anchor = reinterpret_cast<cv::Mat*>(anchorAddr);
    if (!anchor || anchor->empty()) {
        LOGE("MFSREngine: Invalid anchor frame");
        return 0L;
    }
    MFSREngine* engine = new MFSREngine();
    engine->init(*anchor, scale, false); // Default: use ECC first
    LOGD("MFSREngine created with scale %f (ECC mode)", scale);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_createMFSREngineWithTierNative(JNIEnv* env, jobject, jfloat scale, jlong anchorAddr, jboolean useOrbFallback, jstring cacheDirPath) {
    cv::Mat* anchor = reinterpret_cast<cv::Mat*>(anchorAddr);
    if (!anchor || anchor->empty()) {
        LOGE("MFSREngine: Invalid anchor frame");
        return 0L;
    }
    
    std::string cacheDir = "";
    if (cacheDirPath != nullptr) {
        const char* cacheDirChars = env->GetStringUTFChars(cacheDirPath, nullptr);
        if (cacheDirChars != nullptr) {
            cacheDir = std::string(cacheDirChars);
            env->ReleaseStringUTFChars(cacheDirPath, cacheDirChars);
        }
    }
    
    MFSREngine* engine = new MFSREngine();
    engine->init(*anchor, scale, useOrbFallback == JNI_TRUE, cacheDir);
    LOGD("MFSREngine created with scale %f (ORB fallback: %d) cacheDir: %s", scale, useOrbFallback == JNI_TRUE, cacheDir.c_str());
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_resetMFSREngineAnchorNative(JNIEnv* env, jobject, jlong engineAddr) {
    MFSREngine* engine = reinterpret_cast<MFSREngine*>(engineAddr);
    if (engine == nullptr) {
        LOGE("MFSREngine: Invalid engine pointer");
        return;
    }
    engine->resetToAnchor();
    LOGD("MFSREngine reset to anchor");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_processMFSRFrameNative(JNIEnv* env, jobject, jlong engineAddr, jlong frameAddr) {
    MFSREngine* engine = reinterpret_cast<MFSREngine*>(engineAddr);
    cv::Mat* frame = reinterpret_cast<cv::Mat*>(frameAddr);

    if (engine == nullptr || frame == nullptr || frame->empty()) {
        LOGE("MFSREngine: Invalid engine or frame");
        return JNI_FALSE;
    }

    bool result = engine->processFrame(*frame);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_finalizeMFSREngineNative(JNIEnv* env, jobject, jlong engineAddr, jlong outputFrameAddr) {
    MFSREngine* engine = reinterpret_cast<MFSREngine*>(engineAddr);
    cv::Mat* outputFrame = reinterpret_cast<cv::Mat*>(outputFrameAddr);

    if (engine == nullptr || outputFrame == nullptr) {
        LOGE("MFSREngine: Invalid engine or output frame");
        return;
    }

    engine->finalize(*outputFrame);
    LOGD("MFSREngine finalized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_destroyMFSREngineNative(JNIEnv* env, jobject, jlong engineAddr) {
    MFSREngine* engine = reinterpret_cast<MFSREngine*>(engineAddr);
    if (engine != nullptr) {
        delete engine;
        LOGD("MFSREngine destroyed");
    }
    if (engineAddr == reinterpret_cast<jlong>(g_mfsrEngine)) {
        g_mfsrEngine = nullptr;
    }
}

// Directive 1: New JNI signature with isGrayscale and isHdr flags
extern "C" JNIEXPORT jlong JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_createMFSREngineWithFlagsNative(JNIEnv* env, jobject, jfloat scale, jlong anchorAddr, jboolean useOrbFallback, jstring cacheDirPath, jboolean isGrayscale, jboolean isHdr) {
    cv::Mat* anchor = reinterpret_cast<cv::Mat*>(anchorAddr);
    if (!anchor || anchor->empty()) {
        LOGE("MFSREngine: Invalid anchor frame");
        return 0L;
    }
    
    std::string cacheDir = "";
    if (cacheDirPath != nullptr) {
        const char* cacheDirChars = env->GetStringUTFChars(cacheDirPath, nullptr);
        if (cacheDirChars != nullptr) {
            cacheDir = std::string(cacheDirChars);
            env->ReleaseStringUTFChars(cacheDirPath, cacheDirChars);
        }
    }
    
    MFSREngine* engine = new MFSREngine();
    engine->init(*anchor, scale, useOrbFallback == JNI_TRUE, cacheDir, isGrayscale == JNI_TRUE, isHdr == JNI_TRUE);
    LOGD("MFSREngine created with scale %f, ORB: %d, Grayscale: %d, HDR: %d, cacheDir: %s", 
         scale, useOrbFallback == JNI_TRUE, isGrayscale == JNI_TRUE, isHdr == JNI_TRUE, cacheDir.c_str());
    return reinterpret_cast<jlong>(engine);
}
