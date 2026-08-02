package com.example.testandroidenv

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSpeechRecognizerControllerTest {
    @Test
    fun mapsNetworkAndNoSpeechErrors() {
        assertEquals(
            SpeechRecognitionState.NetworkError,
            mapSpeechRecognitionError(SpeechRecognizer.ERROR_NETWORK)
        )
        assertEquals(
            SpeechRecognitionState.NoSpeech,
            mapSpeechRecognitionError(SpeechRecognizer.ERROR_NO_MATCH)
        )
    }

    @Test
    fun mapsPermissionAndBusyErrorsToReadableMessages() {
        val permission = mapSpeechRecognitionError(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        )
        val busy = mapSpeechRecognitionError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)

        assertTrue(permission is SpeechRecognitionState.Error)
        assertTrue((permission as SpeechRecognitionState.Error).message.contains("permission"))
        assertTrue(busy is SpeechRecognitionState.Error)
        assertTrue((busy as SpeechRecognitionState.Error).message.contains("busy"))
    }
}
