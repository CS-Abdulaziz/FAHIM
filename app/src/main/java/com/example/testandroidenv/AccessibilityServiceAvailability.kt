package com.example.testandroidenv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

object AccessibilityServiceAvailability {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(context, TreeDumperService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo.serviceInfo
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
    }
}
