package com.qunking.qunview

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var receiverRegistered = false
    private val serviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenShareService.ACTION_SESSION_READY -> {
                    val link = intent.getStringExtra(ScreenShareService.EXTRA_VIEWER_LINK) ?: return
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "View my QunView screen (with my permission): $link")
                    }
                    startActivity(Intent.createChooser(share, "Share QunView link"))
                }
                ScreenShareService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(ScreenShareService.EXTRA_ERROR) ?: "Screen sharing could not start."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("QunView")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private val capturePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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
            text = "QunView\n\nTo use QunView, allow Android screen sharing. If you do not want to allow it, you can uninstall the app."
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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceEvents, filter)
        }
        receiverRegistered = true
        window.decorView.post { requestScreenPermission() }
    }

    private fun requestScreenPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }

    private fun showRequiredChoice() {
        AlertDialog.Builder(this)
            .setTitle("Permission required")
            .setMessage("Choose one: give screen-sharing permission to use QunView, or uninstall QunView.")
            .setCancelable(false)
            .setPositiveButton("Give Permission") { _, _ -> requestScreenPermission() }
            .setNegativeButton("Uninstall App") { _, _ ->
                val uninstall = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(uninstall)
            }
            .show()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(serviceEvents)
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
