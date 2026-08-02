package com.example.testandroidenv

import com.example.testandroidenv.agent.perception.CurrentAppNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentAppNormalizerTest {
    @Test
    fun usesActualLauncherPackageAndKnownLogicalIds() {
        assertEquals(
            "android_launcher",
            CurrentAppNormalizer.normalize(
                packageName = "com.transsion.XOSLauncher.upgrade",
                displayName = "Home",
                screenName = "Home Screen",
                launcherPackageName = "com.transsion.XOSLauncher.upgrade"
            ).appId
        )
        assertEquals(
            "whatsapp",
            CurrentAppNormalizer.normalize(
                "com.whatsapp",
                "WhatsApp",
                "Chats",
                "com.transsion.XOSLauncher.upgrade"
            ).appId
        )
        assertEquals(
            "whatsapp_business",
            CurrentAppNormalizer.normalize(
                "com.whatsapp.w4b",
                "WhatsApp Business",
                "Chats",
                "com.transsion.XOSLauncher.upgrade"
            ).appId
        )
    }
}
