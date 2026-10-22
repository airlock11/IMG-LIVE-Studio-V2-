package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * IMG V2 private video operator view. The foreground V2BroadcastService owns
 * camera, microphone, encoder and RTMPS; this Activity only attaches preview
 * and controls it. Screen-off, Home and configuration changes must not stop it.
 */
class PrivateYoutubeCameraActivity:Activity(),SurfaceHolder.Callback,V2BroadcastService.Listener {
 private var broadcast:V2BroadcastService?=null
 private var bound=false
 private var resumed=false
 private lateinit var monitor:SurfaceView
 private lateinit var status:TextView
 private lateinit var start:Button
 private lateinit var stop:Button
 private lateinit var portraitButton:Button
 private lateinit var landscapeButton:Button
 private lateinit var flipButton:Button
 private val draftId by lazy {intent.getStringExtra("draftRequestId").orEmpty()}
 private val conn=object:ServiceConnection {
  override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
   broadcast=(binder as? V2BroadcastService.LocalBinder)?.service()
   broadcast?.register(this@PrivateYoutubeCameraActivity)
   if(checkPermissions()) {
    broadcast?.prepareCamera()
    if(resumed&&monitor.holder.surface.isValid)broadcast?.attachPreview(monitor)
   }
   refresh()
  }
  override fun onServiceDisconnected(name:ComponentName?){
   broadcast=null;status.text="Broadcast service disconnected."
   refresh()
  }
 }
 private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
 private fun text(value:String,size:Float=14f)=TextView(this).apply {
  text=value;textSize=size;setTextColor(Color.WHITE)
  setPadding(dp(4),dp(4),dp(4),dp(6))
 }
 private fun action(name:String,run:()->Unit)=Button(this).apply {
  text=name;textSize=12f;isAllCaps=false
  setOnClickListener{run()}
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val landscape=intent.getStringExtra("img_video_mode")=="landscape"
  requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  val panel=LinearLayout(this).apply {
   orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(12,17,24))
   setPadding(dp(10),dp(10),dp(10),dp(10))
  }
  val controls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
  controls.addView(text("IMG LIVE STUDIO • V2",13f))
  controls.addView(text("Private YouTube camera test",if(landscape)17f else 21f))
  controls.addView(text("Portrait / Landscape LIVE • stable 9:16 video output",12f))
  val choices=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
  portraitButton=action("Portrait"){changeFraming(true)}
  landscapeButton=action("Landscape"){changeFraming(false)}
  choices.addView(portraitButton,LinearLayout.LayoutParams(0,dp(45),1f))
  choices.addView(landscapeButton,LinearLayout.LayoutParams(0,dp(45),1f))
  controls.addView(choices)
  flipButton=action("Flip front / rear camera"){broadcast?.flipCamera()}
  controls.addView(flipButton)
  status=text("Connecting private camera controls…",13f)
  controls.addView(status)
  start=action("START PRIVATE VIDEO UPLOAD"){
   if(!checkPermissions())return@action
   val service=broadcast
   if(service==null)status.text="Camera service is not ready."
   else if(!service.activateForeground())
    status.text="Android could not start the camera foreground service."
   else service.startPrivateUpload(draftId)
   refresh()
  }
  stop=action("STOP UPLOAD"){broadcast?.stopBroadcast();refresh()}
  controls.addView(start)
  controls.addView(stop)
  controls.addView(action("BACK TO V2"){finish()})
  val area=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
  monitor=SurfaceView(this)
  area.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  // The entire RTMPS upload is 9:16 for this test. In landscape framing
  // the camera is letterboxed into that unchanged stream size. No stretching.
  area.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
   val maxW=r-l
   val maxH=b-t
   if(maxW>0&&maxH>0){
    val w=minOf(maxW,maxH*9/16)
    val h=w*16/9
    val previous=monitor.layoutParams
    if(w>0&&h>0&&(previous.width!=w||previous.height!=h))
     monitor.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
   }
  }
  monitor.holder.addCallback(this)
  if(landscape){
   panel.addView(area,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
   panel.addView(controls,LinearLayout.LayoutParams(dp(275),ViewGroup.LayoutParams.MATCH_PARENT))
  }else{
   panel.addView(controls)
   panel.addView(area,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  }
  setContentView(panel)
  if(!Regex("^[a-f0-9]{32}$").matches(draftId)||BuildConfig.OAUTH_BACKEND_URL.isBlank()){
   status.text="Choose a verified private V2 draft from Connected Accounts."
   start.isEnabled=false
   return
  }
  val serviceIntent=Intent(this,V2BroadcastService::class.java)
  bound=bindService(serviceIntent,conn,Context.BIND_AUTO_CREATE)
  if(!checkPermissions())requestPermissions(arrayOf(Manifest.permission.CAMERA,
   Manifest.permission.RECORD_AUDIO),640)
  refresh()
 }
 private fun checkPermissions():Boolean =
  checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
  checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED

 @Deprecated("Deprecated in Java")
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,results:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,results)
  if(requestCode==640){
   if(checkPermissions()){
    broadcast?.prepareCamera()
    if(resumed&&monitor.holder.surface.isValid)broadcast?.attachPreview(monitor)
   }else status.text="Allow camera and microphone access to start your PRIVATE test."
   refresh()
  }
 }
 private fun refresh(){
  val service=broadcast
  if(!::start.isInitialized)return
  val usable=service!=null&&checkPermissions()
  start.isEnabled=usable&&service?.isActive()!=true&&service?.isEnded()!=true
  stop.isEnabled=service?.isActive()==true
  portraitButton.isEnabled=usable
  landscapeButton.isEnabled=usable
  flipButton.isEnabled=usable
  if(service!=null) {
   status.text=service.getStatus()
   portraitButton.text=if(service.isPortrait())"✓ Portrait" else "Portrait"
   landscapeButton.text=if(service.isPortrait())"Landscape" else "✓ Landscape"
   flipButton.text=if(service.frontCamera())"Switch to rear camera" else "Switch to front camera"
  }
 }
 private fun changeFraming(portrait:Boolean){
  val service=broadcast?:return
  service.changeFraming(portrait)
  val target=if(portrait)"portrait" else "landscape"
  intent.putExtra("img_video_mode",target)
  val newOrientation=if(portrait)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
   else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  if(requestedOrientation!=newOrientation)requestedOrientation=newOrientation
  refresh()
 }
 override fun onBroadcastStatus(status:String,active:Boolean,connecting:Boolean,
    ended:Boolean,portrait:Boolean,front:Boolean){runOnUiThread{refresh()}}
 override fun surfaceCreated(holder:SurfaceHolder){
  if(resumed&&checkPermissions())broadcast?.attachPreview(monitor)
 }
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){
  if(width>0&&height>0)
   broadcast?.let{
    // The service continues encoding when this surface disappears.
    // Its renderer updates preview dimensions only, not encoder dimensions.
    if(monitor.holder.surface.isValid&&resumed)it.attachPreview(monitor)
   }
 }
 override fun surfaceDestroyed(holder:SurfaceHolder){broadcast?.detachPreview()}
 override fun onResume(){
  super.onResume()
  resumed=true
  if(checkPermissions()&&::monitor.isInitialized&&monitor.holder.surface.isValid)
   broadcast?.attachPreview(monitor)
 }
 override fun onStop(){
  resumed=false
  // Detach only the screen's surface. NEVER stop the foreground encoder.
  broadcast?.detachPreview()
  super.onStop()
 }
 override fun onDestroy(){
  if(bound){
   broadcast?.unregister(this)
   broadcast?.detachPreview()
   unbindService(conn)
   bound=false
  }
  broadcast=null
  window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  super.onDestroy()
 }
}