package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
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
 private val draftId by lazy { intent.getStringExtra("draftRequestId").orEmpty() }

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
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
  panel.addView(note("This is an isolated video-upload test. Only camera and microphone will be sent. Your existing V2 screens are unchanged. Uploading does not automatically make the YouTube event live.",13f))
  monitor=SurfaceView(this)
  panel.addView(monitor,LinearLayout.LayoutParams(
   ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
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
   if(!engine.prepareVideo(1280,720,2300000,30,2,0)||
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
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}
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
