package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.generic.GenericStream
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender

/**
 * V2 private broadcast lives in a user-started CAMERA+MICROPHONE foreground
 * service, not an Activity. A SurfaceView is optional; removing preview must
 * never stop the live video encoder. No endpoint or credentials are passed
 * to JavaScript, Intents, notification extras, or logs.
 *
 * Android/OEM restrictions can still interrupt capture when the device locks;
 * an RTMPS socket callback is not proof of healthy YouTube video ingestion.
 */
class V2BroadcastService:Service(),ConnectChecker {
 interface Listener {
  fun onBroadcastStatus(status:String,active:Boolean,connecting:Boolean,ended:Boolean,portrait:Boolean,front:Boolean)
  // Mirrors the same bitmap submitted to H.264's GL filter. UI receives
  // one frame only when score, clock or a finite transition actually changes.
  fun onPreviewGraphics(image:android.graphics.Bitmap,x:Float,y:Float,w:Float,h:Float){}
 }
 inner class LocalBinder:Binder(){ fun service():V2BroadcastService=this@V2BroadcastService }
 private val binder=LocalBinder()
 private val ui=Handler(Looper.getMainLooper())
 private var listeners=mutableSetOf<Listener>()
 private var stream:GenericStream?=null
 private var scoreboard=V2Scorecard.State()
 private var scoreFilter:ImageFilterRender?=null
 private var latestGraphics:android.graphics.Bitmap?=null
 private var lastGraphicsGeometry=floatArrayOf(8f,70f,84f,22f)
 private fun pushPreviewGraphics(image:android.graphics.Bitmap?=latestGraphics){
  if(image==null)return
  val pos=lastGraphicsGeometry
  listeners.toList().forEach{listener->try{
   listener.onPreviewGraphics(image,pos[0],pos[1],pos[2],pos[3])
  }catch(_:Exception){}}
 }
 private var scoreVisible=true
 private var scoreAlpha=1f
 private var lastScoreAlpha:Float?=null
 private var scoreTransitionEpoch=0
 private var graphicsStyle=0
 private var graphicsSize=60
 private var graphicsOpacity=1f
 private var graphicsWidth=100
 private var graphicsHeight=100
 private var graphicsX=0
 private var graphicsY=0
 private var graphicsMargin=3
 private var graphicsPosition="bottom-center"
 private var graphicsHomeLogo:android.graphics.Bitmap?=null
 private var graphicsAwayLogo:android.graphics.Bitmap?=null
 private var scoreEffectsEnabled=true
 private var scoreAward:V2Scorecard.Award?=null
 private var lastRenderedAward:V2Scorecard.Award?=null
 private var scoreAwardEpoch=0
 private var lastGraphicsStyle=-1
 private var lastScorecardImageState:V2Scorecard.State?=null
 private var lastScorecardVisible:Boolean?=null
 private var auth:NativeOAuthClient?=null
 private var preview:SurfaceView?=null
 private var wakeLock:PowerManager.WakeLock?=null
 private var generation=0
 private var attempted=false
 private var running=false
 private var connecting=false
 private var ended=false
 private var foreground=false
 private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
 private var networkReady=false
 private var networkLabel="Checking connection"
 private var networkTracking=false
 private var ingestDestination:String?=null // RAM only, never passed to Activity or logs
 private var recoveryAttempts=0
 private var connectedAtLeastOnce=false
 private var recoveryQueued=false
 // Prefer sustainable phone temperature over maximum quality.
 // Hardware H.264, 24 fps and lower GPU resolution reduce encode load.
 private var videoBitrate=1250000
 private val minBitrate=450000
 private val maxBitrate=1250000
 private var thermalBitrateCap=1250000
 private var thermalSeverity=0
 private var thermalMonitorRegistered=false
 private val thermalListener=if(android.os.Build.VERSION.SDK_INT>=29)
   PowerManager.OnThermalStatusChangedListener{state->
    ui.post{onThermalState(state)}
   } else null
 private fun onThermalState(state:Int){
  if(state==thermalSeverity)return
  thermalSeverity=state
  if(android.os.Build.VERSION.SDK_INT>=29 && state>=PowerManager.THERMAL_STATUS_CRITICAL){
   if(running||connecting)stopBroadcast()
   notifyState("Phone overheating • broadcast stopped to protect the device.")
   return
  }
  thermalBitrateCap=when{
   android.os.Build.VERSION.SDK_INT>=29 && state>=PowerManager.THERMAL_STATUS_SEVERE->550000
   android.os.Build.VERSION.SDK_INT>=29 && state>=PowerManager.THERMAL_STATUS_MODERATE->800000
   else->maxBitrate
  }
  if(videoBitrate>thermalBitrateCap){
   videoBitrate=thermalBitrateCap
   try{stream?.setVideoBitrateOnFly(videoBitrate)}catch(_:Exception){}
  }
  if(state>=2 && (running||connecting))
   notifyState("Phone warming • lowering upload quality to stay cooler.")
 }
 private var congestedSamples=0
 private var healthySamples=0
 private var previousDropped=0L
 private var previousUploadedBytes=0L
 private var stalledUploadSamples=0
 private var qualityPolling=false
 private val qualityTick=object:Runnable {
  override fun run(){
   qualityPolling=false
   if(ended||!running)return
   sampleUploadQuality()
   pollQuality()
  }
 }
 private val networkCallback=object:ConnectivityManager.NetworkCallback(){
  override fun onAvailable(network:Network){ui.post{refreshNetwork()}}
  override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){ui.post{refreshNetwork()}}
  override fun onLost(network:Network){ui.post{refreshNetwork()}}
 }
 private fun refreshNetwork(){
  val previouslyReady=networkReady
  val active=connectivity.activeNetwork
  val caps=active?.let{connectivity.getNetworkCapabilities(it)}
  networkReady=caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true &&
   caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  networkLabel=when{
   !networkReady->"Offline / waiting for internet"
   caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"Wi-Fi online"
   caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true->"Mobile data online"
   else->"Internet online"
  }
  if(!networkReady && (running||connecting)){
   notifyState("Connection lost • awaiting internet")
  }else if(networkReady && !previouslyReady && connecting && connectedAtLeastOnce && ingestDestination!=null){
   scheduleRecovery()
  }
  publish()
 }
 fun connectionSummary()=networkLabel+" • "+(videoBitrate/1000)+" kbps"
 private var portrait=false // Basketball/Pickleball V2 always broadcasts in landscape; Streetball portrait comes later.
 private var front=false
 private var title="Camera and microphone ready"
 private val channel="img_v2_broadcast"
 private val notificationId=7205
 private val actionActivate="com.imgofficial.livestudio.v2.BROADCAST_ACTIVATE"
 private val actionStop="com.imgofficial.livestudio.v2.BROADCAST_STOP"
 override fun onBind(intent:Intent?):IBinder=binder
 fun register(listener:Listener){listeners.add(listener);publish()}
 fun unregister(listener:Listener){listeners.remove(listener)}
 private fun publish(){listeners.toList().forEach{listener->
  try{listener.onBroadcastStatus(title,running,connecting,ended,portrait,front)}catch(_:Exception){}
 }}
 private fun notifyState(value:String){
  title=value
  if(foreground)try{
   // Notification is intentionally non-secret: never include an RTMPS URL.
   getSystemService(NotificationManager::class.java).notify(notificationId,notification())
  }catch(_:Exception){}
  publish()
 }
 fun isActive()=running||connecting
 fun isEnded()=ended
 fun isPortrait()=portrait
 fun frontCamera()=front
 fun getStatus()=title

 override fun onCreate(){
  super.onCreate()
  if(android.os.Build.VERSION.SDK_INT>=29){
   val manager=getSystemService(POWER_SERVICE) as PowerManager
   try{
    thermalMonitorRegistered=thermalListener?.let{manager.addThermalStatusListener(it);true}?:false
    onThermalState(manager.currentThermalStatus)
   }catch(_:Exception){thermalMonitorRegistered=false}
  }
  getSystemService(NotificationManager::class.java).createNotificationChannel(
   NotificationChannel(channel,"IMG V2 private broadcast",NotificationManager.IMPORTANCE_LOW)
  )
  try{
   connectivity.registerDefaultNetworkCallback(networkCallback)
   networkTracking=true
   refreshNetwork()
  }catch(_:Exception){ networkLabel="Network status unavailable" }
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  when(intent?.action) {
   actionStop -> {stopBroadcast();stopSelf();return START_NOT_STICKY}
   actionActivate -> {
    val hasPermissions=checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&
     checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    if(!hasPermissions){notifyState("Allow camera and microphone before uploading.");stopSelf();return START_NOT_STICKY}
    try {
     val notification=notification()
     if(Build.VERSION.SDK_INT>=29)startForeground(notificationId,notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
     else startForeground(notificationId,notification)
     foreground=true
    }catch(_:Exception){
     notifyState("Android blocked background camera permission. Return to V2 and retry.")
     stopSelf();return START_NOT_STICKY
    }
   }
  }
  return START_NOT_STICKY // never restart camera unattended after process death
 }
 private fun notification():Notification {
  val stop=PendingIntent.getService(this,1,Intent(this,V2BroadcastService::class.java).setAction(actionStop),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java).apply{
   flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
  },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,channel)
   .setSmallIcon(android.R.drawable.ic_menu_camera)
   .setContentTitle("IMG LIVE Studio V2 • Private video")
   .setContentText(title.take(90))
   .setContentIntent(open).setOngoing(true)
   .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
    "Stop Upload",stop).build()).build()
 }
 fun activateForeground():Boolean {
  refreshNetwork()
  if(!networkReady){notifyState("No verified internet. Connect Wi-Fi or mobile data first.");return false}
  if(!prepareCamera())return false
  if(foreground)return true
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||
     checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return false
  return try{
   // The button is pressed while V2 is visible; launch a started service so
   // it survives unbinding, then promote immediately before network work.
   androidx.core.content.ContextCompat.startForegroundService(this,
    Intent(this,V2BroadcastService::class.java).setAction(actionActivate))
   if(!foreground){
    val n=notification()
    if(Build.VERSION.SDK_INT>=29)startForeground(notificationId,n,
     ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    else startForeground(notificationId,n)
    foreground=true
   }
   true
  }catch(_:Exception){
   notifyState("Android could not start background camera service.")
   false
  }
 }

 fun prepareCamera():Boolean {
  if(stream!=null)return true
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||
     checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
   notifyState("Camera and microphone permissions are required.")
   return false
  }
  return try {
   val engine=GenericStream(this,this)
   // A stable landscape 16:9 H.264 frame; do not rotate it into portrait.
   // Streetball portrait uses a future separate mode/profile.
   if(!engine.prepareVideo(960,540,videoBitrate,24,2,0)||
      !engine.prepareAudio(44100,true,128000)){
    engine.release()
    notifyState("This phone cannot prepare the video/audio codec.")
    false
   }else{
    engine.getStreamClient().setReTries(5)
    engine.getStreamClient().setCheckServerAlive(true)
    engine.getStreamClient().setLogs(false)
    stream=engine
    applyFraming()
    applyScorecard()
    notifyState("Camera and microphone ready • landscape 16:9.")
    true
   }
  }catch(_:Exception){notifyState("Camera preparation failed on this device.");false}
 }

 fun attachPreview(surface:SurfaceView) {
  preview=surface
  if(!surface.holder.surface.isValid||!prepareCamera())return
  try{
   if(stream?.isOnPreview!=true)stream?.startPreview(surface)
   stream?.getGlInterface()?.setPreviewResolution(surface.width,surface.height)
   // The GL renderer may start after filters were configured. Reapply its
   // composed frame and geometry once the SurfaceView is actually attached.
   placeGraphics()
   if(scoreFilter!=null && latestGraphics==null)updateScorecard()
   pushPreviewGraphics()
  }catch(_:Exception){ notifyState("Preview unavailable; camera upload may still work.") }
 }
 fun detachPreview(){
  // StopPreview detaches only the rendered SurfaceView when isStreaming=true.
  // It must NOT release encoder, camera or microphone.
  if(stream?.isOnPreview==true)try{stream?.stopPreview()}catch(_:Exception){}
  preview=null
 }
 fun startPrivateUpload(draftId:String){
  if(!Regex("^[a-f0-9]{32}$").matches(draftId) || !foreground ||
     !prepareCamera()||attempted||ended||running||connecting){
   if(!foreground)notifyState("Broadcast service is not ready. Keep V2 open and tap Start.")
   return
  }
  if(!networkReady){notifyState("No internet connection. Connect Wi-Fi or mobile data first.");return}
  attempted=true
  connecting=true
  recoveryAttempts=0
  connectedAtLeastOnce=false
  ingestDestination=null
  val sequence=++generation
  notifyState("Preparing PRIVATE YouTube RTMPS upload…")
  if(auth==null)auth=NativeOAuthClient(this,BuildConfig.OAUTH_BACKEND_URL)
  auth?.preparePrivateIngest(draftId){url,error-> ui.post{
   if(sequence!=generation||ended||!connecting)return@post
   if(url==null){
    connecting=false
    // Only retry a rejected non-creating request by explicitly tapping Start.
    attempted=false
    notifyState(error?:"Private YouTube upload preparation failed.")
    // A failed preparation is not a Google sign-out. Keep this already-bound
    // service alive so the operator can retry the SAME private event without
    // orphaning the Activity binder or recreating an OAuth session.
    // Release only the foreground notification; camera preview may remain.
    if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false}
    return@post
   }
   try {
    val power=getSystemService(POWER_SERVICE) as PowerManager
    wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
     "com.imgofficial.livestudio.v2:privateBroadcast").apply{
     setReferenceCounted(false);acquire()
    }
    ingestDestination=url
    stream?.startStream(url)
    notifyState("Connecting private RTMPS; waiting for server…")
   }catch(_:Exception){
    notifyState("Camera/RTMPS start failed.")
    stopBroadcast()
   }
  }}
 }
 fun configureScorecard(sport:String,home:String,away:String){
  if(running||connecting)return // configure at game setup only
  scoreboard=V2Scorecard.State(
   sport=if(sport=="pickleball")"pickleball" else "basketball",
   home=home.trim().take(18).ifEmpty{"HOME"},
   away=away.trim().take(18).ifEmpty{"AWAY"}
  )
  updateScorecard()
 }
 internal fun scorecard():V2Scorecard.State=scoreboard.copy()
 fun scorecardVisible()=scoreVisible
 fun graphicsStyle()=graphicsStyle
 fun graphicsSize()=graphicsSize
 fun graphicsPosition()=graphicsPosition
 fun scoreEffectsEnabled()=scoreEffectsEnabled
 fun setScoreEffectsEnabled(enabled:Boolean){
  scoreEffectsEnabled=enabled
  if(!enabled){scoreAwardEpoch++;scoreAward=null;updateScorecard()}
 }
 fun setGraphicsStyle(style:Int){
  if(style !in 0..11 || graphicsStyle==style)return
  val oldCompact=V2BroadcastGraphicsRenderer.compact(graphicsStyle)
  val newCompact=V2BroadcastGraphicsRenderer.compact(style)
  graphicsStyle=style
  if(oldCompact!=newCompact)
   graphicsPosition=if(newCompact)"bottom-right" else "bottom-center"
  lastGraphicsStyle=-1
  placeGraphics()
  updateScorecard()
 }
 fun configureGraphics(style:Int,position:String,size:Int,opacity:Int,
  width:Int,height:Int,x:Int,y:Int,margin:Int,effects:Boolean,
  homeLogo:android.graphics.Bitmap?,awayLogo:android.graphics.Bitmap?){
  if(style in 0..11)graphicsStyle=style
  graphicsPosition=if(position in setOf("top-left","top-right","bottom-left","bottom-right","bottom-center"))
   position else if(V2BroadcastGraphicsRenderer.compact(graphicsStyle))"bottom-right" else "bottom-center"
  graphicsSize=size.coerceIn(15,115)
  graphicsOpacity=opacity.coerceIn(30,100)/100f
  graphicsWidth=width.coerceIn(65,150)
  graphicsHeight=height.coerceIn(65,150)
  graphicsX=x.coerceIn(-30,30);graphicsY=y.coerceIn(-30,30)
  graphicsMargin=margin.coerceIn(1,10)
  scoreEffectsEnabled=effects
  graphicsHomeLogo=homeLogo
  graphicsAwayLogo=awayLogo
  lastGraphicsStyle=-1
  placeGraphics();updateScorecard()
 }
 fun resizeGraphics(amount:Int){
  graphicsSize=(graphicsSize+amount).coerceIn(15,115)
  placeGraphics()
 }
 fun placeGraphicsAt(position:String){
  if(position in setOf("top-left","top-right","bottom-left","bottom-right","bottom-center")){
   graphicsPosition=position;placeGraphics()
  }
 }
 private fun placeGraphics(){
  val filter=scoreFilter?:return
  val compact=V2BroadcastGraphicsRenderer.compact(graphicsStyle)
  // Maintain natural artwork aspect ratio under every resize setting.
  val overall=(graphicsSize/60f)*minOf(graphicsWidth,graphicsHeight)/100f
  var pw=(if(compact)31f else 84f)*overall
  var ph=pw*(960f/540f)*
   V2BroadcastGraphicsRenderer.height(graphicsStyle)/
   V2BroadcastGraphicsRenderer.width(graphicsStyle)
  val factor=minOf(1f,85f/ph,95f/pw)
  pw*=factor;ph*=factor
  val safe=graphicsMargin.toFloat()
  val px=when(graphicsPosition){
   "top-left","bottom-left"->safe
   "top-right","bottom-right"->100f-pw-safe
   else->(100f-pw)/2f
  }
  val py=if(graphicsPosition.startsWith("top"))safe else 100f-ph-safe
  try{
   val finalX=(px+graphicsX).coerceIn(safe,100f-pw-safe)
   val finalY=(py+graphicsY).coerceIn(safe,100f-ph-safe)
   filter.setScale(pw,ph)
   filter.setPosition(finalX,finalY)
   lastGraphicsGeometry=floatArrayOf(finalX,finalY,pw,ph)
   pushPreviewGraphics()
  }catch(_:Exception){notifyState("Broadcast Graphics position could not be changed.")}
 }
 private fun startScoreAward(team:Int,points:Int,prior:Int){
  val epoch=++scoreAwardEpoch
  scoreAward=V2Scorecard.Award(team,points,0f,prior)
  updateScorecard()
  // A limited 15-frame sequence over 1.68 seconds follows the broadcast
  // reference, with an identity wipe and held points-award takeover.
  // No continuous animation loop: this minimizes mobile GPU heat.
  for(frame in 1..15){
   ui.postDelayed({
    if(epoch!=scoreAwardEpoch)return@postDelayed
    scoreAward=if(frame==15)null else
     V2Scorecard.Award(team,points,frame/15f,prior)
    updateScorecard()
   },frame*112L)
  }
 }
 fun adjustScore(team:Int,delta:Int){
  if(team !in 0..1 || delta !in -3..3 || delta==0)return
  val prior=if(team==0)scoreboard.homeScore else scoreboard.awayScore
  scoreboard=if(team==0)scoreboard.copy(homeScore=(scoreboard.homeScore+delta).coerceIn(0,999))
   else scoreboard.copy(awayScore=(scoreboard.awayScore+delta).coerceIn(0,999))
  val changed=(if(team==0)scoreboard.homeScore else scoreboard.awayScore)-prior
  if(changed>0 && scoreboard.sport=="basketball" && scoreEffectsEnabled){
   startScoreAward(team,changed,prior)
  }else{
   scoreAwardEpoch++
   scoreAward=null
   updateScorecard()
  }
 }
 fun addFoul(team:Int){adjustFoul(team,1)}
 fun adjustFoul(team:Int,delta:Int){
  if(scoreboard.sport!="basketball"||team !in 0..1)return
  scoreboard=if(team==0)scoreboard.copy(homeFouls=(scoreboard.homeFouls+delta).coerceIn(0,99))
   else scoreboard.copy(awayFouls=(scoreboard.awayFouls+delta).coerceIn(0,99))
  updateScorecard()
 }
 fun nextPeriod(){adjustPeriod(1)}
 // Direct quarter/game selection without repeated GL uploads.
 fun selectPeriod(value:Int){
  if(value !in 1..9 || value==scoreboard.period)return
  val previous=scoreboard.period
  scoreboard=scoreboard.copy(
   period=value,
   homeFouls=if(value>previous&&scoreboard.sport=="basketball")0 else scoreboard.homeFouls,
   awayFouls=if(value>previous&&scoreboard.sport=="basketball")0 else scoreboard.awayFouls
  )
  updateScorecard()
 }
 fun adjustPeriod(delta:Int){
  val previous=scoreboard.period
  val updated=(previous+delta).coerceIn(1,9)
  scoreboard=scoreboard.copy(period=updated,
   homeFouls=if(updated!=previous&&delta>0&&scoreboard.sport=="basketball")0 else scoreboard.homeFouls,
   awayFouls=if(updated!=previous&&delta>0&&scoreboard.sport=="basketball")0 else scoreboard.awayFouls)
  updateScorecard()
 }
 fun setClock(value:String){
  if(value.isNotBlank() && !Regex("^\\d{1,2}:\\d{2}$").matches(value))return
  scoreboard=scoreboard.copy(clock=value.take(5))
  updateScorecard()
 }
 fun setScorecardVisible(visible:Boolean){
  if(scoreVisible==visible)return
  scoreVisible=visible
  val beginning=scoreAlpha
  val finish=if(visible)1f else 0f
  val epoch=++scoreTransitionEpoch
  // Four 60ms steps. Camera and audio keep rendering continuously.
  // It is a real GL overlay fade, visible in both preview and output.
  for(step in 1..4){
   ui.postDelayed({
    if(epoch!=scoreTransitionEpoch || ended)return@postDelayed
    val t=step/4f
    val ease=t*t*(3f-2f*t)
    scoreAlpha=beginning+(finish-beginning)*ease
    updateScorecard()
   },step*60L)
  }
 }
 private fun applyScorecard(){
  val gl=stream?.getGlInterface()?:return
  try{
   if(scoreFilter==null){
    scoreFilter=ImageFilterRender().also{gl.addFilter(it)}
   }
   lastScorecardImageState=null
   lastScorecardVisible=null
   lastScoreAlpha=null
   lastRenderedAward=null
   lastGraphicsStyle=-1
   updateScorecard()
  }catch(_:Exception){notifyState("Video scoreboard overlay unavailable on this phone.")}
 }
 private fun updateScorecard(){
  val filter=scoreFilter?:return
  if(lastScoreAlpha==scoreAlpha && lastRenderedAward==scoreAward &&
      lastGraphicsStyle==graphicsStyle && lastScorecardImageState==scoreboard)return
  try{
   // Never pass null to setImage: in the native GL renderer a missing
   // texture can leave an opaque dark/black rectangle over the camera.
   // Transparent frames are rendered for hide and fade transitions.
   val picture=V2BroadcastGraphicsRenderer.render(
    scoreboard,scoreAlpha*graphicsOpacity,scoreAward,graphicsStyle,graphicsHomeLogo,graphicsAwayLogo)
   // Upload first, then scale: image sprite dimensions can be reset on
   // texture replacement in the GPU filter lifecycle.
   filter.setImage(picture)
   placeGraphics()
   latestGraphics=picture
   pushPreviewGraphics(picture)
   lastScorecardImageState=scoreboard.copy()
   lastRenderedAward=scoreAward
   lastGraphicsStyle=graphicsStyle
   lastScorecardVisible=scoreVisible
   lastScoreAlpha=scoreAlpha
   // No keyframe request for score changes or clock ticks.
  }catch(_:Exception){notifyState("Unable to redraw native video scoreboard.")}
 }
 fun flipCamera(){
  try{
   val camera=stream?.videoSource as? Camera2Source?:return
   camera.switchCamera()
   front=!front
   stream?.requestKeyframe()
   notifyState(if(front)"Front camera selected" else "Rear camera selected")
  }catch(_:Exception){notifyState("Unable to switch cameras without losing capture.")}
 }
 fun changeFraming(usePortrait:Boolean){
  // For compatibility with already-installed V2 Activity calls. Portrait
  // is deliberately unavailable until a future Streetball encoder profile.
  if(portrait || usePortrait){
   portrait=false
   applyFraming()
  }
  notifyState("Landscape broadcast • 16:9")
 }
 private fun applyFraming(){
  stream?.getGlInterface()?.apply{
   setIsPortrait(false)
   // Camera sensor is 90 degrees relative to a landscape phone; RootEncoder
   // renders the landscape camera through this GL orientation.
   setCameraOrientation(270)
  }
 }
 private fun pollQuality(){
  if(!qualityPolling && running && !ended){
   qualityPolling=true
   ui.postDelayed(qualityTick,5000)
  }
 }
 private fun sampleUploadQuality(){
  val engine=stream?:return
  val client=engine.getStreamClient()
  val dropped=client.getDroppedVideoFrames()
  val increase=(dropped-previousDropped).coerceAtLeast(0L)
  previousDropped=dropped
  // Transport can stall during Wi-Fi -> mobile-data handover without
  // immediately invoking onConnectionFailed. Detect stalled real uploads
  // (not just low signal strength) before attempting the same RTMPS URL.
  val uploadedBytes=client.getBytesSend()
  if(networkReady && uploadedBytes<=previousUploadedBytes)stalledUploadSamples++
  else stalledUploadSamples=0
  previousUploadedBytes=uploadedBytes
  if(stalledUploadSamples>=3){
   running=false;connecting=true
   ui.removeCallbacks(qualityTick)
   qualityPolling=false
   notifyState("Video upload stalled • recovering same private feed")
   scheduleRecovery()
   return
  }
  val congested=client.hasCongestion(35f)||increase>=3
  if(!networkReady || congested){
   congestedSamples++
   healthySamples=0
   if(congestedSamples>=2){
    val reduced=(videoBitrate*0.72).toInt().coerceAtLeast(minBitrate)
    if(reduced<videoBitrate){
     videoBitrate=reduced
     try{engine.setVideoBitrateOnFly(videoBitrate)}catch(_:Exception){}
     notifyState("Weak upload • reducing video to "+(videoBitrate/1000)+" kbps")
    }
    congestedSamples=0
   }
  }else{
   congestedSamples=0
   healthySamples++
   // Increase slowly only after sustained good RTMPS queue behaviour.
   if(healthySamples>=5&&videoBitrate<thermalBitrateCap){
    videoBitrate=(videoBitrate+100000).coerceAtMost(thermalBitrateCap)
    try{engine.setVideoBitrateOnFly(videoBitrate)}catch(_:Exception){}
    notifyState("Upload improved • video "+(videoBitrate/1000)+" kbps")
    healthySamples=0
   }
  }
  publish()
 }
 private fun scheduleRecovery(){
  if(ended||!connecting||!networkReady||recoveryQueued||ingestDestination==null)return
  if(recoveryAttempts>=5){
   stopBroadcast()
   notifyState("Recovery limit reached; private upload stopped safely.")
   return
  }
  val attempt=++recoveryAttempts
  val epoch=generation
  recoveryQueued=true
  val delay=when(attempt){1->3000L;2->6000L;3->12000L;4->24000L;else->30000L}
  notifyState("Recovering private feed • attempt "+attempt+"/5")
  ui.postDelayed({
   recoveryQueued=false
   if(epoch!=generation||ended||!connecting||running)return@postDelayed
   if(!networkReady){
    notifyState("Waiting for internet • original private feed retained")
    return@postDelayed
   }
   val original=ingestDestination?:return@postDelayed
   try{
    // Recover the ORIGINAL RTMPS endpoint, not a new YouTube draft.
    // A transport restart may cause a brief gap after an actual outage.
    if(stream?.isStreaming==true)stream?.stopStream()
    stream?.startStream(original)
    notifyState("Reconnecting to existing private YouTube stream…")
   }catch(_:Exception){scheduleRecovery()}
  },delay)
 }
 fun stopBroadcast(){
  generation++
  connecting=false;running=false;ended=true
  ingestDestination=null
  recoveryQueued=false
  ui.removeCallbacks(qualityTick)
  scoreTransitionEpoch++
  scoreAwardEpoch++
  scoreAward=null
  qualityPolling=false
  try{if(stream?.isStreaming==true)stream?.stopStream()}catch(_:Exception){}
  try{if(stream?.isOnPreview==true)stream?.stopPreview()}catch(_:Exception){}
  try{stream?.release()}catch(_:Exception){}
  stream=null
  scoreFilter=null
  latestGraphics=null
  lastScoreAlpha=null
  try{if(wakeLock?.isHeld==true)wakeLock?.release()}catch(_:Exception){}
  wakeLock=null
  notifyState("Upload stopped. Your YouTube account remains connected.")
  if(foreground){
   stopForeground(STOP_FOREGROUND_REMOVE)
   foreground=false
   stopSelf() // No invisible microphone/camera service after explicit Stop
  }
 }
 override fun onConnectionStarted(url:String){ui.post{if(!ended)notifyState("Connecting secure RTMPS…")}}
 override fun onConnectionSuccess(){ui.post{
  if(ended)return@post
  connecting=false;running=true
  connectedAtLeastOnce=true
  recoveryAttempts=0
  recoveryQueued=false
  healthySamples=0;congestedSamples=0
  previousDropped=stream?.getStreamClient()?.getDroppedVideoFrames()?:0L
  previousUploadedBytes=stream?.getStreamClient()?.getBytesSend()?:0L
  stalledUploadSamples=0
  pollQuality()
  notifyState("RTMPS accepted • check YouTube Studio Stream health")
 }}
 override fun onConnectionFailed(reason:String){ui.post{
  if(ended)return@post
  running=false;connecting=true
  ui.removeCallbacks(qualityTick);qualityPolling=false
  if(networkReady&&stream?.isStreaming==true&&
   stream?.getStreamClient()?.reTry(2500,reason)==true){
   notifyState("Signal interrupted • retrying existing private stream")
  }else if(networkReady){
   scheduleRecovery()
  }else notifyState("No internet • waiting to reconnect private feed")
 }}
 override fun onDisconnect(){ui.post{
  if(!ended&&running){
   running=false;connecting=true
   ui.removeCallbacks(qualityTick);qualityPolling=false
   if(networkReady)scheduleRecovery()
   else notifyState("RTMPS disconnected • waiting for internet")
  }
 }}
 override fun onAuthError(){ui.post{stopBroadcast();notifyState("YouTube rejected the private RTMPS upload.")}}
 override fun onAuthSuccess(){}
 override fun onDestroy(){
  ui.removeCallbacks(qualityTick)
  scoreTransitionEpoch++
  if(thermalMonitorRegistered && android.os.Build.VERSION.SDK_INT>=29){
   val manager=getSystemService(POWER_SERVICE) as PowerManager
   try{thermalListener?.let{manager.removeThermalStatusListener(it)}}catch(_:Exception){}
   thermalMonitorRegistered=false
  }
  if(networkTracking)try{connectivity.unregisterNetworkCallback(networkCallback)}catch(_:Exception){}
  networkTracking=false
  stopBroadcast()
  detachPreview()
  try{stream?.release()}catch(_:Exception){}
  stream=null;auth?.close();auth=null;listeners.clear()
  super.onDestroy()
 }
}