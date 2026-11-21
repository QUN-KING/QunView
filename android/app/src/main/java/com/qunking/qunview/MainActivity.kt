package com.qunking.qunview

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private var receiverRegistered = false
    private var startAfterAccessibilitySettings = false
    private var pendingStartAfterNotificationPermission = false
    private var allowRemoteAssistanceForSession = false
    private var mainButton: Button? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val continueStarting = pendingStartAfterNotificationPermission
        pendingStartAfterNotificationPermission = false
        if (continueStarting) {
            if (granted) continueStartFlow() else showNotificationPermissionRequired()
        }
    }

    private val serviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenShareService.ACTION_SESSION_READY -> {
                    val link = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_LINK) ?: return
                    shareViewerLink(link)
                    refreshMainButton()
                }
                ScreenShareService.ACTION_SHARING_STARTED,
                ScreenShareService.ACTION_SHARING_STOPPED -> refreshMainButton()
                ScreenShareService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(ScreenShareService.EXTRA_ERROR) ?: "Screen sharing could not start."
                    AlertDialog.Builder(this@MainActivity).setTitle("QunView").setMessage(message).setPositiveButton("OK", null).show()
                    refreshMainButton()
                }
                ScreenShareService.ACTION_VIEWER_REQUEST -> showViewerApproval()
                ScreenShareService.ACTION_REMOTE_CONTROL_REQUEST -> showRemoteControlApproval()
            }
        }
    }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val service = Intent(this, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenShareService.EXTRA_ALLOW_REMOTE_ASSISTANCE, allowRemoteAssistanceForSession)
            }
            startForegroundService(service)
            refreshMainButton()
        } else {
            refreshMainButton()
            showRequiredChoice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val title = TextView(this).apply {
            text = "QunView\n\nShare your screen only when you choose. The main button starts or stops sharing. Android will ask for screen-capture approval. Remote control needs your separate Accessibility approval and can be enabled during setup."
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        mainButton = Button(this).apply {
            setOnClickListener {
                if (ScreenShareService.isActive) stopScreenSharing() else beginStartFlow()
            }
        }
        val status = TextView(this).apply {
            text = "You approve every viewer. You can stop sharing at any time."
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            addView(title)
            addView(mainButton)
            addView(status)
        }
        // Android 15 enforces edge-to-edge for apps targeting API 35.
        // Keep the controls clear of the status bar, cutout and navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(20 + bars.left, 20 + bars.top, 20 + bars.right, 20 + bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshMainButton()

        val filter = IntentFilter().apply {
            addAction(ScreenShareService.ACTION_SESSION_READY)
            addAction(ScreenShareService.ACTION_ERROR)
            addAction(ScreenShareService.ACTION_SHARING_STARTED)
            addAction(ScreenShareService.ACTION_SHARING_STOPPED)
            addAction(ScreenShareService.ACTION_VIEWER_REQUEST)
            addAction(ScreenShareService.ACTION_REMOTE_CONTROL_REQUEST)
        }
        ContextCompat.registerReceiver(this, serviceEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

     }

    private fun refreshMainButton() {
        mainButton?.text = if (ScreenShareService.isActive) "Stop sharing" else "Start sharing"
    }

    private fun stopScreenSharing() {
        startService(Intent(this, ScreenShareService::class.java)
            .setAction(ScreenShareService.ACTION_STOP_SHARING))
        refreshMainButton()
    }

    private fun beginStartFlow() {
        // Viewer approval and the persistent Stop action are notification-based.
        // Do not start a session on Android 13+ when those controls cannot be shown.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterNotificationPermission = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueStartFlow()
    }

    private fun areRequiredNotificationsEnabled(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val viewerChannel = manager.getNotificationChannel(ScreenShareService.REQUEST_CHANNEL_ID)
            val sharingChannel = manager.getNotificationChannel(ScreenShareService.CHANNEL_ID)
            if (viewerChannel?.importance == NotificationManager.IMPORTANCE_NONE ||
                sharingChannel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun showNotificationPermissionRequired() {
        AlertDialog.Builder(this)
            .setTitle("Notifications are required")
            .setMessage("QunView needs notifications to show viewer approval requests and the Stop sharing control while the app is in the background. Allow notifications in Android App Settings, then tap Start sharing again.")
            .setPositiveButton("Open app settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun continueStartFlow() {
        if (!areRequiredNotificationsEnabled()) {
            showNotificationPermissionRequired()
            return
        }
        val preferences = getSharedPreferences("qunview_preferences", MODE_PRIVATE)
        if (preferences.getBoolean("remote_assistance_choice_saved", false)) {
            // Remember the owner's first choice; do not repeat the same disclosure every start.
            allowRemoteAssistanceForSession =
                preferences.getBoolean("remote_assistance_enabled", false) && isAccessibilityServiceEnabled()
            requestScreenPermission()
            return
        }
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        AlertDialog.Builder(this)
            .setTitle("Optional Remote Assistance")
            .setMessage("Remote Assistance is optional. If enabled, an approved viewer can use Back, Home, Tap and Swipe only after your separate approval for that session. QunView does not read window text or collect other-app content through Accessibility. Screen-only sharing works without it.")
            .setPositiveButton(if (accessibilityEnabled) "Enable once" else "Set up once") { _, _ ->
                if (accessibilityEnabled) {
                    allowRemoteAssistanceForSession = true
                    preferences.edit().putBoolean("remote_assistance_choice_saved", true)
                        .putBoolean("remote_assistance_enabled", true).apply()
                    requestScreenPermission()
                } else {
                    startAfterAccessibilitySettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Screen sharing only") { _, _ ->
                allowRemoteAssistanceForSession = false
                preferences.edit().putBoolean("remote_assistance_choice_saved", true)
                    .putBoolean("remote_assistance_enabled", false).apply()
                requestScreenPermission()
            }
            .show()
    }

    private fun showViewerApproval() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Viewer wants to see your screen")
            .setMessage("Allow this person to view your live screen? Nothing is shared with this viewer until you choose Allow. You can stop sharing at any time.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_APPROVE_VIEWER)) }
            .setNegativeButton("Deny") { _, _ -> startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_DENY_VIEWER)) }
            .show()
    }

    private fun showRemoteControlApproval() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Remote control request")
            .setMessage("The viewer wants to tap, swipe, Back or Home on this phone. Allow only if you trust this person. You can disable controls from the QunView notification at any time.")
            .setCancelable(false)
            .setPositiveButton("Allow controls") { _, _ ->
                startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_APPROVE_REMOTE_CONTROL))
            }
            .setNegativeButton("Deny") { _, _ ->
                startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_DENY_REMOTE_CONTROL))
            }
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = packageName + "/" + QunViewAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    override fun onResume() {
        super.onResume()
        refreshMainButton()
        if (startAfterAccessibilitySettings) {
            startAfterAccessibilitySettings = false
            if (isAccessibilityServiceEnabled()) {
                allowRemoteAssistanceForSession = true
                getSharedPreferences("qunview_preferences", MODE_PRIVATE).edit()
                    .putBoolean("remote_assistance_choice_saved", true)
                    .putBoolean("remote_assistance_enabled", true).apply()
                requestScreenPermission()
            } else {
                allowRemoteAssistanceForSession = false
                getSharedPreferences("qunview_preferences", MODE_PRIVATE).edit()
                    .putBoolean("remote_assistance_choice_saved", true)
                    .putBoolean("remote_assistance_enabled", false).apply()
                requestScreenPermission()
            }
        }
    }

    private fun requestScreenPermission() {
        // Android requires its own consent for each new MediaProjection session.
        // Launch that single system prompt directly; avoid an extra in-app confirmation button.
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun showRequiredChoice() {
        AlertDialog.Builder(this)
            .setTitle("Screen-sharing permission required")
            .setMessage("QunView cannot share your screen unless you approve Android's permission.")
            .setCancelable(true)
            .setPositiveButton("Try again") { _, _ -> requestScreenPermission() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareViewerLink(link: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "View my QunView screen (with my permission): " + link)
        }
        startActivity(Intent.createChooser(share, "Share QunView link"))
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(serviceEvents)
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
