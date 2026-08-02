package com.example.testandroidenv

import android.content.res.AssetManager
import android.os.Build
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val MODEL_ASSET_PATH = "models/ggml-small-q5_0.bin"
private const val INFERENCE_THREAD_COUNT = 4

/**
 * Serializes every interaction with the native context: whisper.cpp contexts must not be used
 * concurrently. The model is initialized once and retained for the activity lifetime.
 */
class WhisperEngine private constructor() {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WhisperNative").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var contextPointer = 0L

    suspend fun transcribe(samples: FloatArray): String = withContext(dispatcher) {
        check(contextPointer != 0L) { "Whisper model is not loaded" }
        require(samples.isNotEmpty()) { "No audio was recorded" }

        check(WhisperNative.transcribe(contextPointer, INFERENCE_THREAD_COUNT, samples) == 0) {
            "whisper.cpp could not transcribe the recording"
        }

        buildString {
            repeat(WhisperNative.getTextSegmentCount(contextPointer)) { index ->
                append(WhisperNative.getTextSegment(contextPointer, index))
            }
        }.trim()
    }

    suspend fun release() {
        withContext(dispatcher) {
            if (contextPointer != 0L) {
                WhisperNative.freeContext(contextPointer)
                contextPointer = 0L
            }
        }
        dispatcher.close()
    }

    companion object {
        suspend fun load(assetManager: AssetManager): WhisperEngine {
            val engine = WhisperEngine()
            withContext(engine.dispatcher) {
                engine.contextPointer = WhisperNative.initContextFromAsset(assetManager, MODEL_ASSET_PATH)
                check(engine.contextPointer != 0L) {
                    "Unable to load $MODEL_ASSET_PATH"
                }
            }
            return engine
        }
    }
}

/** JNI surface based on whisper.cpp's official Android implementation. */
private object WhisperNative {
    init {
        // The prototype packages only the arm64-v8a implementation.
        check(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "This prototype requires an arm64-v8a device"
        }
        System.loadLibrary("whisper_jni")
    }

    external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
    external fun freeContext(contextPointer: Long)
    external fun transcribe(
        contextPointer: Long,
        threadCount: Int,
        audio: FloatArray
    ): Int
    external fun getTextSegmentCount(contextPointer: Long): Int
    external fun getTextSegment(contextPointer: Long, index: Int): String
}
