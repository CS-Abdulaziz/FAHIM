package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.execution.hasMeaningfulUiChange
import com.example.testandroidenv.agent.execution.resolveControlledAppPackage
import com.example.testandroidenv.agent.perception.AgentObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPolicyTest {
    @Test
    fun controlledAppMapContainsOnlyWhatsAppMvpTarget() {
        assertEquals("com.whatsapp", resolveControlledAppPackage("WhatsApp"))
        assertEquals("com.whatsapp", resolveControlledAppPackage("whatsapp"))
        assertEquals("com.whatsapp", resolveControlledAppPackage("واتساب"))
        assertEquals("com.whatsapp.w4b", resolveControlledAppPackage("WhatsApp Business"))
        assertNull(resolveControlledAppPackage("Unknown"))
        assertNull(resolveControlledAppPackage("com.example.untrusted"))
    }

    @Test
    fun meaningfulChangeUsesPackageOrFingerprintNotVersion() {
        val source = observation(1, "com.whatsapp", "same")

        assertFalse(hasMeaningfulUiChange(source, observation(2, "com.whatsapp", "same")))
        assertTrue(hasMeaningfulUiChange(source, observation(2, "com.whatsapp", "changed")))
        assertTrue(hasMeaningfulUiChange(source, observation(2, "other.package", "same")))
    }

    private fun observation(
        version: Long,
        packageName: String,
        fingerprint: String
    ) = AgentObservation(
        currentApp = CurrentApp(packageName, packageName),
        uiState = CompactUiState(version, emptyList()),
        fingerprint = fingerprint,
        capturedAtEpochMillis = version
    )
}
