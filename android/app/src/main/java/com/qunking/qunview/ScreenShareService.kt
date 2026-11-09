package com.qunking.qunview
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
class ScreenShareService:Service(){
 private var projection:MediaProjection?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  createNotificationChannel();startForeground(NOTIFICATION_ID,buildNotification())
  val resultCode=intent?.getIntExtra(EXTRA_RESULT_CODE,0)?:0
  val resultData=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
  if(resultData==null){stopSelf();return START_NOT_STICKY}
  val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  projection=manager.getMediaProjection(resultCode,resultData)
  // TODO: Register MediaProjection.Callback, create VirtualDisplay, and connect its Surface to WebRTC.
  return START_NOT_STICKY
 }
 override fun onDestroy(){projection?.stop();projection=null;super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
 private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){val c=NotificationChannel(CHANNEL_ID,"QunView screen sharing",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager::class.java).createNotificationChannel(c)}}
 private fun buildNotification():Notification{val b=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)Notification.Builder(this,CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this);return b.setContentTitle("QunView is sharing your screen").setContentText("Stop sharing by closing QunView.").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()}
 companion object{const val EXTRA_RESULT_CODE="result_code";const val EXTRA_RESULT_DATA="result_data";private const val CHANNEL_ID="qunview_screen_share";private const val NOTIFICATION_ID=41}
}