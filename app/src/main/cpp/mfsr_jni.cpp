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
    JNIEnv* env, jobject, jlong engineAddr, jdouble medianMse, jdouble p90Mse,
    jdouble sceneSpread, jdouble medianBrightness) {

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
    JNIEnv* env, jobject, jlong engineAddr, jlong frameAddr, jint callIndex) {

    if (checkDeadSystem(env)) return;

    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    cv::Mat* frame = reinterpret_cast<cv::Mat*>(frameAddr);

    if (!engine || !frame || frame->empty()) {
        MFSR_LOGE("accumulateFrame: invalid engine or frame");
        return;
    }

    try {
        engine->accumulateFrame(*frame, callIndex);
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

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getHoleCount(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0;
    return engine->getHoleCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getAccumulatedFrames(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0;
    return engine->getAccumulatedFrames();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getMeanDensity(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0.0;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0.0;
    return engine->getMeanDensity();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getMotionThreshold(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0.0f;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0.0f;
    return engine->getMotionThreshold();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_suggestMaxScale(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 2;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 2;
    return engine->suggestMaxScale();
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_setRenderScale(
    JNIEnv* env, jobject, jlong engineAddr, jfloat scale) {

    if (checkDeadSystem(env)) return;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return;
    engine->setRenderScale(scale);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_setHighBitDepth(
    JNIEnv* env, jobject, jlong engineAddr, jboolean on) {

    if (checkDeadSystem(env)) return;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return;
    engine->setHighBitDepth(on == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getMotionRejectedSamples(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return 0;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return 0;
    return engine->getMotionRejectedSamples();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dan_timelapse_mfsr_MFSRNative_getProcessedCallCount(
    JNIEnv* env, jobject, jlong engineAddr) {

    if (checkDeadSystem(env)) return -1;
    auto* engine = reinterpret_cast<MFSREngineV2*>(engineAddr);
    if (!engine) return -1;
    return engine->processedCallCount();
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
    char buf[700];
    snprintf(buf, sizeof(buf),
        "frame=%d mse=%.2f orb_inliers=%d ecc_cc=%.4f shift=%.2fpx sharp=%.1f bright=%.1f "
        "gain=[%.3f,%.3f,%.3f] offset=[%.1f,%.1f,%.1f] motion=%.1f align_err=%.2fpx "
        "weight=%.3f accepted=%d reason='%s'",
        t.frameIndex, t.mseScore, t.orbMatchCount, t.eccCorrelation,
        t.shiftPx, t.sharpness, t.brightness,
        t.gain[0], t.gain[1], t.gain[2],
        t.offset[0], t.offset[1], t.offset[2],
        t.motionDiff, t.alignErrPx,
        t.accumulationWeight, t.accepted ? 1 : 0, t.rejectionReason.c_str());
    return env->NewStringUTF(buf);
}
