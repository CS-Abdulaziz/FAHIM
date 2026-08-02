package com.example.testandroidenv.agent.safety

data class ForegroundPolicyDecision(
    val allowed: Boolean,
    val normalizedAppId: String,
    val reason: String
)

/** Strict package-based policy shared by perception, validation, and execution. */
object ForegroundApplicationPolicy {
    const val ANDROID_LAUNCHER_APP_ID = "android_launcher"
    const val WHATSAPP_APP_ID = "whatsapp"
    const val WHATSAPP_BUSINESS_APP_ID = "whatsapp_business"
    const val ANDROID_SYSTEM_APP_ID = "android_system"
    const val UNKNOWN_APP_ID = "unknown"

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
    private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /* Used only when Android cannot return a default HOME package. */
    private val commonLauncherFallbackPackages = setOf(
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.coloros.launcher",
        "com.vivo.launcher",
        "com.motorola.launcher3",
        "com.oneplus.launcher"
    )

    fun normalizeAppId(
        packageName: String?,
        resolvedHomePackageName: String?
    ): String = when {
        packageName.isNullOrBlank() -> UNKNOWN_APP_ID
        packageName == WHATSAPP_PACKAGE -> WHATSAPP_APP_ID
        packageName == WHATSAPP_BUSINESS_PACKAGE -> WHATSAPP_BUSINESS_APP_ID
        isLauncherPackage(packageName, resolvedHomePackageName) -> ANDROID_LAUNCHER_APP_ID
        isRequiredAndroidSystemPackage(packageName) -> ANDROID_SYSTEM_APP_ID
        else -> UNKNOWN_APP_ID
    }

    fun evaluateNodeAction(
        requestPackageName: String?,
        requestAppId: String?,
        requestResolvedHomePackageName: String?,
        livePackageName: String?,
        liveAppId: String?,
        liveResolvedHomePackageName: String?,
        controllerPackageName: String,
        whatsappBusinessEnabled: Boolean = true
    ): ForegroundPolicyDecision {
        if (requestPackageName.isNullOrBlank() || livePackageName.isNullOrBlank()) {
            return rejected(UNKNOWN_APP_ID, "FOREGROUND_PACKAGE_MISSING")
        }
        if (requestPackageName != livePackageName) {
            return rejected(
                normalizeAppId(livePackageName, liveResolvedHomePackageName),
                "REQUEST_LIVE_PACKAGE_MISMATCH"
            )
        }
        if (livePackageName == controllerPackageName) {
            return rejected(UNKNOWN_APP_ID, "CONTROLLER_APPLICATION")
        }

        val expectedRequestAppId = normalizeAppId(
            requestPackageName,
            requestResolvedHomePackageName
        )
        val expectedLiveAppId = normalizeAppId(livePackageName, liveResolvedHomePackageName)
        if (requestAppId != expectedRequestAppId || liveAppId != expectedLiveAppId) {
            return rejected(expectedLiveAppId, "NORMALIZED_APP_ID_MISMATCH")
        }
        if (expectedRequestAppId != expectedLiveAppId) {
            return rejected(expectedLiveAppId, "REQUEST_LIVE_APP_ID_MISMATCH")
        }

        return when (expectedLiveAppId) {
            ANDROID_LAUNCHER_APP_ID -> allowed(
                expectedLiveAppId,
                if (liveResolvedHomePackageName.isNullOrBlank()) {
                    "COMMON_LAUNCHER_FALLBACK"
                } else {
                    "RESOLVED_HOME_MATCH"
                }
            )
            WHATSAPP_APP_ID -> allowed(expectedLiveAppId, "WHATSAPP_PACKAGE")
            WHATSAPP_BUSINESS_APP_ID -> if (whatsappBusinessEnabled) {
                allowed(expectedLiveAppId, "WHATSAPP_BUSINESS_PACKAGE")
            } else {
                rejected(expectedLiveAppId, "WHATSAPP_BUSINESS_DISABLED")
            }
            ANDROID_SYSTEM_APP_ID -> allowed(expectedLiveAppId, "REQUIRED_ANDROID_SYSTEM_UI")
            else -> rejected(expectedLiveAppId, "PACKAGE_NOT_IN_NODE_ACTION_ALLOWLIST")
        }
    }

    fun isLauncherPackage(
        packageName: String,
        resolvedHomePackageName: String?
    ): Boolean {
        return if (!resolvedHomePackageName.isNullOrBlank()) {
            packageName == resolvedHomePackageName
        } else {
            packageName in commonLauncherFallbackPackages
        }
    }

    private fun isRequiredAndroidSystemPackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.android.settings") ||
            packageName.startsWith("com.android.permissioncontroller")
    }

    private fun allowed(appId: String, reason: String) = ForegroundPolicyDecision(
        allowed = true,
        normalizedAppId = appId,
        reason = reason
    )

    private fun rejected(appId: String, reason: String) = ForegroundPolicyDecision(
        allowed = false,
        normalizedAppId = appId,
        reason = reason
    )
}
