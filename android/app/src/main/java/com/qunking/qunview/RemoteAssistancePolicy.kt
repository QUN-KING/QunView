package com.qunking.qunview

/**
 * Pure session policy for remote-assistance commands.
 * Screen capture permission alone never grants remote-control permission.
 */
internal object RemoteAssistancePolicy {
    fun canRequestControl(
        sessionAllowsRemoteAssistance: Boolean,
        viewerApproved: Boolean,
        accessibilityServiceAvailable: Boolean
    ): Boolean = sessionAllowsRemoteAssistance && viewerApproved && accessibilityServiceAvailable

    fun canExecuteControl(
        sessionAllowsRemoteAssistance: Boolean,
        viewerApproved: Boolean,
        remoteControlApproved: Boolean,
        accessibilityServiceAvailable: Boolean
    ): Boolean = sessionAllowsRemoteAssistance &&
        viewerApproved &&
        remoteControlApproved &&
        accessibilityServiceAvailable
}
