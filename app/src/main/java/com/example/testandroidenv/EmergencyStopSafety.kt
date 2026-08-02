package com.example.testandroidenv

import java.text.Normalizer

/**
 * Deliberately narrow local safety override. It is not a conversational intent classifier:
 * every non-emergency utterance is forwarded unchanged to the AI Planner.
 */
object EmergencyStopSafety {
    const val SPOKEN_CONFIRMATION = "تم إيقاف الجلسة."

    fun isExplicitStop(utterance: String): Boolean {
        return normalize(utterance) == "وقف"
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]"), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace("\u0640", "")
            .trim()
    }
}
