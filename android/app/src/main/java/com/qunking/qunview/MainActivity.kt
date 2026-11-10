package com.qunking.qunview

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var receiverRegistered = false
    private var accessibilitySettingsOpened = false
    private var setupStarted = false

    private val serviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenShareService.ACTION_SESSION_READY -> {
                    val link = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_LINK) ?: return
                    shareViewerLink(link)
                }
                ScreenShareService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(ScreenShareService.EXTRA_ERROR) ?: "Screen sharing could not start."
                    AlertDialog.Builder(this@MainActivity).setTitle("QunView").setMessage(message).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val service = Intent(this, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(service)
        } else {
            showRequiredChoice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = "QunView\n\nQunView shares your screen only after Android permission. Remote assistance also requires you to enable its accessibility service in Settings. You can stop sharing at any time."
            textSize = 18f
            setPadding(32, 48, 32, 24)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(title)
        })
        val filter = IntentFilter().apply {
            addAction(ScreenShareService.ACTION_SESSION_READY)
            addAction(ScreenShareService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceEvents, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (!setupStarted) {
            setupStarted = true
            window.decorView.post { beginGuidedSetup() }
        } else if (accessibilitySettingsOpened) {
            accessibilitySettingsOpened = false
            if (isAccessibilityServiceEnabled()) requestScreenPermission() else showAccessibilityRequired()
        }
    }

    private fun beginGuidedSetup() {
        if (isAccessibilityServiceEnabled()) {
            requestScreenPermission()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Step 1 of 2 — Remote assistance")
            .setMessage("To let the viewer tap and swipe during a session, Android requires you to enable QunView remote assistance in Accessibility Settings. QunView does not read screen text. Enable it only if you agree.")
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Uninstall App") { _, _ -> requestUninstall() }
            .show()
    }

    private fun openAccessibilitySettings() {
        accessibilitySettingsOpened = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = packageName + "/" + QunViewAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showAccessibilityRequired() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility permission required")
            .setMessage("Remote assistance is not enabled. Open Android Settings to enable QunView, or uninstall the app.")
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Uninstall App") { _, _ -> requestUninstall() }
            .show()
    }

    private fun requestScreenPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        AlertDialog.Builder(this)
            .setTitle("Step 2 of 2 — Screen sharing")
            .setMessage("Android will now ask whether you want to share your screen. This approval is required each time a new screen-sharing session starts.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> capturePermission.launch(manager.createScreenCaptureIntent()) }
            .setNegativeButton("Uninstall App") { _, _ -> requestUninstall() }
            .show()
    }

    private fun showRequiredChoice() {
        AlertDialog.Builder(this)
            .setTitle("Screen-sharing permission required")
            .setMessage("QunView cannot start without your approval to share the screen.")
            .setCancelable(false)
            .setPositiveButton("Give Permission") { _, _ -> requestScreenPermission() }
            .setNegativeButton("Uninstall App") { _, _ -> requestUninstall() }
            .show()
    }

    private fun requestUninstall() {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName)))
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