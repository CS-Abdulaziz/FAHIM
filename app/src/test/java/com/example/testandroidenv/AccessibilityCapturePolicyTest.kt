package com.example.testandroidenv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCapturePolicyTest {
    @Test
    fun rejectsControllerAndMissingPackages() {
        val ownPackage = "com.example.testandroidenv"

        assertFalse(AccessibilityCapturePolicy.isExternalTarget(null, ownPackage))
        assertFalse(AccessibilityCapturePolicy.isExternalTarget("", ownPackage))
        assertFalse(AccessibilityCapturePolicy.isExternalTarget(ownPackage, ownPackage))
    }

    @Test
    fun acceptsExternalPackage() {
        assertTrue(
            AccessibilityCapturePolicy.isExternalTarget(
                rootPackageName = "com.example.target",
                controllerPackageName = "com.example.testandroidenv"
            )
        )
    }
}
