package com.example.testandroidenv.agent.perception

import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.safety.ForegroundApplicationPolicy

object CurrentAppNormalizer {
    fun normalize(
        packageName: String?,
        displayName: String?,
        screenName: String?,
        launcherPackageName: String?
    ): CurrentApp {
        val appId = ForegroundApplicationPolicy.normalizeAppId(
            packageName = packageName,
            resolvedHomePackageName = launcherPackageName
        )
        return CurrentApp(
            packageName = packageName,
            displayName = displayName,
            appId = appId,
            screenName = screenName ?: displayName
        )
    }

}
