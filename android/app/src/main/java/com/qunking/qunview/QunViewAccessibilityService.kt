package com.qunking.qunview

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Explicitly enabled by the device owner in Android Settings. */
class QunViewAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // QunView does not inspect or collect window contents.
    }

    override fun onInterrupt() {
        // No background action is started from accessibility events.
    }

    companion object {
        @Volatile var instance: QunViewAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}