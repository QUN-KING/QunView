package com.qunking.qunview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistancePolicyTest {
    @Test
    fun requestRequiresSessionConsentViewerApprovalAndAccessibility() {
        assertTrue(RemoteAssistancePolicy.canRequestControl(true, true, true))
        assertFalse(RemoteAssistancePolicy.canRequestControl(false, true, true))
        assertFalse(RemoteAssistancePolicy.canRequestControl(true, false, true))
        assertFalse(RemoteAssistancePolicy.canRequestControl(true, true, false))
    }

    @Test
    fun commandRequiresAllFourIndependentApprovalsAndAvailability() {
        assertTrue(RemoteAssistancePolicy.canExecuteControl(true, true, true, true))
        assertFalse(RemoteAssistancePolicy.canExecuteControl(false, true, true, true))
        assertFalse(RemoteAssistancePolicy.canExecuteControl(true, false, true, true))
        assertFalse(RemoteAssistancePolicy.canExecuteControl(true, true, false, true))
        assertFalse(RemoteAssistancePolicy.canExecuteControl(true, true, true, false))
    }
}
