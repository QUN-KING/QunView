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
import android.util.Log

class ScreenShareService : Service() {
    private var streamer: WebRtcScreenStreamer? = null
    @Volatile private var viewerRequestPending = false
    @Volatile private var remoteControlRequestPending = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SHARING -> { clearViewerRequest(); streamer?.stop(); streamer = null; stopSelf(); return START_NOT_STICKY }
            ACTION_APPROVE_VIEWER -> {
                if (viewerRequestPending) {
                    clearViewerRequest()
                    streamer?.approveViewer()
                }
                return START_NOT_STICKY
            }
            ACTION_APPROVE_REMOTE_CONTROL -> { if (streamer?.approveRemoteControl() == true) showRemoteControlActiveNotification() else clearRemoteControlRequest(); return START_NOT_STICKY }
            ACTION_DENY_REMOTE_CONTROL -> { clearRemoteControlRequest(); streamer?.denyRemoteControl(); return START_NOT_STICKY }
            ACTION_DENY_VIEWER -> {
                if (viewerRequestPending) {
                    clearViewerRequest()
                    streamer?.denyViewer()
                }
                return START_NOT_STICKY
            }
        }
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

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
        // Ignore duplicate start commands while this capture session is alive.
        if (streamer != null) return START_NOT_STICKY

        streamer = WebRtcScreenStreamer(
            applicationContext,
            resultData,
            onSessionReady = { viewerLink ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PENDING_VIEWER_LINK, viewerLink).apply()
                sendBroadcast(Intent(ACTION_SESSION_READY).setPackage(packageName)
                    .putExtra(EXTRA_VIEWER_LINK, viewerLink))
            },
            onError = { message ->
                Log.w(TAG, "Temporary connection issue: " + message)
            },
            onViewerRequest = { showViewerRequestNotification() },
            onRemoteControlRequest = { showRemoteControlRequestNotification(); sendBroadcast(Intent(ACTION_REMOTE_CONTROL_REQUEST).setPackage(packageName)) },
            onViewerLeft = { clearViewerRequest() },
            onStopped = { clearViewerRequest(); stopSelf() }
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

    private fun showViewerRequestNotification() {
        if (viewerRequestPending) return
        viewerRequestPending = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(VIEWER_REQUEST_NOTIFICATION_ID, buildViewerRequestNotification())
        sendBroadcast(Intent(ACTION_VIEWER_REQUEST).setPackage(packageName))
    }

    private fun showRemoteControlRequestNotification() {
        if (remoteControlRequestPending) return
        remoteControlRequestPending = true
        val allow = PendingIntent.getService(this, 45, Intent(this, ScreenShareService::class.java).setAction(ACTION_APPROVE_REMOTE_CONTROL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deny = PendingIntent.getService(this, 46, Intent(this, ScreenShareService::class.java).setAction(ACTION_DENY_REMOTE_CONTROL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, REQUEST_CHANNEL_ID) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        getSystemService(NotificationManager::class.java).notify(CONTROL_REQUEST_NOTIFICATION_ID,
            builder.setContentTitle("QunView: Remote control request")
                .setContentText("Viewer wants to tap or swipe. Allow only if you trust them.")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
                .addAction(android.R.drawable.checkbox_on_background, "Allow controls", allow)
                .addAction(android.R.drawable.ic_delete, "Deny", deny).build())
    }

    private fun showRemoteControlActiveNotification() {
        val disable = PendingIntent.getService(this, 47,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_DENY_REMOTE_CONTROL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, REQUEST_CHANNEL_ID) else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        getSystemService(NotificationManager::class.java).notify(CONTROL_REQUEST_NOTIFICATION_ID,
            builder.setContentTitle("QunView: Remote controls are ON")
                .setContentText("Tap Disable controls to revoke remote assistance immediately.")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Disable controls", disable).build())
    }

    private fun clearRemoteControlRequest() {
        remoteControlRequestPending = false
        getSystemService(NotificationManager::class.java).cancel(CONTROL_REQUEST_NOTIFICATION_ID)
    }

    private fun clearViewerRequest() {
        clearRemoteControlRequest()
        viewerRequestPending = false
        getSystemService(NotificationManager::class.java)
            .cancel(VIEWER_REQUEST_NOTIFICATION_ID)
    }

    private fun buildViewerRequestNotification(): Notification {
        val approveIntent = Intent(this, ScreenShareService::class.java).setAction(ACTION_APPROVE_VIEWER)
        val denyIntent = Intent(this, ScreenShareService::class.java).setAction(ACTION_DENY_VIEWER)
        val approvePending = PendingIntent.getService(this, 43, approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val denyPending = PendingIntent.getService(this, 44, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, REQUEST_CHANNEL_ID) else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder.setContentTitle("QunView: Viewer request")
            .setContentText("Someone wants to view your screen. Choose Allow or Deny.")
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
        clearViewerRequest()
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
        const val ACTION_STOP_SHARING = "com.qunking.qunview.STOP_SHARING"
        const val ACTION_APPROVE_VIEWER = "com.qunking.qunview.APPROVE_VIEWER"
        const val ACTION_DENY_VIEWER = "com.qunking.qunview.DENY_VIEWER"
        const val ACTION_APPROVE_REMOTE_CONTROL = "com.qunking.qunview.APPROVE_REMOTE_CONTROL"
        const val ACTION_DENY_REMOTE_CONTROL = "com.qunking.qunview.DENY_REMOTE_CONTROL"
        const val ACTION_VIEWER_REQUEST = "com.qunking.qunview.VIEWER_REQUEST"
        const val ACTION_REMOTE_CONTROL_REQUEST = "com.qunking.qunview.REMOTE_CONTROL_REQUEST"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_SESSION_READY = "com.qunking.qunview.SESSION_READY"
        const val ACTION_ERROR = "com.qunking.qunview.SHARING_ERROR"
        const val EXTRA_VIEWER_LINK = "viewer_link"
        const val EXTRA_ERROR = "sharing_error"
        const val PREFS_NAME = "qunview_state"
        const val PENDING_VIEWER_LINK = "pending_viewer_link"
        private const val TAG = "QunViewService"
        private const val CHANNEL_ID = "qunview_screen_share"
        private const val REQUEST_CHANNEL_ID = "qunview_viewer_requests"
        private const val NOTIFICATION_ID = 41
        private const val VIEWER_REQUEST_NOTIFICATION_ID = 42
        private const val CONTROL_REQUEST_NOTIFICATION_ID = 43
    }
}
