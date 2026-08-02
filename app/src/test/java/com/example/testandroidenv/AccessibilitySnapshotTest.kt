package com.example.testandroidenv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySnapshotTest {
    @Test
    fun freshnessUsesCentralizedLimit() {
        val capturedAt = 1_000_000L
        val snapshot = snapshot(capturedAt)

        assertTrue(snapshot.isFresh(capturedAt))
        assertTrue(snapshot.isFresh(capturedAt + DemoConfig.SNAPSHOT_FRESHNESS_MS))
        assertFalse(snapshot.isFresh(capturedAt + DemoConfig.SNAPSHOT_FRESHNESS_MS + 1))
        assertFalse(snapshot.isFresh(capturedAt - 1))
    }

    private fun snapshot(capturedAt: Long) = AccessibilitySnapshot(
        packageName = "com.example.target",
        windowTitle = "Target",
        capturedAtEpochMillis = capturedAt,
        serializedTree = "tree_truncated=false",
        nodes = emptyList(),
        treeTruncated = false
    )
}
