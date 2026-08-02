package com.example.testandroidenv.agent.validation

import java.text.Normalizer
import java.util.Locale

/** The Planner can select only these explicitly controlled application aliases. */
object AllowedAppRegistry {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    fun resolvePackage(target: String?): String? {
        val key = target?.let(::key) ?: return null
        return when (key) {
            "whatsapp", "واتساب", "واتس اب" -> WHATSAPP_PACKAGE
            "whatsapp business", "واتساب للاعمال", "واتساب أعمال",
            "واتساب بزنس" -> WHATSAPP_BUSINESS_PACKAGE
            else -> null
        }
    }

    private fun key(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]"), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }
}
