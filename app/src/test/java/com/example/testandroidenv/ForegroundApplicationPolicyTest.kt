package com.example.testandroidenv

import com.example.testandroidenv.agent.safety.ForegroundApplicationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundApplicationPolicyTest {
    @Test
    fun dynamicallyResolvedOemLauncherIsAccepted() {
        val decision = evaluateMatchingPackage(
            packageName = OEM_LAUNCHER,
            resolvedHomePackageName = OEM_LAUNCHER
        )

        assertTrue(decision.allowed)
        assertEquals("android_launcher", decision.normalizedAppId)
        assertEquals("RESOLVED_HOME_MATCH", decision.reason)
    }

    @Test
    fun controllerApplicationIsRejected() {
        val decision = evaluateMatchingPackage(
            packageName = CONTROLLER_PACKAGE,
            resolvedHomePackageName = OEM_LAUNCHER
        )

        assertFalse(decision.allowed)
        assertEquals("CONTROLLER_APPLICATION", decision.reason)
    }

    @Test
    fun unrelatedApplicationIsRejected() {
        val decision = evaluateMatchingPackage(
            packageName = "org.telegram.messenger",
            resolvedHomePackageName = OEM_LAUNCHER
        )

        assertFalse(decision.allowed)
        assertEquals("PACKAGE_NOT_IN_NODE_ACTION_ALLOWLIST", decision.reason)
    }

    @Test
    fun whatsappRemainsAccepted() {
        val decision = evaluateMatchingPackage(
            packageName = "com.whatsapp",
            resolvedHomePackageName = OEM_LAUNCHER
        )

        assertTrue(decision.allowed)
        assertEquals("whatsapp", decision.normalizedAppId)
    }

    @Test
    fun launcherSnapshotIsRejectedWhenDifferentPackageIsLive() {
        val requestAppId = ForegroundApplicationPolicy.normalizeAppId(
            OEM_LAUNCHER,
            OEM_LAUNCHER
        )
        val livePackage = "org.telegram.messenger"
        val decision = ForegroundApplicationPolicy.evaluateNodeAction(
            requestPackageName = OEM_LAUNCHER,
            requestAppId = requestAppId,
            requestResolvedHomePackageName = OEM_LAUNCHER,
            livePackageName = livePackage,
            liveAppId = ForegroundApplicationPolicy.normalizeAppId(
                livePackage,
                OEM_LAUNCHER
            ),
            liveResolvedHomePackageName = OEM_LAUNCHER,
            controllerPackageName = CONTROLLER_PACKAGE
        )

        assertFalse(decision.allowed)
        assertEquals("REQUEST_LIVE_PACKAGE_MISMATCH", decision.reason)
    }

    private fun evaluateMatchingPackage(
        packageName: String,
        resolvedHomePackageName: String?
    ) = ForegroundApplicationPolicy.evaluateNodeAction(
        requestPackageName = packageName,
        requestAppId = ForegroundApplicationPolicy.normalizeAppId(
            packageName,
            resolvedHomePackageName
        ),
        requestResolvedHomePackageName = resolvedHomePackageName,
        livePackageName = packageName,
        liveAppId = ForegroundApplicationPolicy.normalizeAppId(
            packageName,
            resolvedHomePackageName
        ),
        liveResolvedHomePackageName = resolvedHomePackageName,
        controllerPackageName = CONTROLLER_PACKAGE
    )

    private companion object {
        const val OEM_LAUNCHER = "com.transsion.XOSLauncher.upgrade"
        const val CONTROLLER_PACKAGE = "com.example.testandroidenv"
    }
}
