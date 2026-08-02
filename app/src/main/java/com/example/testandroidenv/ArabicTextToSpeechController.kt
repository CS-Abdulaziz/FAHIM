package com.example.testandroidenv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed interface ArabicTtsState {
    data object Initializing : ArabicTtsState
    data object Ready : ArabicTtsState
    data object Speaking : ArabicTtsState
    data object ArabicUnavailable : ArabicTtsState
    data class Error(val message: String) : ArabicTtsState
}

class ArabicTextToSpeechController(
    context: Context,
    private val onStateChanged: (ArabicTtsState) -> Unit,
    private val onUtteranceStarted: (SpokenUtterance) -> Unit = {},
    private val onUtteranceCompleted: (SpokenUtterance) -> Unit = {}
) : SpokenFeedbackOutput {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicLong()
    private var engine: TextToSpeech? = null
    private var state: ArabicTtsState = ArabicTtsState.Initializing
    private val requestLock = Any()
    private var pendingUtterance: SpokenUtterance? = null
    private var activeUtterance: SpokenUtterance? = null

    init {
        emit(ArabicTtsState.Initializing)
        engine = TextToSpeech(context.applicationContext, ::onInitialized)
    }

    fun speak(reason: String) {
        speak(
            SpokenUtterance(
                goalId = "manual",
                kind = SpokenFeedbackKind.INTERACTION,
                text = reason,
                utteranceId = "manual-${utteranceCounter.incrementAndGet()}"
            )
        )
    }

    override fun speak(utterance: SpokenUtterance): Boolean {
        if (utterance.text.isBlank()) return false
        when (state) {
            ArabicTtsState.Initializing -> {
                synchronized(requestLock) {
                    if (
                        TtsPriorityPolicy.shouldAccept(
                            pendingUtterance?.kind,
                            utterance.kind
                        )
                    ) {
                        pendingUtterance = utterance
                    } else {
                        return false
                    }
                }
                return true
            }
            ArabicTtsState.ArabicUnavailable -> {
                emit(ArabicTtsState.ArabicUnavailable)
                return false
            }
            is ArabicTtsState.Error -> {
                emit(state)
                return false
            }
            ArabicTtsState.Ready,
            ArabicTtsState.Speaking -> {
                synchronized(requestLock) {
                    if (!TtsPriorityPolicy.shouldAccept(activeUtterance?.kind, utterance.kind)) {
                        return false
                    }
                    activeUtterance = utterance
                }
                val result = engine?.speak(
                    utterance.text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utterance.utteranceId
                ) ?: TextToSpeech.ERROR
                if (result == TextToSpeech.ERROR) {
                    synchronized(requestLock) {
                        if (activeUtterance?.utteranceId == utterance.utteranceId) {
                            activeUtterance = null
                        }
                    }
                    emit(ArabicTtsState.Error("Could not speak the Planner reason."))
                    return false
                }
                return true
            }
        }
    }

    fun stop() {
        stopAll()
    }

    fun hasPendingOrActiveSpeech(): Boolean = synchronized(requestLock) {
        pendingUtterance != null || activeUtterance != null
    }

    override fun cancelGoal(goalId: String) {
        val shouldStop = synchronized(requestLock) {
            if (pendingUtterance?.goalId == goalId) pendingUtterance = null
            if (activeUtterance?.goalId == goalId) {
                activeUtterance = null
                true
            } else {
                false
            }
        }
        if (shouldStop) {
            engine?.stop()
            if (state is ArabicTtsState.Speaking) emit(ArabicTtsState.Ready)
        }
    }

    override fun stopAll() {
        synchronized(requestLock) {
            pendingUtterance = null
            activeUtterance = null
        }
        engine?.stop()
        if (state is ArabicTtsState.Speaking) emit(ArabicTtsState.Ready)
    }

    fun shutdown() {
        synchronized(requestLock) {
            pendingUtterance = null
            activeUtterance = null
        }
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun onInitialized(status: Int) {
        val textToSpeech = engine
        if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
            emit(ArabicTtsState.Error("Text-to-speech initialization failed."))
            return
        }

        val locale = Locale.forLanguageTag("ar-SA")
        val availability = textToSpeech.isLanguageAvailable(locale)
        if (!isArabicTtsLanguageAvailable(availability)) {
            synchronized(requestLock) { pendingUtterance = null }
            emit(ArabicTtsState.ArabicUnavailable)
            return
        }
        if (textToSpeech.setLanguage(locale) == TextToSpeech.ERROR) {
            synchronized(requestLock) { pendingUtterance = null }
            emit(ArabicTtsState.ArabicUnavailable)
            return
        }

        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val utterance = synchronized(requestLock) {
                        activeUtterance?.takeIf { it.utteranceId == utteranceId }
                    } ?: return
                    emit(ArabicTtsState.Speaking)
                    mainHandler.post { onUtteranceStarted(utterance) }
                }

                override fun onDone(utteranceId: String?) {
                    val utterance = synchronized(requestLock) {
                        activeUtterance?.takeIf { it.utteranceId == utteranceId }
                            ?.also { activeUtterance = null }
                    } ?: return
                    emit(ArabicTtsState.Ready)
                    mainHandler.post { onUtteranceCompleted(utterance) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    val wasActive = synchronized(requestLock) {
                        if (activeUtterance?.utteranceId == utteranceId) {
                            activeUtterance = null
                            true
                        } else {
                            false
                        }
                    }
                    if (wasActive) emit(ArabicTtsState.Ready)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handlePlaybackError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handlePlaybackError(utteranceId)
                }
            }
        )
        emit(ArabicTtsState.Ready)
        synchronized(requestLock) {
            pendingUtterance.also { pendingUtterance = null }
        }?.let(::speak)
    }

    private fun emit(newState: ArabicTtsState) {
        state = newState
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(newState)
        } else {
            mainHandler.post { onStateChanged(newState) }
        }
    }

    private fun handlePlaybackError(utteranceId: String?) {
        val wasActive = synchronized(requestLock) {
            if (activeUtterance?.utteranceId == utteranceId) {
                activeUtterance = null
                true
            } else {
                false
            }
        }
        if (wasActive) emit(ArabicTtsState.Error("Text-to-speech playback failed."))
    }
}

internal object AudioCoordinationPolicy {
    fun canStartRecognition(
        ttsState: ArabicTtsState,
        hasPendingOrActiveSpeech: Boolean
    ): Boolean = ttsState !is ArabicTtsState.Speaking && !hasPendingOrActiveSpeech
}

internal fun isArabicTtsLanguageAvailable(result: Int): Boolean {
    return result >= TextToSpeech.LANG_AVAILABLE
}
