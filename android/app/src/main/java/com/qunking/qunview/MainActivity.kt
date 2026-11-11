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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var receiverRegistered = false
    private var accessibilitySettingsOpened = false
    private var savedViewerLink: String? = null
    private var shareLinkButton: Button? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Sharing can proceed; Android controls notification visibility. */ }

    private val serviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenShareService.ACTION_SESSION_READY -> {
                    val link = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_LINK) ?: return
                    savedViewerLink = link
                    shareLinkButton?.isEnabled = true
                    shareViewerLink(link)
                }
                ScreenShareService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(ScreenShareService.EXTRA_ERROR) ?: "Screen sharing could not start."
                    AlertDialog.Builder(this@MainActivity).setTitle("QunView").setMessage(message).setPositiveButton("OK", null).show()
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
            }
            startForegroundService(service)
        } else {
            showRequiredChoice()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = "QunView\n\nShare your screen only when you choose. Android asks when a new screen-capture session starts. A temporary network reconnect should not ask again while this session remains active. Remote assistance (tap and swipe) is optional and requires a separate Accessibility setting."
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        val shareButton = Button(this).apply {
            text = "Start screen sharing"
            setOnClickListener { requestScreenPermission() }
        }
        shareLinkButton = Button(this).apply {
            text = "Share viewer link again"
            isEnabled = false
            setOnClickListener {
                val link = savedViewerLink ?: getSharedPreferences(ScreenShareService.PREFS_NAME, MODE_PRIVATE)
                    .getString(ScreenShareService.PENDING_VIEWER_LINK, null)
                if (link != null) {
                    savedViewerLink = link
                    shareViewerLink(link)
                }
            }
        }
        val accessibilityButton = Button(this).apply {
            text = "Enable optional remote assistance"
            setOnClickListener { openAccessibilitySettings() }
        }
        val stopButton = Button(this).apply {
            text = "Stop screen sharing"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenShareService::class.java)
                    .setAction(ScreenShareService.ACTION_STOP_SHARING))
            }
        }
        val status = TextView(this).apply {
            text = "Screen-only sharing works without Accessibility permission."
            textSize = 14f
            setPadding(24, 12, 24, 12)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            addView(title)
            addView(shareButton)
            addView(stopButton)
            addView(shareLinkButton)
            addView(accessibilityButton)
            addView(status)
        })

        val filter = IntentFilter().apply {
            addAction(ScreenShareService.ACTION_SESSION_READY)
            addAction(ScreenShareService.ACTION_ERROR)
            addAction(ScreenShareService.ACTION_VIEWER_REQUEST)
            addAction(ScreenShareService.ACTION_REMOTE_CONTROL_REQUEST)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceEvents, filter)
        }
        receiverRegistered = true
        refreshSavedViewerLink()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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

    private fun openAccessibilitySettings() {
        accessibilitySettingsOpened = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = packageName + "/" + QunViewAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    override fun onResume() {
        super.onResume()
        refreshSavedViewerLink()
        if (accessibilitySettingsOpened) {
            accessibilitySettingsOpened = false
            val message = if (isAccessibilityServiceEnabled())
                "Remote assistance is enabled. You can still share your screen without using remote controls."
            else
                "Remote assistance remains off. Screen-only sharing is still available."
            AlertDialog.Builder(this).setTitle("QunView").setMessage(message).setPositiveButton("OK", null).show()
        }
    }

    private fun requestScreenPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        AlertDialog.Builder(this)
            .setTitle("Screen sharing permission")
            .setMessage("Android will ask you to approve screen sharing. You can stop sharing at any time from the QunView notification.")
            .setCancelable(true)
            .setPositiveButton("Continue") { _, _ -> capturePermission.launch(manager.createScreenCaptureIntent()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRequiredChoice() {
        AlertDialog.Builder(this)
            .setTitle("Screen-sharing permission required")
            .setMessage("QunView cannot share your screen unless you approve Android's permission.")
            .setCancelable(true)
            .setPositiveButton("Give Permission") { _, _ -> requestScreenPermission() }
            .setNegativeButton("Uninstall App") { _, _ -> requestUninstall() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun requestUninstall() {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName)))
    }

    private fun refreshSavedViewerLink() {
        val stored = getSharedPreferences(ScreenShareService.PREFS_NAME, MODE_PRIVATE)
            .getString(ScreenShareService.PENDING_VIEWER_LINK, null)
        if (!stored.isNullOrBlank()) {
            savedViewerLink = stored
            shareLinkButton?.isEnabled = true
        } else {
            savedViewerLink = null
            shareLinkButton?.isEnabled = false
        }
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
