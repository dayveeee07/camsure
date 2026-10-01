package com.camsure.profiler.session

import org.junit.Assert.*
import org.junit.Test

class CameraRunPolicyTest {
    @Test fun normalStreamsOutliveValidationDeadlineOnEitherTransport() {
        for (hasReceiver in listOf(false, true)) {
            assertNull(CameraRunPolicy.deadlineMs(false, true, hasReceiver))
            assertNull(CameraRunPolicy.deadlineMs(true, false, hasReceiver))
        }
        assertEquals(300_000L, CameraRunPolicy.deadlineMs(false, false, false))
        assertEquals(600_000L, CameraRunPolicy.deadlineMs(false, false, true))
    }

    @Test fun settingsStayLockedUntilTerminalTeardown() {
        for (state in listOf("preparing", "opening_camera", "running", "stopping")) assertTrue(CameraRunPolicy.active(state))
        for (state in listOf("ready", "failed", "stopped", "completed")) assertFalse(CameraRunPolicy.active(state))
    }

    @Test fun restorationUsesIdentityRatherThanEnumerationOrderAndRejectsAbsentLinks() {
        val links = listOf("rndis0:192.168.42.129/24:8", "eth0:10.0.0.2/24:4")
        assertEquals(1, CameraRunPolicy.restoredIndex(links.reversed(), links.first()))
        assertNull(CameraRunPolicy.restoredIndex(links, "rndis0:192.168.42.130/24:8"))
        assertNull(CameraRunPolicy.restoredIndex(links, "rndis0:192.168.42.129/24:9"))
        assertNull(CameraRunPolicy.restoredIndex(emptyList(), links.first()))
    }
}
