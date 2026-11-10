package com.qunking.qunview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class ScreenShareService : Service() {
    private var streamer: WebRtcScreenStreamer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
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
        if (streamer != null) return START_NOT_STICKY

        // The service is foreground before ScreenCapturerAndroid creates MediaProjection.
        // Do not call getMediaProjection() here: Android grants each consent token once.
        streamer = WebRtcScreenStreamer(
            applicationContext,
            resultData,
            onSessionReady = { viewerLink ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PENDING_VIEWER_LINK, viewerLink)
                    .apply()
                sendBroadcast(Intent(ACTION_SESSION_READY).setPackage(packageName)
                    .putExtra(EXTRA_VIEWER_LINK, viewerLink))
            },
            onError = { message ->
                // Temporary signaling/WebRTC errors must not stop screen sharing.
                Log.w(TAG, "Temporary connection issue: " + message)
            },
            onStopped = { stopSelf() }
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

    private fun notifyError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
    }

    override fun onDestroy() {
        streamer?.stop()
        streamer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "QunView screen sharing", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("QunView screen sharing")
            .setContentText("Screen sharing is active with your permission.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
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
        private const val NOTIFICATION_ID = 41
    }
}
