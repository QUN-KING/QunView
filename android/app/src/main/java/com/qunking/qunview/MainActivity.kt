package com.qunking.qunview
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
class MainActivity:ComponentActivity(){
 private val requestCapture=1001
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val title=TextView(this).apply{text="QunView\n\nShare your screen only when you choose.";textSize=20f;setPadding(32,48,32,24)}
  val start=Button(this).apply{text="Start screen sharing";setOnClickListener{val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;startActivityForResult(manager.createScreenCaptureIntent(),requestCapture)}}
  setContentView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24);addView(title);addView(start)})
 }
 @Deprecated("Starter scaffold")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==requestCapture&&resultCode==Activity.RESULT_OK&&data!=null){
   val service=Intent(this,ScreenShareService::class.java).apply{putExtra(ScreenShareService.EXTRA_RESULT_CODE,resultCode);putExtra(ScreenShareService.EXTRA_RESULT_DATA,data)}
   startForegroundService(service)
  }
 }
}