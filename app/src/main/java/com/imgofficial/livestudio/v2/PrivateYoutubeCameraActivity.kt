package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.pedro.common.ConnectChecker
import com.pedro.library.generic.GenericStream

/**
 * Isolated V2 camera/uplink test. MainActivity, the existing HTML UI and
 * its signed Google login are unchanged. This Activity NEVER receives a key
 * from HTML, and cannot auto-publish a private YouTube broadcast.
 */
class PrivateYoutubeCameraActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
 private var publisher:GenericStream?=null
 private var oauth:NativeOAuthClient?=null
 private lateinit var monitor:SurfaceView
 private lateinit var status:TextView
 private lateinit var start:Button
 private lateinit var stop:Button
 private var ready=false
 private var connecting=false
 private var streaming=false
 // A background/permission transition must not consume the one-time private upload.
 private var uploadEnded=false
 private var uploadGeneration=0
 // RootEncoder's rotation=90 turns the 1280x720 source into a 720x1280
 // portrait H.264 stream. Match the physical preview to that 9:16 frame.
 private val portraitWidth=720
 private val portraitHeight=1280
 private val draftId by lazy { intent.getStringExtra("draftRequestId").orEmpty() }

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  // Only this isolated TEST Activity is portrait-locked. The hybrid V2
  // studio, other sports modes and navigation remain unchanged.
  requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  val bg=Color.rgb(12,17,24)
  val panel=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(bg)
   setPadding(20,24,20,24)
  }
  fun note(message:String,size:Float=15f):TextView=TextView(this).apply {
   text=message;textSize=size;setTextColor(Color.WHITE)
   setPadding(4,8,4,12)
  }
  panel.addView(note("IMG LIVE STUDIO • V2",14f))
  panel.addView(note("Private YouTube camera test",22f))
  panel.addView(note("Portrait video • 9:16 • 720×1280",13f))
  panel.addView(note("This is an isolated video-upload test. Only camera and microphone will be sent. Your existing V2 screens are unchanged. Uploading does not automatically make the YouTube event live.",13f))
  // Keep the preview a true 9:16 rectangle, centered inside its available
  // space. A MATCH_PARENT weighted SurfaceView can stretch the camera when
  // the parent aspect does not match the encoded video.
  val previewArea=FrameLayout(this).apply {setBackgroundColor(Color.BLACK)}
  panel.addView(previewArea,LinearLayout.LayoutParams(
   ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  monitor=SurfaceView(this)
  previewArea.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  previewArea.addOnLayoutChangeListener{_,left,top,right,bottom,_,_,_,_->
   val availableWidth=right-left
   val availableHeight=bottom-top
   if(availableWidth>0&&availableHeight>0){
    val targetWidth=minOf(availableWidth,availableHeight*portraitWidth/portraitHeight)
    val targetHeight=targetWidth*portraitHeight/portraitWidth
    val previous=monitor.layoutParams
    if(targetWidth>0&&targetHeight>0&&(previous.width!=targetWidth||previous.height!=targetHeight)){
     monitor.layoutParams=FrameLayout.LayoutParams(targetWidth,targetHeight,Gravity.CENTER)
    }
   }
  }
  monitor.holder.addCallback(this)
  status=note("Camera permission required",14f)
  panel.addView(status)
  start=Button(this).apply{
   text="START PRIVATE VIDEO UPLOAD"
   isEnabled=false
   setOnClickListener{startPrivateUpload()}
  }
  stop=Button(this).apply{
   text="STOP UPLOAD"
   isEnabled=false
   setOnClickListener{stopUpload()}
  }
  panel.addView(start)
  panel.addView(stop)
  panel.addView(Button(this).apply{text="BACK TO V2";setOnClickListener{finish()}})
  setContentView(panel)
  if(!Regex("^[a-f0-9]{32}$").matches(draftId)||
     BuildConfig.OAUTH_BACKEND_URL.isBlank()){
   status.text="A verified V2 private draft and OAuth test configuration are required."
   return
  }
  oauth=NativeOAuthClient(this,BuildConfig.OAUTH_BACKEND_URL)
  ensurePermissions()
 }

 private fun ensurePermissions() {
  val required=arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO)
  if(required.all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED})prepareCamera()
  else requestPermissions(required,640)
 }
 @Deprecated("Deprecated in Java")
 override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
  super.onRequestPermissionsResult(code,permissions,results)
  if(code==640){
   if(results.size==2&&results.all{it==PackageManager.PERMISSION_GRANTED})prepareCamera()
   else status.text="Allow camera and microphone access for the private video test."
  }
 }

 private fun prepareCamera() {
  if(ready)return
  try {
   val engine=GenericStream(this,this)
   // RootEncoder 2.8.1 encodes 720x1280 when the 1280x720 camera frame
   // is prepared with rotation=90. Rotation 0 produces landscape 1280x720
   // even while the phone itself is held vertically.
   if(!engine.prepareVideo(1280,720,2300000,30,2,90)||
      !engine.prepareAudio(44100,true,128000)){
    engine.release()
    status.text="This device cannot initialize the camera/audio encoder."
    return
   }
   publisher=engine
   ready=true
   status.text="Camera and microphone ready. No video has been sent."
   start.isEnabled=true
   if(monitor.holder.surface.isValid) {
    try{engine.startPreview(monitor)}catch(_:Exception){
     status.text="Camera preview unavailable on this phone."
    }
   }
  }catch(_:Exception){status.text="Camera initialization unavailable on this device."}
 }

 private fun startPrivateUpload() {
  if(!ready||connecting||streaming||uploadEnded)return
  val client=oauth?:return
  val request=++uploadGeneration
  connecting=true
  start.isEnabled=false
  stop.isEnabled=true // allow cancellation while the secure destination is prepared
  status.text="Preparing secure PRIVATE ingest with YouTube…"
  // The URL is handed directly to GenericStream and NEVER to JS, logs,
  // saved preferences, Intent extras, or a visible text field.
  client.preparePrivateIngest(draftId){url,error->
   runOnUiThread {
    // Invalidate delayed responses after STOP, navigation or backgrounding.
    // They must never start video transmission without the operator's presence.
    if(request!=uploadGeneration||uploadEnded||isFinishing||isDestroyed)return@runOnUiThread
    if(url==null){
     connecting=false
     stop.isEnabled=false
     start.isEnabled=ready
     status.text=error?:"Private ingest preparation failed."
     return@runOnUiThread
    }
    try {
     status.text="Connecting encrypted camera/video upload…"
     publisher?.startStream(url)
    }catch(_:Exception){
     stopUpload()
     status.text="Could not start RTMPS upload on this device."
    }
   }
  }
 }

 private fun stopUpload() {
  uploadGeneration++
  uploadEnded=true // a stopped/uncertain session must not reuse single-use ingest
  try{if(publisher?.isStreaming==true)publisher?.stopStream()}catch(_:Exception){}
  connecting=false;streaming=false
  stop.isEnabled=false
  start.isEnabled=false
  status.text="Video upload stopped. Verify the private event in YouTube Studio."
 }

 override fun onConnectionStarted(url:String) {
  runOnUiThread {
   if(!uploadEnded&&connecting)status.text="Connecting secure RTMPS upload…"
  }
 }
 override fun onConnectionSuccess() {
  runOnUiThread {
   if(uploadEnded||isFinishing||isDestroyed) {
    try{if(publisher?.isStreaming==true)publisher?.stopStream()}catch(_:Exception){}
    return@runOnUiThread
   }
   connecting=false;streaming=true
   stop.isEnabled=true
   start.isEnabled=false
   status.text="RTMPS upload accepted. Check YouTube Studio for video ingestion; this is NOT proof the event is live."
  }
 }
 override fun onConnectionFailed(reason:String) {
  runOnUiThread {stopUpload();status.text="RTMPS upload failed. Check network, device and YouTube Studio."}
 }
 override fun onDisconnect() {
  runOnUiThread {
   streaming=false;connecting=false
   stop.isEnabled=false;start.isEnabled=false
   status.text="Private video connection disconnected."
  }
 }
 override fun onAuthError(){runOnUiThread{stopUpload();status.text="YouTube rejected this private ingest destination."}}
 override fun onAuthSuccess(){}

 override fun surfaceCreated(holder:SurfaceHolder){
  if(ready)try{publisher?.startPreview(monitor)}catch(_:Exception){}
 }
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){
  if(ready&&width>0&&height>0)
   try{publisher?.getGlInterface()?.setPreviewResolution(width,height)}catch(_:Exception){}
 }
 override fun surfaceDestroyed(holder:SurfaceHolder){
  try{if(publisher?.isOnPreview==true)publisher?.stopPreview()}catch(_:Exception){}
 }

 override fun onResume(){
  super.onResume()
  // Permission sheets and ordinary app switching can invoke onStop before any
  // upload has begun. Keep the camera preview and Start control usable.
  if(ready&&!connecting&&!streaming&&!uploadEnded){
   start.isEnabled=true
   if(monitor.holder.surface.isValid && publisher?.isOnPreview!=true){
    try{publisher?.startPreview(monitor)}catch(_:Exception){
     status.text="Camera preview unavailable on this phone."
    }
   }
  }
 }

 override fun onStop(){
  // Only stop/consume a single-use ingest if an upload is actually underway.
  // Never label an idle, working camera preview as 'Video upload stopped'.
  if(connecting||streaming||publisher?.isStreaming==true)stopUpload()
  try{if(publisher?.isOnPreview==true)publisher?.stopPreview()}catch(_:Exception){}
  super.onStop()
 }
 override fun onDestroy(){
  try{publisher?.release()}catch(_:Exception){}
  publisher=null
  oauth?.close()
  window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  super.onDestroy()
 }
}
