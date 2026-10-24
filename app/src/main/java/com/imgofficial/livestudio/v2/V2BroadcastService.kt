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
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import android.media.MediaCodecList
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.graphics.SurfaceTexture
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
 private val replay=V2ReplayManager(this){message->notifyState(message)}
 fun replayReady()=replay.ready()&&(running||(offlineRecording&&!offlinePaused))
 fun instantReplayIsActive()=replay.active
 fun instantReplayModeLocked()=replay.isModeLocked()||replayLayoutRestoring
 fun instantReplayIsFull()=replay.fullActive()
 fun instantReplayIsPrepared()=replay.isPrepared()
 fun instantReplayDurationMs()=replay.durationMs()
 fun prepareInstantReplay(result:(Boolean)->Unit)=replay.prepare(
  running||(offlineRecording&&!offlinePaused),result)
 fun attachPrivateReplayPreview(surface:android.view.Surface)=replay.attachPrivatePreview(surface)
 fun detachPrivateReplayPreview()=replay.detachPrivatePreview()
 fun startInstantReplay(mode:Int):Boolean{
  if(instantReplayModeLocked())return false
  if(mode==V2ReplayFilter.FULL){
   replay.setOnAirStartedListener {
    replayLayoutEpoch++
    replayScreenMode=V2ReplayFilter.FULL
    replayScoreFade=0f
    updateScorecard()
    updateReplayBorders(V2ReplayFilter.FULL,0f)
    animateFullReplayBorder(true)
   }
   return replay.putOnAir(mode)
  }
  if(mode!=V2ReplayFilter.DIAGONAL&&mode!=V2ReplayFilter.CORNER)return false
  val candidate=V2ReplayLayout.choose(mode,originalGraphicsRect(),
   V2BroadcastGraphicsRenderer.compact(graphicsStyle))
  if(candidate==null){
   notifyState("Cannot fit scoreboard beside replay without overlap.")
   return false
  }
  replay.setOnAirStartedListener{startReplayLayout(mode,candidate.scoreboard)}
  return replay.putOnAir(mode)
 }
 fun seekInstantReplay(positionMs:Long,dragging:Boolean)=replay.seek(positionMs,dragging)
 fun setReplayTimelineListener(listener:(Long)->Unit)=replay.setTimelineListener(listener)
 fun setReplayCompletedListener(listener:()->Unit)=replay.setOnAirEnded{
  endReplayLayout()
  // Re-enable the controls only after normal LIVE graphics are restored.
  if(replayLayoutRestoring)ui.postDelayed({if(!ended)listener()},760L)
  else listener()
 }
 fun stopInstantReplay(){
  replay.backToLive()
  endReplayLayout()
 }
 fun setInstantReplaySpeed(speed:Float)=replay.setSpeed(speed)
 fun setInstantReplayZoom(zoom:Float,x:Float,y:Float)=replay.zoom(zoom,x,y)
 fun resetInstantReplayZoom()=replay.resetZoom()
 fun saveInstantReplay()=replay.save()

 private var saveLocalCopy=false
 private var currentRecordFile:File?=null
 private var offlineRecording=false
 private var offlinePaused=false
 // Only actual uploaded images are sent on air: never sponsor names,
 // decorative sponsor frames, opaque cards, headings or text.
 private var sponsorSlides:List<Pair<String,android.graphics.Bitmap?>> = emptyList()
 private var sponsorFilter:ImageFilterRender?=null
 private var sponsorShowing=false
 private var scoreVisibleBeforeSponsor:Boolean?=null
 private var sponsorSlideIndex=0
 private var sponsorAuto=true
 private var sponsorIntervalMs=5000L
 private var sponsorTransitionMs=500L
 private var sponsorFrame:android.graphics.Bitmap?=null
 private var sponsorEpoch=0
 private val sponsorAdvance=object:Runnable{
  override fun run(){if(sponsorShowing&&!ended&&sponsorAuto)advanceSponsor(false)}
 }
 fun isOfflineRecording()=offlineRecording
 fun isOfflinePaused()=offlinePaused
 fun hasSponsorSlides()=sponsorSlides.isNotEmpty()
 fun configureSponsorSlides(names:List<String>,images:List<android.graphics.Bitmap?>){
  if(running||connecting||offlineRecording)return
  sponsorSlides=names.take(12).mapIndexedNotNull {i,name->
   images.getOrNull(i)?.takeIf{!it.isRecycled}?.let{name.trim().take(55) to it}
  }
 }
 private fun initSponsorFilter(){
  if(sponsorFilter!=null)return
  try{
   val gl=stream?.getGlInterface()?:return
   sponsorFilter=ImageFilterRender().also{gl.addFilter(it)}
   // Start with a real transparent texture, not a null/black placeholder.
   val blank=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
   sponsorFilter?.setImage(blank)
   sponsorFrame=blank
   sponsorFilter?.setScale(100f,100f)
   sponsorFilter?.setPosition(0f,0f)
  }catch(_:Exception){notifyState("Sponsor overlay could not initialize.")}
 }
 private fun uploadSponsorFrame(frame:android.graphics.Bitmap){
  try{
   initSponsorFilter()
   sponsorFilter?.setImage(frame)
   sponsorFilter?.setScale(100f,100f)
   sponsorFilter?.setPosition(0f,0f)
   // Release earlier CPU frames after the GL thread has time to upload.
   val old=sponsorFrame
   sponsorFrame=frame
   if(old!=null&&old!==frame)ui.postDelayed({
    if(old!==sponsorFrame&&!old.isRecycled)old.recycle()
   },350L)
  }catch(_:Exception){notifyState("Sponsor image unavailable; camera stays active.")}
 }
 private fun transparentSponsor(){
  uploadSponsorFrame(android.graphics.Bitmap.createBitmap(8,8,
   android.graphics.Bitmap.Config.ARGB_8888))
 }
 private fun drawSponsor(index:Int,alpha:Float,canvas:android.graphics.Canvas,w:Int,h:Int){
  val bitmap=sponsorSlides.getOrNull(index)?.second?:return
  if(bitmap.isRecycled)return
  // Scale-to-contain with 5% top/bottom room, never crop or distort.
  val factor=minOf(w*.92f/bitmap.width,h*.90f/bitmap.height,1f)
  val dw=bitmap.width*factor;val dh=bitmap.height*factor
  val dst=android.graphics.RectF((w-dw)/2f,(h-dh)/2f,
   (w+dw)/2f,(h+dh)/2f)
  val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or
   android.graphics.Paint.FILTER_BITMAP_FLAG).apply{
   this.alpha=(alpha.coerceIn(0f,1f)*255).toInt()
  }
  canvas.drawBitmap(bitmap,null,dst,p)
 }
 private fun sponsorFrame(from:Int,to:Int,mix:Float):android.graphics.Bitmap{
  val w=videoWidth.coerceIn(640,1920);val h=videoHeight.coerceIn(360,1080)
  val result=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
  val canvas=android.graphics.Canvas(result)
  if(from!=to&&mix<1f)drawSponsor(from,1f-mix,canvas,w,h)
  drawSponsor(to,if(from==to)1f else mix,canvas,w,h)
  return result
 }
 private fun displaySponsor(index:Int,previous:Int=index,animate:Boolean=false){
  val epoch=++sponsorEpoch
  if(!sponsorShowing)return
  if(!animate||previous==index||sponsorTransitionMs<=0L){
   uploadSponsorFrame(sponsorFrame(index,index,1f));return
  }
  val start=android.os.SystemClock.uptimeMillis()
  fun drawStep(){
   if(!sponsorShowing||ended||epoch!=sponsorEpoch)return
   val t=((android.os.SystemClock.uptimeMillis()-start).toFloat()/
    sponsorTransitionMs).coerceIn(0f,1f)
   val smooth=t*t*(3f-2f*t)
   uploadSponsorFrame(sponsorFrame(previous,index,smooth))
   if(t<1f)ui.postDelayed({drawStep()},80L)
  }
  drawStep()
 }
 private fun scheduleSponsorAdvance(){
  ui.removeCallbacks(sponsorAdvance)
  if(sponsorShowing&&sponsorAuto&&!ended)ui.postDelayed(sponsorAdvance,sponsorIntervalMs)
 }
 fun setSponsorTiming(auto:Boolean,intervalSeconds:Int,transitionMillis:Int){
  sponsorAuto=auto
  sponsorIntervalMs=intervalSeconds.coerceIn(2,30)*1000L
  sponsorTransitionMs=transitionMillis.coerceIn(0,1500).toLong()
  scheduleSponsorAdvance()
 }
 private fun advanceSponsor(immediate:Boolean){
  if(!sponsorShowing||sponsorSlides.isEmpty())return
  val from=sponsorSlideIndex
  sponsorSlideIndex=(sponsorSlideIndex+1)%sponsorSlides.size
  // Automatic rotation uses crossfade; manual NEXT switches immediately.
  displaySponsor(sponsorSlideIndex,from,!immediate)
  scheduleSponsorAdvance()
 }
 fun setSponsorSlideshow(enabled:Boolean){
  ui.removeCallbacks(sponsorAdvance)
  val show=enabled&&hasSponsorSlides()&&!ended
  sponsorEpoch++
  if(show){
   if(!sponsorShowing)scoreVisibleBeforeSponsor=scoreVisible
   sponsorShowing=true
   setScorecardVisibleInstant(false)
   sponsorSlideIndex=0
   displaySponsor(sponsorSlideIndex)
   scheduleSponsorAdvance()
  }else{
   sponsorShowing=false
   transparentSponsor()
   val previous=scoreVisibleBeforeSponsor
   scoreVisibleBeforeSponsor=null
   if(previous!=null)setScorecardVisibleInstant(previous)
  }
 }
 fun nextSponsorSlide(){advanceSponsor(true)}

 fun configureLocalRecording(enabled:Boolean){
  if(running||connecting)return
  saveLocalCopy=enabled
 }
 private fun beginLocalRecording(engine:GenericStream):Boolean{
  if(!saveLocalCopy)return false
  try{
   val folder=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
   if(!folder.exists()&&!folder.mkdirs())throw IllegalStateException("Recording folder unavailable")
   val name="IMG-LIVE-"+java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
    java.util.Locale.US).format(java.util.Date())+".mp4"
   val file=File(folder,name)
   engine.startRecord(file.absolutePath){ _ -> }
   currentRecordFile=file
   notifyState("Local MP4 recording started")
   return true
  }catch(_:Exception){
   currentRecordFile=null
   notifyState(if(offlineRecording)"This phone could not start local MP4 recording." else "Local recording could not start. YouTube upload continues.")
   return false
  }
 }
 private fun finishLocalRecording(engine:GenericStream?,wasOffline:Boolean){
  val file=currentRecordFile?:return
  currentRecordFile=null
  try{engine?.stopRecord()}catch(_:Exception){}
  // The Android encoder may flush the MP4 trailer after stopRecord returns.
  // Finalize and publish on a worker, not on the UI/binder thread.
  val finalScore=scoreboard.copy()
  // Preserve bounded team emblems in each successfully saved recording's
  // local metadata. No full-resolution logos or OAuth credentials are stored.
  fun thumbnail(bitmap:android.graphics.Bitmap?):String{
   if(bitmap==null||bitmap.isRecycled)return ""
   return try{
    val edge=52
    val small=android.graphics.Bitmap.createScaledBitmap(bitmap,edge,edge,true)
    val buffer=java.io.ByteArrayOutputStream()
    val ok=small.compress(android.graphics.Bitmap.CompressFormat.JPEG,65,buffer)
    if(small!==bitmap)small.recycle()
    val bytes=buffer.toByteArray()
    if(ok&&bytes.size in 1..7000) "data:image/jpeg;base64,"+
     android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP) else ""
   }catch(_:Exception){""}
  }
  val savedHomeLogo=thumbnail(graphicsHomeLogo)
  val savedAwayLogo=thumbnail(graphicsAwayLogo)
  if(Build.VERSION.SDK_INT>=29){
   Thread {
    try{
     var lastLength=-1L
     for(attempt in 0..12){
      val length=if(file.exists())file.length() else 0L
      if(length>0&&length==lastLength)break
      lastLength=length
      Thread.sleep(250)
     }
     if(!file.exists()||file.length()<=0)return@Thread
     val values=ContentValues().apply{
      put(MediaStore.Video.Media.DISPLAY_NAME,file.name)
      put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/IMG LIVE Studio")
      put(MediaStore.Video.Media.IS_PENDING,1)
     }
     val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
      ?:throw IllegalStateException("Storage destination unavailable")
     try{
      contentResolver.openOutputStream(uri)?.use{output->
       FileInputStream(file).use{input->input.copyTo(output)}
      }?:throw IllegalStateException("Storage output unavailable")
      contentResolver.update(uri,ContentValues().apply{
       put(MediaStore.Video.Media.IS_PENDING,0)
      },null,null)
      file.delete()
      // Only recordings successfully published to the phone's MediaStore
      // appear in My Streams. This is NOT based on merely toggling Save.
      val prefs=getSharedPreferences("img-v2-native-clips",MODE_PRIVATE)
      val previous=try{org.json.JSONArray(prefs.getString("clips","[]"))}
       catch(_:Exception){org.json.JSONArray()}
      val clip=org.json.JSONObject()
       .put("id",System.currentTimeMillis().toString())
       .put("name",file.name)
       .put("createdAt",System.currentTimeMillis())
       .put("uri",uri.toString())
       .put("home",finalScore.home)
       .put("away",finalScore.away)
       .put("scores",org.json.JSONArray().put(finalScore.homeScore).put(finalScore.awayScore))
       .put("sport",finalScore.sport)
       .put("homeLogo",savedHomeLogo)
       .put("awayLogo",savedAwayLogo)
       .put("platform",if(wasOffline)"Offline" else "YouTube")
        .put("platforms",if(wasOffline)org.json.JSONArray() else org.json.JSONArray().put("YouTube"))
      previous.put(clip)
      prefs.edit().putString("clips",previous.toString()).apply()
     }catch(error:Exception){contentResolver.delete(uri,null,null);throw error}
    }catch(_:Exception){
     // Preserve recording in app-owned Movies directory on storage export failure.
    }
   }.start()
  }
 }
 private var scoreboard=V2Scorecard.State()
 private var scoreFilter:ImageFilterRender?=null
 private var replayBorderFilter:ImageFilterRender?=null
 private var replayTempRect:V2ReplayLayout.Rect?=null
 private var replayScreenMode=0
 private var replayLayoutEpoch=0
 private var replayLayoutRestoring=false
 private var replayScoreFade=1f
 private fun originalGraphicsRect():V2ReplayLayout.Rect{
  val compact=V2BroadcastGraphicsRenderer.compact(graphicsStyle)
  var width=(if(compact)31f else 84f)*graphicsSize/60f
  var height=width*(videoWidth.toFloat()/videoHeight.toFloat())*
   V2BroadcastGraphicsRenderer.height(graphicsStyle)/
   V2BroadcastGraphicsRenderer.width(graphicsStyle)
  val factor=minOf(1f,85f/height,95f/width)
  width*=factor;height*=factor
  return V2ReplayLayout.Rect(
   if(compact)100f-width-3f else (100f-width)/2f,
   100f-height-3f,width,height)
 }
 private fun updateReplayBorders(mode:Int,alpha:Float){
  try{
   val filter=replayBorderFilter?:return
   val image=V2ReplayFrameGraphics.render(mode,videoWidth,videoHeight,alpha)
   filter.setImage(image)
   filter.setScale(100f,100f)
   filter.setPosition(0f,0f)
  }catch(_:Exception){
   notifyState("Replay broadcast frames unavailable.")
  }
 }
 private fun startReplayLayout(mode:Int,rect:V2ReplayLayout.Rect){
  val epoch=++replayLayoutEpoch
  replayLayoutRestoring=false
  replayScreenMode=mode
  replayTempRect=rect
  replayScoreFade=0f
  placeGraphics()
  updateScorecard()
  updateReplayBorders(mode,0f)
  for(step in 1..4){
   ui.postDelayed({
    if(epoch!=replayLayoutEpoch||ended)return@postDelayed
    val t=step/4f
    replayScoreFade=t*t*(3f-2f*t)
    updateScorecard()
    updateReplayBorders(mode,replayScoreFade)
   },step*60L)
  }
 }
 // Scale one uploaded graphic in GL instead of allocating a new bitmap each tick.
 private fun animateFullReplayBorder(enter:Boolean){
  val epoch=replayLayoutEpoch
  if(enter&&!replay.hasDecodedFrame()){
   // Keep broadcast frame fully invisible until a NEW decoded clip frame exists.
   ui.postDelayed({
    if(epoch==replayLayoutEpoch&&replay.active)animateFullReplayBorder(true)
   },33L)
   return
  }
  if(enter)updateReplayBorders(V2ReplayFilter.FULL,1f)
  for(step in 0..17){
   ui.postDelayed({
    if(epoch!=replayLayoutEpoch||ended)return@postDelayed
    val t=step/17f
    val e=t*t*(3f-2f*t)
    val fraction=if(enter)e else 1f-e
    val width=30f+70f*fraction
    try{
     replayBorderFilter?.setScale(width,width)
     replayBorderFilter?.setPosition(100f-width,100f-width)
    }catch(_:Exception){}
   },(step*850L/17L))
  }
 }
 private fun endReplayLayout(){
  if(replayScreenMode==0&&replayTempRect==null)return
  val mode=replayScreenMode
  replayScreenMode=0
  replayLayoutRestoring=true
  val epoch=++replayLayoutEpoch
  if(mode==V2ReplayFilter.FULL)animateFullReplayBorder(false)
  val steps=if(mode==V2ReplayFilter.FULL)10 else 8
  for(step in 1..steps){
   ui.postDelayed({
    if(epoch!=replayLayoutEpoch||ended)return@postDelayed
    replayScoreFade=if(mode==V2ReplayFilter.FULL){
     if(step<=7)0f else (step-7)/3f
    }else when{
     step<=2->1f-step/2f
     step<=5->0f
     else->(step-5)/3f
    }
    if(step==3){replayTempRect=null;placeGraphics()}
    if(step==steps){
     replayScoreFade=1f
     replayLayoutRestoring=false
    }
    updateScorecard()
    if(mode==V2ReplayFilter.FULL){
     if(step==steps)updateReplayBorders(0,0f)
    }else updateReplayBorders(mode,(1f-step.toFloat()/steps).coerceIn(0f,1f))
   },step*90L)
  }
 }
 private var latestGraphics:android.graphics.Bitmap?=null
 private var latestOperatorGraphics:android.graphics.Bitmap?=null
 private var lastGraphicsGeometry=floatArrayOf(8f,70f,84f,22f)
 private fun pushPreviewGraphics(image:android.graphics.Bitmap?=latestOperatorGraphics?:latestGraphics){
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
 private var activeDraftRequestId=""
 /** Reusing the bound service after stopping must not sign out Google or
  * restart an already-ended YouTube event. Only a NEW, server-validated
  * request ID may reset this local encoder session. */
 fun prepareNextDraft(draftId:String):Boolean{
  if(!Regex("^[a-f0-9]{32}$").matches(draftId))return false
  // Rotation may reconnect the SAME on-air event, but never allow an
  // unrelated draft to take over a running stream in the shared service.
  if(running||connecting)return draftId==activeDraftRequestId
  if(!ended)return true
  if(draftId==activeDraftRequestId)return false
  generation++
  attempted=false
  ended=false
  ingestDestination=null
  recoveryAttempts=0
  connectedAtLeastOnce=false
  notifyState("Ready for new private YouTube event · account retained.")
  return true
 }
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
 // Hardware H.264 with the selected resolution/FPS and adaptive bitrate.
 private var videoWidth=1280
  private var videoHeight=720
  private var videoFps=30
  private var videoBitrate=4000000
  private var minBitrate=1600000
  private var maxBitrate=4000000
  private var thermalBitrateCap=4000000
  /** Select the actual H.264 capture/encoder profile before preparing the camera. */
  fun configureVideoProfile(resolution:Int,fps:Int):Boolean {
   if(resolution !in listOf(720,1080)||fps !in listOf(30,60))return false
   if(running||connecting||stream?.isStreaming==true)return false
   if(stream!=null){
    replay.release()
    try{if(stream?.isOnPreview==true)stream?.stopPreview()}catch(_:Exception){}
    try{stream?.release()}catch(_:Exception){}
    stream=null;scoreFilter=null;latestGraphics=null;lastScorecardImageState=null
   }
   videoWidth=if(resolution==1080)1920 else 1280
   videoHeight=resolution
   videoFps=fps
   // H.264 profile stays at the operator's selected *real* resolution and FPS.
   // Starting straight at YouTube's 17 Mbps 1080p60 ceiling frequently floods
   // mobile uplinks before congestion feedback can stabilize the connection.
   // Begin at a viable cellular-friendly target; ramp only on a healthy RTMPS
   // connection, rather than pretending the phone has 17 Mbps of upload speed.
   maxBitrate=when {
    resolution==1080&&fps==60->17000000
    resolution==1080->14000000
    else->8000000
   }
   minBitrate=when {
    resolution==1080&&fps==60->6000000
    resolution==1080->5000000
    else->3000000
   }
   videoBitrate=when {
    resolution==1080&&fps==60->9000000
    resolution==1080->7000000
    fps==60->5000000
    else->4000000
   }
   thermalBitrateCap=maxBitrate
   lastMeasuredSendKbps=-1
   measuredSendAtMs=0L
   previousUploadedBytes=0L
   lastBitrateChangeAtMs=0L
   congestedSamples=0
   healthySamples=0
   stalledUploadSamples=0
   return true
  }
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
   android.os.Build.VERSION.SDK_INT>=29 && state>=PowerManager.THERMAL_STATUS_SEVERE->(maxBitrate*0.45f).toInt()
    android.os.Build.VERSION.SDK_INT>=29 && state>=PowerManager.THERMAL_STATUS_MODERATE->(maxBitrate*0.7f).toInt()
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
 // getBytesSend is bytes sent over the RTMPS socket, not an internet speedtest.
 // Distinguish observed outbound traffic from the target encoder bitrate.
 private var lastMeasuredSendKbps=-1
 private var measuredSendAtMs=0L
 private var lastBitrateChangeAtMs=0L
 internal fun configuredVideoKbps()=videoBitrate/1000
 internal fun minimumVideoKbps()=minBitrate/1000
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
 fun connectionSummary():String{
  val sent=if(running&&lastMeasuredSendKbps>=0)
   " • sent ~"+lastMeasuredSendKbps+" kbps" else ""
  return networkLabel+" • video target "+(videoBitrate/1000)+" kbps"+sent
 }
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
 fun isActive()=running||connecting||offlineRecording
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
   .setContentTitle(if(offlineRecording)"IMG LIVE Studio V2 • Offline Recording" else "IMG LIVE Studio V2 • Private video")
   .setContentText(title.take(90))
   .setContentIntent(open).setOngoing(true)
   .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
    if(offlineRecording)"Stop Recording" else "Stop Upload",stop).build()).build()
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

 /**
  * Reject a profile the device cannot actually encode/capture. The selected
  * resolution and frame rate must not be replaced with 540p or 30fps silently.
  * This preflight supplements, but cannot replace, a live camera/YouTube test.
  */
 private fun checkRequestedVideoProfile():String?{
  val encoderAvailable=try{
   MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any{codec->
    codec.isEncoder && codec.supportedTypes.any{it.equals("video/avc",true)} &&
     try{codec.getCapabilitiesForType("video/avc").videoCapabilities
      .areSizeAndRateSupported(videoWidth,videoHeight,videoFps.toDouble())
     }catch(_:Exception){false}
   }
  }catch(_:Exception){false}
  if(!encoderAvailable)return "This phone has no H.264 encoder for "+videoWidth+"×"+videoHeight+" at "+videoFps+" FPS. Choose a supported profile."
  val cameraAvailable=try{
   val mgr=getSystemService(CAMERA_SERVICE) as CameraManager
   mgr.cameraIdList.any{id->
    try{
     val caps=mgr.getCameraCharacteristics(id)
     val map=caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
     val sizes=map?.getOutputSizes(SurfaceTexture::class.java)
     val rates=caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
     val hasResolution=sizes?.any{size->
      (size.width>=videoWidth&&size.height>=videoHeight)||
      (size.height>=videoWidth&&size.width>=videoHeight)
     }==true
     val hasFrameRate=rates?.any{it.upper>=videoFps}==true
     hasResolution&&hasFrameRate
    }catch(_:Exception){false}
   }
  }catch(_:Exception){false}
  if(!cameraAvailable)return "This camera cannot provide "+videoWidth+"×"+videoHeight+" at "+videoFps+" FPS. Select a lower quality."
  return null
 }
 fun prepareCamera():Boolean {
  if(stream!=null)return true
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||
     checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
   notifyState("Camera and microphone permissions are required.")
   return false
  }
  val unsupported=checkRequestedVideoProfile()
  if(unsupported!=null){
   notifyState(unsupported)
   return false
  }
  return try {
   val engine=GenericStream(this,this)
   replay.installStreamRecorder(engine)
   // A stable landscape 16:9 H.264 frame; do not rotate it into portrait.
   // Streetball portrait uses a future separate mode/profile.
   if(!engine.prepareVideo(videoWidth,videoHeight,videoBitrate,videoFps,2,0)||
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
    replay.installGl(engine)
    replayBorderFilter=ImageFilterRender().also{
    // Only the encoded broadcast receives labels/frames; operator video stays clean.
    it.setRenderMode(com.pedro.encoder.input.gl.render.RenderMode.OUTPUT)
    engine.getGlInterface().addFilter(it)
   }
    updateReplayBorders(0,0f)
    applyScorecard()
    initSponsorFilter()
    notifyState("Camera and microphone ready • "+videoWidth+"×"+videoHeight+" / "+videoFps+" FPS")
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
   // Preview attachment can recreate a GL surface/texture even when the
   // scoreboard state is unchanged. Restore the last native renderer bitmap
   // to the encoded compositor before restoring its placement.
   val cached=latestGraphics
   if(cached!=null && !cached.isRecycled){
    try{scoreFilter?.setImage(cached)}
    catch(_:Exception){
     lastScorecardImageState=null
     notifyState("GL graphic restoration failed; camera remains active.")
    }
   }else if(scoreFilter!=null){
    // Make a fresh frame if the image cache was never filled.
    lastScorecardImageState=null
    updateScorecard()
   }
   placeGraphics()
   pushPreviewGraphics()
  }catch(_:Exception){ notifyState("Preview unavailable; camera upload may still work.") }
 }
 fun detachPreview(){
  // StopPreview detaches only the rendered SurfaceView when isStreaming=true.
  // It must NOT release encoder, camera or microphone.
  if(stream?.isOnPreview==true)try{stream?.stopPreview()}catch(_:Exception){}
  preview=null
 }
 fun activateOfflineForeground():Boolean{
  if(!prepareCamera())return false
  if(foreground)return true
  return try{
   androidx.core.content.ContextCompat.startForegroundService(this,
    Intent(this,V2BroadcastService::class.java).setAction(actionActivate))
   val n=notification()
   if(Build.VERSION.SDK_INT>=29)startForeground(notificationId,n,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
   else startForeground(notificationId,n)
   foreground=true;true
  }catch(_:Exception){notifyState("Unable to start local recording service.");false}
 }
 fun startOfflineRecording():Boolean{
  if(ended||offlineRecording||running||connecting)return false
  if(!activateOfflineForeground())return false
  val engine=stream?:return false
  if(!engine.isOnPreview){notifyState("Camera preview is not ready. Allow Camera permission.");return false}
  offlineRecording=true;offlinePaused=false;saveLocalCopy=true
  replay.setCapturing(true)
  if(!beginLocalRecording(engine)){
   replay.setCapturing(false);offlineRecording=false;return false
  }
  notifyState("Recording on this device • no streaming platform connected")
  return true
 }
 fun pauseOfflineRecording():Boolean{
  if(!offlineRecording||offlinePaused||currentRecordFile==null)return false
  return try{
   replay.setCapturing(false)
   stream?.pauseRecord();offlinePaused=true
   notifyState("Recording paused • Resume or End");true
  }
  catch(_:Exception){notifyState("Could not pause recording.");false}
 }
 fun resumeOfflineRecording():Boolean{
  if(!offlineRecording||!offlinePaused||currentRecordFile==null)return false
  return try{
   stream?.resumeRecord()
   replay.setCapturing(true);offlinePaused=false
   notifyState("Recording resumed");true
  }
  catch(_:Exception){notifyState("Could not resume recording.");false}
 }
 fun startPrivateUpload(draftId:String){
  if(!Regex("^[a-f0-9]{32}$").matches(draftId) || !foreground ||
     !prepareCamera()||attempted||ended||running||connecting){
   if(!foreground)notifyState("Broadcast service is not ready. Keep V2 open and tap Start.")
   return
  }
  if(!networkReady){notifyState("No internet connection. Connect Wi-Fi or mobile data first.");return}
  attempted=true
  activeDraftRequestId=draftId
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
    replay.setCapturing(true)
    stream?.startStream(url)
    stream?.let{beginLocalRecording(it)}
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
 fun shotClockVisible()=scoreboard.shotVisible
 fun setShotClockVisible(visible:Boolean){
  if(scoreboard.shotVisible==visible)return
  scoreboard=scoreboard.copy(shotVisible=visible)
  updateScorecard()
 }
 fun isStarting()=connecting
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
  graphicsStyle=style
  graphicsPosition=if(V2BroadcastGraphicsRenderer.compact(style))"bottom-right" else "bottom-center"
  lastGraphicsStyle=-1
  placeGraphics()
  updateScorecard()
 }
 fun configureGraphics(style:Int,position:String,size:Int,opacity:Int,
  width:Int,height:Int,x:Int,y:Int,margin:Int,effects:Boolean,
  homeLogo:android.graphics.Bitmap?,awayLogo:android.graphics.Bitmap?){
  if(style in 0..11)graphicsStyle=style
  // Legacy X/Y/width/height/position arguments from older installs are
  // intentionally ignored. A single size value keeps artwork proportions.
  graphicsPosition=if(V2BroadcastGraphicsRenderer.compact(graphicsStyle))"bottom-right" else "bottom-center"
  graphicsSize=size.coerceIn(15,115)
  graphicsOpacity=1f
  graphicsWidth=100;graphicsHeight=100
  graphicsX=0;graphicsY=0;graphicsMargin=3
  // All approved scoreboards animate when scores change; no manual switch.
  scoreEffectsEnabled=true
  graphicsHomeLogo=homeLogo
  graphicsAwayLogo=awayLogo
  lastGraphicsStyle=-1
  placeGraphics();updateScorecard()
 }
 fun setGraphicsSize(size:Int){
  val proposed=size.coerceIn(15,115)
  if(proposed==graphicsSize)return
  graphicsSize=proposed
  // GPU scale updates without recreating / recompressing the bitmap.
  placeGraphics()
 }
 fun resizeGraphics(amount:Int){setGraphicsSize(graphicsSize+amount)}
 private fun placeGraphics(notifyPreview:Boolean=true){
  val filter=scoreFilter
  val compact=V2BroadcastGraphicsRenderer.compact(graphicsStyle)
  val size=graphicsSize/60f
  var pw=(if(compact)31f else 84f)*size
  var ph=pw*(videoWidth.toFloat()/videoHeight.toFloat())*
   V2BroadcastGraphicsRenderer.height(graphicsStyle)/
   V2BroadcastGraphicsRenderer.width(graphicsStyle)
  val factor=minOf(1f,85f/ph,95f/pw)
  pw*=factor;ph*=factor
  val safe=3f
  val px=if(compact)100f-pw-safe else (100f-pw)/2f
  val py=100f-ph-safe
  val temporary=replayTempRect
  val x=temporary?.x?:px
  val y=temporary?.y?:py
  val width=temporary?.w?:pw
  val height=temporary?.h?:ph
  try{
   filter?.setScale(width,height)
   filter?.setPosition(x,y)
   lastGraphicsGeometry=floatArrayOf(x,y,width,height)
   if(notifyPreview)pushPreviewGraphics()
  }catch(_:Exception){notifyState("Unable to size native broadcast graphics.")}
 }
 private fun startScoreAward(team:Int,points:Int,prior:Int){
  val epoch=++scoreAwardEpoch
  scoreAward=V2Scorecard.Award(team,points,0f,prior)
  updateScorecard()
  // H.264 is 24 FPS; update the overlay slightly faster (~30 FPS)
  // for a short, fluid transition, rather than 15 frames across 1.68s.
  // Rendering remains finite to limit encoder and GPU load.
  val animationFrames=18
  val frameStepMs=33L
  for(frame in 1..animationFrames){
   ui.postDelayed({
    if(epoch!=scoreAwardEpoch || ended)return@postDelayed
    scoreAward=if(frame==animationFrames)null else
     V2Scorecard.Award(team,points,frame.toFloat()/animationFrames,prior)
    updateScorecard()
   },frame*frameStepMs)
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
 fun setShotClock(seconds:Int){
  val safe=seconds.coerceIn(0,99)
  if(scoreboard.shotClock==safe)return
  scoreboard=scoreboard.copy(shotClock=safe)
  updateScorecard()
 }

 private fun setScorecardVisibleInstant(visible:Boolean){
  scoreVisible=visible
  scoreTransitionEpoch++
  scoreAlpha=if(visible)1f else 0f
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
    scoreFilter=ImageFilterRender().also{
     // Prevent double-drawing in the monitor. The existing separate operator
     // ImageView handles preview graphics; this GPU layer is OUTPUT ONLY.
     it.setRenderMode(com.pedro.encoder.input.gl.render.RenderMode.OUTPUT)
     gl.addFilter(it)
    }
   }
   lastScorecardImageState=null
   lastScorecardVisible=null
   lastScoreAlpha=null
   lastRenderedAward=null
   lastGraphicsStyle=-1
   updateScorecard()
  }catch(_:Exception){
   // GL may not support image filters on this device. Keep the operator
   // preview alive and make the missing encoded overlay explicit.
   notifyState("Broadcast Graphics unavailable in encoded video; camera remains ready.")
   updateScorecard()
  }
 }
 private fun updateScorecard(){
  val filter=scoreFilter
  val onAirAlpha=scoreAlpha*replayScoreFade
  if(lastScoreAlpha==onAirAlpha && lastRenderedAward==scoreAward &&
      lastGraphicsStyle==graphicsStyle && lastScorecardImageState==scoreboard)return
  try{
   // Never pass null to setImage: in the native GL renderer a missing
   // texture can leave an opaque dark/black rectangle over the camera.
   // Transparent frames are rendered for hide and fade transitions.
   val picture=V2BroadcastGraphicsRenderer.render(
    scoreboard,onAirAlpha*graphicsOpacity,scoreAward,graphicsStyle,graphicsHomeLogo,graphicsAwayLogo)
   // Upload first, then scale: image sprite dimensions can reset on
   // texture replacement in the GPU filter lifecycle. A local preview
   // cannot be treated as proof that the encoded GL upload succeeded.
   var uploaded=filter!=null
   if(filter!=null){
    try{filter.setImage(picture)}catch(_:Exception){
     uploaded=false
     notifyState("GL graphic upload failed; on-air overlay unverified.")
    }
   }
   // Clock ticks should update the native ImageView only once per frame.
   placeGraphics(notifyPreview=false)
   latestGraphics=picture
   latestOperatorGraphics=if(replayScreenMode==V2ReplayFilter.FULL)
    V2BroadcastGraphicsRenderer.render(
     scoreboard,scoreAlpha*graphicsOpacity,scoreAward,graphicsStyle,graphicsHomeLogo,graphicsAwayLogo)
    else null
   pushPreviewGraphics(latestOperatorGraphics?:picture)
   // Retry a failed upload on a subsequent state update or surface attach.
   lastScorecardImageState=if(uploaded)scoreboard.copy() else null
   lastRenderedAward=scoreAward
   lastGraphicsStyle=graphicsStyle
   lastScorecardVisible=scoreVisible
   lastScoreAlpha=onAirAlpha
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
  val nowMs=android.os.SystemClock.elapsedRealtime()
  val dropped=client.getDroppedVideoFrames()
  val droppedSincePrevious=(dropped-previousDropped).coerceAtLeast(0L)
  previousDropped=dropped

  // Report ACTUAL RTMPS bytes sent over the most recent sample window.
  // This is traffic observed, not the network's available upload bandwidth:
  // simple/still scenes may naturally transmit fewer bytes than the target.
  val uploadedBytes=client.getBytesSend()
  val elapsedMs=nowMs-measuredSendAtMs
  val delta=(uploadedBytes-previousUploadedBytes).coerceAtLeast(0L)
  if(measuredSendAtMs>0L&&elapsedMs>=1000L&&uploadedBytes>=previousUploadedBytes)
   lastMeasuredSendKbps=((delta*8L)/elapsedMs).coerceIn(0L,Int.MAX_VALUE.toLong()).toInt()
  measuredSendAtMs=nowMs
  if(networkReady&&uploadedBytes<=previousUploadedBytes)stalledUploadSamples++
  else stalledUploadSamples=0
  previousUploadedBytes=uploadedBytes

  // A stopped RTMPS socket is distinct from temporary congestion. Reconnect
  // to the SAME private YouTube event after 3 consecutive zero-byte samples.
  if(stalledUploadSamples>=3){
   running=false;connecting=true
   lastMeasuredSendKbps=-1
   ui.removeCallbacks(qualityTick);qualityPolling=false
   notifyState("Video upload stalled • recovering same private feed")
   scheduleRecovery()
   return
  }

  // RTMP queue pressure and dropped upload frames are congestion signals;
  // the encoder target or Android's mobile-network label is NOT a speedtest.
  val queueBusy=client.hasCongestion(45f)
  val severeQueue=client.hasCongestion(75f)
  val dropping=droppedSincePrevious>=6L
  if(networkReady&&(queueBusy||dropping)){
   congestedSamples++
   healthySamples=0
   val changeAllowed=nowMs-lastBitrateChangeAtMs>=10000L
   if(congestedSamples>=2&&changeAllowed){
    val factor=if(severeQueue||droppedSincePrevious>=15L)0.70 else 0.80
    val reduced=(videoBitrate*factor).toInt()
     .coerceAtLeast(minBitrate).coerceAtMost(thermalBitrateCap)
    if(reduced<videoBitrate){
     videoBitrate=reduced
     lastBitrateChangeAtMs=nowMs
     try{engine.setVideoBitrateOnFly(videoBitrate)}catch(_:Exception){}
     notifyState("RTMPS congestion • video target lowered to "+(videoBitrate/1000)+" kbps")
    }else if(nowMs-lastBitrateChangeAtMs>=30000L){
     lastBitrateChangeAtMs=nowMs
     notifyState("Video upload remains congested at "+(videoBitrate/1000)+
      " kbps • consider a lower FPS or resolution for the next broadcast")
    }
    congestedSamples=0
   }
  }else if(networkReady){
   congestedSamples=0
   healthySamples++
   // Prevent rapid quality oscillation. Only ramp up after 30 seconds
   // without outbound queue pressure or dropped outgoing video frames.
   if(healthySamples>=6&&nowMs-lastBitrateChangeAtMs>=30000L &&
      videoBitrate<thermalBitrateCap){
    videoBitrate=(videoBitrate+250000).coerceAtMost(thermalBitrateCap)
    lastBitrateChangeAtMs=nowMs
    try{engine.setVideoBitrateOnFly(videoBitrate)}catch(_:Exception){}
    notifyState("RTMPS stable • video target "+(videoBitrate/1000)+" kbps")
    healthySamples=0
   }
  }else{
   healthySamples=0
   congestedSamples=0
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
  replay.release()
  replayScreenMode=0;replayTempRect=null;replayBorderFilter=null
  val wasOffline=offlineRecording
  ui.removeCallbacks(sponsorAdvance)
  sponsorShowing=false;transparentSponsor()
  // Next recording or live stream must not inherit a hidden scoreboard.
  scoreVisibleBeforeSponsor?.let{
   scoreVisible=it;scoreAlpha=if(it)1f else 0f
   scoreTransitionEpoch++
  }
  scoreVisibleBeforeSponsor=null
  offlineRecording=false;offlinePaused=false
  generation++
  connecting=false;running=false;ended=true
  ingestDestination=null
  recoveryQueued=false
  ui.removeCallbacks(qualityTick)
  scoreTransitionEpoch++
  scoreAwardEpoch++
  scoreAward=null
  qualityPolling=false
  finishLocalRecording(stream,wasOffline)
  try{if(stream?.isStreaming==true)stream?.stopStream()}catch(_:Exception){}
  try{if(stream?.isOnPreview==true)stream?.stopPreview()}catch(_:Exception){}
  try{stream?.release()}catch(_:Exception){}
  stream=null
  sponsorFilter=null
  scoreFilter=null
  latestGraphics=null
  lastScoreAlpha=null
  try{if(wakeLock?.isHeld==true)wakeLock?.release()}catch(_:Exception){}
  wakeLock=null
  notifyState(if(wasOffline)"Recording ended; MP4 is finalizing on this device." else "Upload stopped. Your YouTube account remains connected.")
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
  measuredSendAtMs=android.os.SystemClock.elapsedRealtime()
  lastMeasuredSendKbps=-1
  lastBitrateChangeAtMs=measuredSendAtMs
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