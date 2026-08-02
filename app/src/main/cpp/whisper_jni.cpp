#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "WhisperJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

size_t assetRead(void * context, void * output, size_t readSize) {
    return AAsset_read(static_cast<AAsset *>(context), output, readSize);
}

bool assetEof(void * context) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(context)) <= 0;
}

void assetClose(void * context) {
    AAsset_close(static_cast<AAsset *>(context));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_testandroidenv_WhisperNative_initContextFromAsset(
        JNIEnv * env,
        jobject /* thiz */,
        jobject assetManager,
        jstring assetPath) {
    const char * path = env->GetStringUTFChars(assetPath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    AAssetManager * manager = AAssetManager_fromJava(env, assetManager);
    AAsset * asset = AAssetManager_open(manager, path, AASSET_MODE_STREAMING);
    env->ReleaseStringUTFChars(assetPath, path);
    if (asset == nullptr) {
        LOGE("Unable to open model asset");
        return 0;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = assetRead,
            .eof = assetEof,
            .close = assetClose,
    };
    const whisper_context * context = whisper_init_with_params(
            &loader,
            whisper_context_default_params());
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_testandroidenv_WhisperNative_freeContext(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong contextPointer) {
    if (contextPointer != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(contextPointer));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_testandroidenv_WhisperNative_transcribe(
        JNIEnv * env,
        jobject /* thiz */,
        jlong contextPointer,
        jint threadCount,
        jfloatArray audio) {
    if (contextPointer == 0 || audio == nullptr) {
        return -1;
    }

    jfloat * samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize sampleCount = env->GetArrayLength(audio);
    if (samples == nullptr) {
        return -1;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    // Fixed Arabic command recognition: greedy decoding does not use beam search.
    params.language = "ar";
    params.greedy.best_of = 1;
    params.n_threads = threadCount;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = true;

    whisper_reset_timings(reinterpret_cast<whisper_context *>(contextPointer));
    const int result = whisper_full(
            reinterpret_cast<whisper_context *>(contextPointer),
            params,
            samples,
            sampleCount);

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_testandroidenv_WhisperNative_getTextSegmentCount(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong contextPointer) {
    return contextPointer == 0
           ? 0
           : whisper_full_n_segments(reinterpret_cast<whisper_context *>(contextPointer));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_testandroidenv_WhisperNative_getTextSegment(
        JNIEnv * env,
        jobject /* thiz */,
        jlong contextPointer,
        jint index) {
    if (contextPointer == 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(whisper_full_get_segment_text(
            reinterpret_cast<whisper_context *>(contextPointer), index));
}
