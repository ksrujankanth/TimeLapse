#include <jni.h>
#include "mfsr_engine.h"

static bool checkDeadSystem(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        MFSR_LOGE("checkDeadSystem: clearing pending Java exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_createEngine(
    JNIEnv* env, jobject, jfloat scale, jlong anchorAddr) {

    if (checkDeadSystem(env)) return 0L;

    cv::Mat* anchor = reinterpret_cast<cv::Mat*>(anchorAddr);
    if (!anchor || anchor->empty()) {
        MFSR_LOGE("createEngine: invalid anchor frame");
        return 0L;
    }

    try {
        auto* engine = new MFSREngineV2();
        engine->init(*anchor, scale);
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& e) {
        MFSR_LOGE("createEngine: exception in init(): %s", e.what());
        return 0L;
    } catch (...) {
        MFSR_LOGE("createEngine: unknown exception in init()");
        return 0L;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_setSceneStats(
    JNIEnv* env, jobject, jlong engineAddr, jdouble medianMse, jdouble p90Mse, jdouble sceneSpread, jdouble medianBrightness) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return;
    engine->setSceneStats(medianMse, p90Mse, sceneSpread, medianBrightness);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_enhanceOutputContrast(
    JNIEnv* env, jobject, jlong engineAddr, jlong outputAddr) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* output = reinterpret_cast<cv::Mat*>(outputAddr);

    if (!engine || !output || output->empty()) {
        MFSR_LOGE("enhanceOutputContrast: invalid engine or output");
        return;
    }

    try {
        engine->enhanceOutputContrast(*output);
    } catch (const std::exception& e) {
        MFSR_LOGE("enhanceOutputContrast: exception: %s", e.what());
    } catch (...) {
        MFSR_LOGE("enhanceOutputContrast: unknown exception");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_processFrame(
    JNIEnv* env, jobject, jlong engineAddr, jlong frameAddr) {

    if (checkDeadSystem(env)) return JNI_FALSE;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* frame = reinterpret_cast<cv::Mat*>(frameAddr);

    if (!engine || !frame || frame->empty()) {
        MFSR_LOGE("processFrame: invalid engine or frame");
        return JNI_FALSE;
    }

    try {
        return engine->processFrame(*frame) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        MFSR_LOGE("processFrame: exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        MFSR_LOGE("processFrame: unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_initAccumulation(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return;
    try {
        engine->initAccumulation();
    } catch (const std::exception& e) {
        MFSR_LOGE("initAccumulation: exception: %s", e.what());
    } catch (...) {
        MFSR_LOGE("initAccumulation: unknown exception");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_accumulateFrame(
    JNIEnv* env, jobject, jlong engineAddr, jlong frameAddr, jint frameIndex) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* frame = reinterpret_cast<cv::Mat*>(frameAddr);

    if (!engine || !frame || frame->empty()) {
        MFSR_LOGE("accumulateFrame: invalid engine or frame");
        return;
    }

    try {
        engine->accumulateFrame(*frame, frameIndex);
    } catch (const std::exception& e) {
        MFSR_LOGE("accumulateFrame: exception: %s", e.what());
    } catch (...) {
        MFSR_LOGE("accumulateFrame: unknown exception");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_finalizeEngine(
    JNIEnv* env, jobject, jlong engineAddr, jlong outputAddr) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* output = reinterpret_cast<cv::Mat*>(outputAddr);

    if (!engine || !output) {
        MFSR_LOGE("finalizeEngine: invalid engine or output");
        return;
    }

    try {
        engine->finalize(*output);
    } catch (const std::exception& e) {
        MFSR_LOGE("finalizeEngine: exception: %s", e.what());
    } catch (...) {
        MFSR_LOGE("finalizeEngine: unknown exception");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_destroyEngine(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (engine) {
        delete engine;
        MFSR_LOGD("Engine destroyed");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getStoredFrameCount(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0;
    return engine->getStoredFrameCount();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_processFrameWithTelemetry(
    JNIEnv* env, jobject, jlong engineAddr, jlong frameAddr, jint frameIndex, jdouble preScanMse) {

    if (checkDeadSystem(env)) return JNI_FALSE;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* frame = reinterpret_cast<cv::Mat*>(frameAddr);

    if (!engine || !frame || frame->empty()) return JNI_FALSE;
    try {
        return engine->processFrameWithIndex(*frame, frameIndex, preScanMse) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        MFSR_LOGE("processFrameWithTelemetry: exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        MFSR_LOGE("processFrameWithTelemetry: unknown exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getTelemetryCount(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0;
    return engine->getTelemetryCount();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getTelemetryLine(
    JNIEnv* env, jobject, jlong engineAddr, jint index) {

    if (checkDeadSystem(env)) return env->NewStringUTF("");

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine || index < 0 || index >= engine->getTelemetryCount()) {
        return env->NewStringUTF("");
    }

    const auto& t = engine->getTelemetry(index);
    char buf[512];
    snprintf(buf, sizeof(buf),
        "frame=%d mse=%.2f orb_inliers=%d ecc_converged=%d shift=%.2fpx sharp=%.1f brightness=%.1f weight=%.3f accepted=%d pixels=%d/%d reason='%s'",
        t.frameIndex, t.mseScore, t.orbMatchCount,
        t.eccConverged ? 1 : 0, t.shiftPx, t.sharpness, t.brightness,
        t.accumulationWeight, t.accepted ? 1 : 0,
        t.pixelsAccumulated, t.pixelsTotal,
        t.rejectionReason.c_str());
    return env->NewStringUTF(buf);
}