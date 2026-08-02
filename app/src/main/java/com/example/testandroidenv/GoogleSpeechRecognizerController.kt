package com.example.testandroidenv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

sealed interface SpeechRecognitionState {
    data object Ready : SpeechRecognitionState
    data object Listening : SpeechRecognitionState
    data object Recognizing : SpeechRecognitionState
    data class Result(val text: String) : SpeechRecognitionState
    data object NoSpeech : SpeechRecognitionState
    data object Unavailable : SpeechRecognitionState
    data object NetworkError : SpeechRecognitionState
    data object Cancelled : SpeechRecognitionState
    data class Error(val message: String) : SpeechRecognitionState
}

class GoogleSpeechRecognizerController(
    context: Context,
    private val onStateChanged: (SpeechRecognitionState) -> Unit
) : RecognitionListener {
    private val applicationContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var cancelRequested = false

    fun startListening() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            onStateChanged(SpeechRecognitionState.Unavailable)
            return
        }

        try {
            val speechRecognizer = recognizer ?: SpeechRecognizer
                .createSpeechRecognizer(applicationContext)
                .also {
                    it.setRecognitionListener(this)
                    recognizer = it
                }
            cancelRequested = false
            active = true
            onStateChanged(SpeechRecognitionState.Listening)
            speechRecognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, RECOGNITION_LOCALE)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, RECOGNITION_LOCALE)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }
            )
        } catch (error: RuntimeException) {
            active = false
            onStateChanged(
                SpeechRecognitionState.Error(
                    error.message ?: "Could not start speech recognition"
                )
            )
        }
    }

    fun stopListening() {
        if (!active) return
        onStateChanged(SpeechRecognitionState.Recognizing)
        recognizer?.stopListening()
    }

    fun cancel() {
        if (!active) return
        active = false
        cancelRequested = true
        recognizer?.cancel()
        onStateChanged(SpeechRecognitionState.Cancelled)
    }

    fun destroy() {
        active = false
        cancelRequested = true
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onStateChanged(SpeechRecognitionState.Listening)
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (active) onStateChanged(SpeechRecognitionState.Recognizing)
    }

    override fun onError(error: Int) {
        active = false
        if (cancelRequested) {
            cancelRequested = false
            return
        }
        onStateChanged(mapSpeechRecognitionError(error))
    }

    override fun onResults(results: Bundle?) {
        active = false
        val finalText = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        onStateChanged(
            if (finalText.isEmpty()) {
                SpeechRecognitionState.NoSpeech
            } else {
                SpeechRecognitionState.Result(finalText)
            }
        )
    }

    override fun onPartialResults(partialResults: Bundle?) {
        // Partial results are intentionally never forwarded to the Planner.
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val RECOGNITION_LOCALE = "ar-SA"
    }
}

internal fun mapSpeechRecognitionError(error: Int): SpeechRecognitionState {
    return when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            SpeechRecognitionState.NetworkError

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            SpeechRecognitionState.NoSpeech

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SpeechRecognitionState.Error("Recognition service is busy. Please retry.")

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechRecognitionState.Error("Microphone permission is required.")

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechRecognitionState.Error("Arabic speech recognition is unavailable.")

        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            SpeechRecognitionState.Error("Too many recognition requests. Please wait and retry.")

        else -> SpeechRecognitionState.Error("Speech recognition failed (error $error).")
    }
}
