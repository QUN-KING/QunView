package com.qunking.qunview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class ScreenShareService : Service() {
    private var streamer: WebRtcScreenStreamer? = null
    private var remoteAssistanceForSession = false

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    fun revokeRemoteControlForAccessibilityChange() {
        streamer?.denyRemoteControl()
    }
    private val pendingViewerIds = ConcurrentHashMap.newKeySet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SHARING -> { isActive = false; sendBroadcast(Intent(ACTION_SHARING_STOPPED).setPackage(packageName)); clearAllViewerRequests(); clearSavedViewerLink(); streamer?.stop(); streamer = null; stopSelf(); return START_NOT_STICKY }
            ACTION_APPROVE_VIEWER -> {
                val viewerId = intent.getStringExtra(EXTRA_VIEWER_ID)
                if (viewerId != null && pendingViewerIds.remove(viewerId)) {
                    getSystemService(NotificationManager::class.java).cancel(viewerNotificationId(viewerId))
                    streamer?.approveViewer(viewerId)
                }
                return START_NOT_STICKY
            }
            ACTION_DENY_VIEWER -> {
                val viewerId = intent.getStringExtra(EXTRA_VIEWER_ID)
                if (viewerId != null && pendingViewerIds.remove(viewerId)) {
                    getSystemService(NotificationManager::class.java).cancel(viewerNotificationId(viewerId))
                    streamer?.denyViewer(viewerId)
                }
                return START_NOT_STICKY
            }
        }
        // Ignore duplicate start commands before changing session state or validating
        // their extras; the active MediaProjection belongs to the first start command.
        if (streamer != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            notifyError("Android screen permission was not received.")
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannels()
        isActive = true
        sendBroadcast(Intent(ACTION_SHARING_STARTED).setPackage(packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        remoteAssistanceForSession = intent?.getBooleanExtra(EXTRA_ALLOW_REMOTE_ASSISTANCE, false) ?: false
        streamer = WebRtcScreenStreamer(
            applicationContext,
            resultData,
            remoteAssistanceForSession,
            onSessionReady = { viewerLink ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PENDING_VIEWER_LINK, viewerLink).apply()
                sendBroadcast(Intent(ACTION_SESSION_READY).setPackage(packageName)
                    .putExtra(EXTRA_VIEWER_LINK, viewerLink))
            },
            onError = { message ->
                Log.w(TAG, "Temporary connection issue: " + message)
            },
            onViewerRequest = { viewerId -> showViewerRequestNotification(viewerId) },
            onViewerLeft = { viewerId -> clearViewerRequest(viewerId) },
            onStopped = { clearAllViewerRequests(); clearSavedViewerLink(); stopSelf() }
        )
        try {
            streamer?.start()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen sender", error)
            notifyError("Could not start screen sharing.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showViewerRequestNotification(viewerId: String) {
        if (!pendingViewerIds.add(viewerId)) return
        getSystemService(NotificationManager::class.java)
            .notify(viewerNotificationId(viewerId), buildViewerRequestNotification(viewerId))
    }

    private fun clearSavedViewerLink() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PENDING_VIEWER_LINK).apply()
    }

    private fun viewerNotificationId(viewerId: String): Int =
        1000 + (viewerId.hashCode() and 0x3fffffff)

    private fun clearViewerRequest(viewerId: String) {
        pendingViewerIds.remove(viewerId)
        getSystemService(NotificationManager::class.java).cancel(viewerNotificationId(viewerId))
    }

    private fun clearAllViewerRequests() {
        pendingViewerIds.toList().forEach { clearViewerRequest(it) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = packageName + "/" + QunViewAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun buildViewerRequestNotification(viewerId: String): Notification {
        val requestCode = viewerNotificationId(viewerId)
        val approveIntent = Intent(this, ScreenShareService::class.java)
            .setAction(ACTION_APPROVE_VIEWER).putExtra(EXTRA_VIEWER_ID, viewerId)
        val denyIntent = Intent(this, ScreenShareService::class.java)
            .setAction(ACTION_DENY_VIEWER).putExtra(EXTRA_VIEWER_ID, viewerId)
        val approvePending = PendingIntent.getService(this, requestCode, approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val denyPending = PendingIntent.getService(this, requestCode + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, REQUEST_CHANNEL_ID) else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        // Reflect the current Android setting, not only the session's initial choice.
        val requestText = if (remoteAssistanceForSession && isAccessibilityServiceEnabled()) {
            "Viewer ${viewerId.take(6)} requests screen viewing and may use Back, Home, Tap and Swipe. Allow only if you trust them."
        } else {
            "Viewer ${viewerId.take(6)} requests view-only access to your shared screen. Allow only if you trust them."
        }
        return builder.setContentTitle("QunView: Viewer request")
            .setContentText(requestText)
            .setStyle(Notification.BigTextStyle().bigText(requestText))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(android.R.drawable.checkbox_on_background, "Allow", approvePending)
            .addAction(android.R.drawable.ic_delete, "Deny", denyPending)
            .build()
    }

    private fun notifyError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        val wasActive = isActive
        isActive = false
        if (wasActive) sendBroadcast(Intent(ACTION_SHARING_STOPPED).setPackage(packageName))
        clearAllViewerRequests()
        clearSavedViewerLink()
        streamer?.stop()
        streamer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "QunView screen sharing", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(
                REQUEST_CHANNEL_ID, "QunView viewer requests", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when someone asks to view your screen"
                setShowBadge(false)
            })
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        val stopIntent = Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP_SHARING)
        val stopPendingIntent = PendingIntent.getService(this, 42, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder.setContentTitle("QunView screen sharing")
            .setContentText("Your screen is being shared. Tap Stop to end it immediately.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop sharing", stopPendingIntent)
            .build()
    }

    companion object {
        @Volatile private var activeInstance: ScreenShareService? = null

        fun revokeRemoteControlForAccessibilityChange() {
            activeInstance?.revokeRemoteControlForAccessibilityChange()
        }

        @Volatile var isActive = false
            private set

        const val ACTION_STOP_SHARING = "com.qunking.qunview.STOP_SHARING"
        const val ACTION_SHARING_STARTED = "com.qunking.qunview.SHARING_STARTED"
        const val ACTION_SHARING_STOPPED = "com.qunking.qunview.SHARING_STOPPED"
        const val ACTION_APPROVE_VIEWER = "com.qunking.qunview.APPROVE_VIEWER"
        const val ACTION_DENY_VIEWER = "com.qunking.qunview.DENY_VIEWER"
        const val EXTRA_VIEWER_ID = "viewer_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ALLOW_REMOTE_ASSISTANCE = "allow_remote_assistance"
        const val ACTION_SESSION_READY = "com.qunking.qunview.SESSION_READY"
        const val ACTION_ERROR = "com.qunking.qunview.SHARING_ERROR"
        const val EXTRA_VIEWER_LINK = "viewer_link"
        const val EXTRA_ERROR = "sharing_error"
        const val PREFS_NAME = "qunview_state"
        const val PENDING_VIEWER_LINK = "pending_viewer_link"
        private const val TAG = "QunViewService"
        const val CHANNEL_ID = "qunview_screen_share"
        const val REQUEST_CHANNEL_ID = "qunview_viewer_requests"
        private const val NOTIFICATION_ID = 41
    }
}
