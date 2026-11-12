package com.qunking.qunview

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/** Explicitly enabled by the device owner in Android Settings. */
class QunViewAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // QunView does not inspect or collect window contents.
    }

    override fun onInterrupt() {}

    fun performRemoteCommand(command: JSONObject) {
        when (command.optString("action")) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "tap", "swipe" -> {
                val x1 = normalized(command.optDouble("x1", command.optDouble("x", -1.0)))
                val y1 = normalized(command.optDouble("y1", command.optDouble("y", -1.0)))
                val x2 = normalized(command.optDouble("x2", x1.toDouble()))
                val y2 = normalized(command.optDouble("y2", y1.toDouble()))
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return
                val metrics = resources.displayMetrics
                val path = Path().apply {
                    moveTo(x1 * metrics.widthPixels, y1 * metrics.heightPixels)
                    lineTo(x2 * metrics.widthPixels, y2 * metrics.heightPixels)
                }
                val duration = if (command.optString("action") == "tap") 90L else 350L
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }
    }

    private fun normalized(value: Double): Float =
        if (value.isFinite() && value in 0.0..1.0) value.toFloat() else -1f

    companion object {
        @Volatile var instance: QunViewAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        revokeActiveRemoteControl()
        return super.onUnbind(intent)
    }

    private fun revokeActiveRemoteControl() {
        if (instance === this) {
            instance = null
            // Revoke synchronously; Android can restrict starting background services here.
            ScreenShareService.revokeRemoteControlForAccessibilityChange()
        }
    }

    override fun onDestroy() {
        revokeActiveRemoteControl()
        super.onDestroy()
    }
}
