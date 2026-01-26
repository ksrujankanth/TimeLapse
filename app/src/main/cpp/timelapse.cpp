#include <jni.h>
#include <vector>
#include "opencv2/core.hpp"
#include "opencv2/imgproc.hpp"
#include <android/log.h>
#include <algorithm>
#include <queue>

#define LOG_TAG "NativeTimelapse"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using namespace cv;

inline static int calculateLuminance(const Point3_<uint8_t>* pixel) {
    return (int)(0.299 * (double)pixel->x + 0.587 * (double)pixel->y + 0.114 * (double)pixel->z);
}

static
void Mat_to_vector_Mat(Mat &mat, std::vector<Mat> &v_mat) {
    v_mat.clear();
    if (mat.type() == CV_32SC2 && mat.cols == 1) {
        v_mat.reserve(mat.rows);
        for (int i = 0; i < mat.rows; i++) {
            Vec<int, 2> a = mat.at<Vec<int, 2> >(i, 0);
            long long addr = (((long long) a[0]) << 32) | (a[1] & 0xffffffff);
            Mat &m = *((Mat *) addr);
            v_mat.push_back(m);
        }
    }
}

static
void mergePixelsByLightness(jlong images_nativeObj, jlong output_nativeObj, int luminance_alpha) {
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    std::vector<Mat> images;
    Mat_to_vector_Mat(imagesAsMat, images);

    if (images.empty()) return;

    const size_t size = images.size();
    Mat &output = *((Mat *) output_nativeObj);

    if (output.empty() || output.size() != images[0].size() || output.type() != images[0].type()) {
        output.create(images[0].size(), images[0].type());
    }

    output.forEach<Point3_<uint8_t>>(
            [images, size, luminance_alpha](Point3_<uint8_t>& pixel, const int *position) -> void {
                const Point3_<uint8_t> *srcPixel = nullptr;
                int luminance;
                auto *bestSrcPixel = (const Point3_<uint8_t>*)images[0].ptr(position);
                int bestComparativeLuminance = luminance_alpha * calculateLuminance(bestSrcPixel);

                for(size_t imageIndex = 1; imageIndex < size; imageIndex++) {
                    srcPixel = (const Point3_<uint8_t>*)images[imageIndex].ptr(position);
                    luminance = luminance_alpha * calculateLuminance(srcPixel);
                    if (luminance > bestComparativeLuminance) {
                        bestComparativeLuminance = luminance;
                        bestSrcPixel = srcPixel;
                    }
                }
                pixel = *bestSrcPixel;
            }
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_mergeLightestPixelsNative(JNIEnv *env, jobject thiz, jlong images_nativeObj, jlong output_nativeObj) {
    mergePixelsByLightness(images_nativeObj, output_nativeObj, 1);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_mergeDarkestPixelsNative(JNIEnv *env, jobject thiz, jlong images_nativeObj, jlong output_nativeObj) {
    mergePixelsByLightness(images_nativeObj, output_nativeObj, -1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_generateMotionExtractionFrameNative(JNIEnv* env, jobject, jlong currentFrameAddr, jlong previousFrameAddr, jlong outputFrameAddr) {
    cv::Mat& currentFrame = *(cv::Mat*)currentFrameAddr;
    cv::Mat& previousFrame = *(cv::Mat*)previousFrameAddr;
    cv::Mat& outputFrame = *(cv::Mat*)outputFrameAddr;

    if (outputFrame.empty() || outputFrame.size() != currentFrame.size() || outputFrame.type() != currentFrame.type()) {
        outputFrame.create(currentFrame.size(), currentFrame.type());
    }

    if (currentFrame.empty() || previousFrame.empty()) {
        outputFrame = cv::Scalar(127, 127, 127);
        return;
    }

    cv::Mat diffFrame_16S;
    cv::Mat currentFrame_16S, previousFrame_16S;
    currentFrame.convertTo(currentFrame_16S, CV_16SC3);
    previousFrame.convertTo(previousFrame_16S, CV_16SC3);
    cv::subtract(currentFrame_16S, previousFrame_16S, diffFrame_16S);
    diffFrame_16S.convertTo(outputFrame, CV_8UC3, 0.5, 127.5);
}

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

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_images_ImageTools_00024Companion_updateMedianHistogramNative(JNIEnv* env, jobject, jlong histogramAddr, jlong frameAddr, jlong outputAddr, jint frameCount) {
    // This function is no longer used but kept for binary compatibility if needed,
    // or we can remove it if we update all call sites.
}
