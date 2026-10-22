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

 private val isLandscapeMode: Boolean
  get()=intent.getStringExtra("img_video_mode")=="landscape"
 private val draftId by lazy { intent.getStringExtra("draftRequestId").orEmpty() }

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val landscape=isLandscapeMode
  // Rotate only the isolated V2 camera test Activity, never the hybrid studio.
  // Switching is available BEFORE upload, not while video is being published.
  requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  val bg=Color.rgb(12,17,24)
  val dp=resources.displayMetrics.density
  fun px(value:Int)=(value*dp+0.5f).toInt()
  fun note(message:String,size:Float=15f):TextView=TextView(this).apply {
   text=message;textSize=size;setTextColor(Color.WHITE)
   setPadding(px(4),px(4),px(4),px(7))
  }
  val panel=LinearLayout(this).apply {
   orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
   setBackgroundColor(bg)
   setPadding(px(10),px(10),px(10),px(10))
  }
  val side=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setPadding(px(4),px(2),px(4),px(2))
  }
  side.addView(note("IMG LIVE STUDIO • V2",13f))
  side.addView(note("Private YouTube camera test",if(landscape)18f else 22f))
  val orientationLabel=note(
   if(landscape)"LANDSCAPE • 16:9 • 1280 × 720"
   else "PORTRAIT • 9:16 • 720 × 1280",13f
  )
  side.addView(orientationLabel)
  if(!landscape)side.addView(note(
   "Private YouTube camera and microphone upload only. No public Go Live and no changes to your V2 studio.",12f
  ))
  val modes=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
  val portraitButton=Button(this).apply {
   text=if(landscape)"Portrait" else "✓ Portrait"
   textSize=12f
   setAllCaps(false)
   setOnClickListener { changeTestMode(false) }
  }
  val landscapeButton=Button(this).apply {
   text=if(landscape)"✓ Landscape" else "Landscape"
   textSize=12f
   setAllCaps(false)
   setOnClickListener { changeTestMode(true) }
  }
  modes.addView(portraitButton,LinearLayout.LayoutParams(0,px(45),1f))
  modes.addView(landscapeButton,LinearLayout.LayoutParams(0,px(45),1f))
  side.addView(modes)
  // The live video SurfaceView is centered at the encoded frame's ratio.
  // Black margins are intentional; stretching the video is not.
  val videoWidth=if(landscape)1280 else 720
  val videoHeight=if(landscape)720 else 1280
  val previewArea=FrameLayout(this).apply {setBackgroundColor(Color.BLACK)}
  monitor=SurfaceView(this)
  previewArea.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  previewArea.addOnLayoutChangeListener{_,left,top,right,bottom,_,_,_,_->
   val availableWidth=right-left
   val availableHeight=bottom-top
   if(availableWidth>0&&availableHeight>0){
    val targetWidth=minOf(availableWidth,availableHeight*videoWidth/videoHeight)
    val targetHeight=targetWidth*videoHeight/videoWidth
    val old=monitor.layoutParams
    if(targetWidth>0&&targetHeight>0&&(old.width!=targetWidth||old.height!=targetHeight)){
     monitor.layoutParams=FrameLayout.LayoutParams(targetWidth,targetHeight,Gravity.CENTER)
    }
   }
  }
  monitor.holder.addCallback(this)
  status=note("Camera permission required",13f)
  start=Button(this).apply{
   text="START PRIVATE VIDEO UPLOAD"
   textSize=12f
   setAllCaps(false)
   isEnabled=false
   setOnClickListener{startPrivateUpload()}
  }
  stop=Button(this).apply{
   text="STOP UPLOAD"
   textSize=12f
   setAllCaps(false)
   isEnabled=false
   setOnClickListener{stopUpload()}
  }
  side.addView(status)
  side.addView(start)
  side.addView(stop)
  side.addView(Button(this).apply{
   text="BACK TO V2"
   textSize=12f
   setAllCaps(false)
   setOnClickListener{finish()}
  })
  if(landscape){
   panel.addView(previewArea,LinearLayout.LayoutParams(0,
    ViewGroup.LayoutParams.MATCH_PARENT,1f))
   panel.addView(side,LinearLayout.LayoutParams(px(255),
    ViewGroup.LayoutParams.MATCH_PARENT))
  }else{
   panel.addView(side,LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
   // Portrait keeps controls reachable. The scaled preview must not stretch.
   panel.addView(previewArea,1,LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  }
  setContentView(panel)
  if(!Regex("^[a-f0-9]{32}$").matches(draftId)||
     BuildConfig.OAUTH_BACKEND_URL.isBlank()){
   status.text="A verified V2 private draft and OAuth test configuration are required."
   return
  }
  oauth=NativeOAuthClient(this,BuildConfig.OAUTH_BACKEND_URL)
  ensurePermissions()
 }

 private fun changeTestMode(landscape:Boolean) {
  if(connecting||streaming||publisher?.isStreaming==true){
   status.text="Stop uploading before changing video orientation."
   return
  }
  if(isLandscapeMode==landscape)return
  // Preserve the original private draft, V2 account session and Android UI.
  // Intent mode survives the Activity recreation caused by screen rotation.
  intent.putExtra("img_video_mode",if(landscape)"landscape" else "portrait")
  requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
   // A 1280x720 camera buffer uses rotation=90 for genuine 720x1280
   // portrait video and rotation=0 for 1280x720 landscape output.
   val videoRotation=if(isLandscapeMode)0 else 90
   if(!engine.prepareVideo(1280,720,2300000,30,2,videoRotation)||
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
