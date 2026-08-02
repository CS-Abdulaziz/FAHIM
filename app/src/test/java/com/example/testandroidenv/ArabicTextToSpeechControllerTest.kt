package com.example.testandroidenv

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextToSpeechControllerTest {
    @Test
    fun recognizesSupportedAndMissingArabicVoiceResults() {
        assertTrue(isArabicTtsLanguageAvailable(TextToSpeech.LANG_AVAILABLE))
        assertTrue(isArabicTtsLanguageAvailable(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertTrue(isArabicTtsLanguageAvailable(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
        assertFalse(isArabicTtsLanguageAvailable(TextToSpeech.LANG_MISSING_DATA))
        assertFalse(isArabicTtsLanguageAvailable(TextToSpeech.LANG_NOT_SUPPORTED))
    }
}
