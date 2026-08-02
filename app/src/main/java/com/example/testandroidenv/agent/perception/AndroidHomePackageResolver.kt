package com.example.testandroidenv.agent.perception

import android.content.Intent
import android.content.pm.PackageManager

object AndroidHomePackageResolver {
    fun resolve(packageManager: PackageManager): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            val activityInfo = packageManager.resolveActivity(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo ?: return null
            val packageName = activityInfo.packageName?.trim()?.takeIf(String::isNotEmpty)
                ?: return null
            if (
                packageName == "android" &&
                (
                    activityInfo.name?.contains("ResolverActivity", ignoreCase = true) == true ||
                        activityInfo.name?.contains("ChooserActivity", ignoreCase = true) == true
                    )
            ) {
                null
            } else {
                packageName
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
