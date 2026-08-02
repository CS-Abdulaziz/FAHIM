package com.example.testandroidenv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopSafetyTest {
    @Test
    fun recognizesOnlyTheNarrowExplicitSafetyOverride() {
        assertTrue(EmergencyStopSafety.isExplicitStop("وقف"))
        assertTrue(EmergencyStopSafety.isExplicitStop("وَقْف"))

        assertFalse(EmergencyStopSafety.isExplicitStop("لا خلاص يعطيك العافية"))
        assertFalse(EmergencyStopSafety.isExplicitStop("إيه"))
        assertFalse(EmergencyStopSafety.isExplicitStop("إيه، افتح محادثة سارة"))
        assertFalse(EmergencyStopSafety.isExplicitStop("أبي محادثة محمد"))
    }
}
