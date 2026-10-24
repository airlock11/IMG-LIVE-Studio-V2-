package com.imgofficial.livestudio.v2

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.pedro.library.generic.GenericStream
import java.io.File
import java.io.FileInputStream

/**
 * Native replay coordinator. Camera, stream encoder, scoreboard, microphone,
 * recording, and clocks continue working while a muted replay is decoded
 * independently onto RootEncoder's GPU compositor.
 */
class V2ReplayManager(
 private val context:Context, private val status:(String)->Unit
) {
 val ring=V2ReplayRing()
 private val cleanCapture=V2ReplayCleanCapture(ring)
 private var sourceEngine:GenericStream?=null
 private var captureWidth=1280
 private var captureHeight=720
 private var captureFps=30
 private val ui=Handler(Looper.getMainLooper())
 private var filter:V2ReplayFilter?=null
 private var operatorDecoder:V2ReplayPlayback?=null
 private var operatorSurface:android.view.Surface?=null
 private var onAirDecoder:V2ReplayPlayback?=null
 private var preparedFile:File?=null
 private var snapshot:V2ReplayRing.Snapshot?=null
 // History is sampled against clean H.264 video PTS, NOT the current LIVE clock.
 private data class TimedScoreboard(val ptsUs:Long,val value:V2Scorecard.State)
 private val scoreHistory=java.util.ArrayDeque<TimedScoreboard>()
 @Volatile private var lockedScoreHistory:List<TimedScoreboard> = emptyList()
 private var latestScoreboard=V2Scorecard.State()
 private var historicalScoreListener:(V2Scorecard.State?)->Unit={}
 private var epoch=0
 @Volatile var speed=1f
  private set
 @Volatile var active=false
  private set
 @Volatile var prepared=false
  private set
 @Volatile private var modeLocked=false
 @Volatile private var preparingOnAir=false
 fun isModeLocked()=modeLocked
 fun hasDecodedFrame()=filter?.hasDecodedFrame()==true
 @Volatile private var playheadMs=0L
 private var timelineChanged:(Long)->Unit={}
 private var onAirEnded:()->Unit={}
 private var onAirStarted:(Int)->Unit={}
 private var snapshotDurationMs=10_000L
 private var playbackOffsetMs=0L

 fun installStreamRecorder(engine:GenericStream){
  sourceEngine=engine
  engine.setRecordController(ring)
 }
 /** Replay is OUTPUT-only; PREVIEW path feeds a second graphics-free encoder. */
 fun installGl(engine:GenericStream,width:Int,height:Int,fps:Int){
  captureWidth=width
  captureHeight=height
  captureFps=fps
  filter=V2ReplayFilter().also{
   it.setRenderMode(com.pedro.encoder.input.gl.render.RenderMode.OUTPUT)
   engine.getGlInterface().addFilter(it)
  }
 }
 fun setCapturing(enabled:Boolean){
  if(!enabled){
   synchronized(scoreHistory){scoreHistory.clear()}
   lockedScoreHistory=emptyList()
   cleanCapture.stop()
   backToLive()
   ring.setCleanCaptureEnabled(false)
   ring.setCaptureEnabled(false)
   return
  }
  ring.setCaptureEnabled(true)
  val engine=sourceEngine
  if(engine==null||!cleanCapture.start(engine,captureWidth,captureHeight,captureFps)){
   ring.setCleanCaptureEnabled(false)
   ring.setCaptureEnabled(false)
   status("Instant Replay unavailable: clean camera encoder unsupported. Main recording remains active.")
  }
 }
 fun ready()=ring.ready()
 fun fullActive()=active&&filter?.isFull==true
 fun durationMs()=snapshotDurationMs
 fun positionMs()=playheadMs
 fun isPrepared()=prepared
 fun setTimelineListener(listener:(Long)->Unit){timelineChanged=listener}
 fun setOnAirEnded(listener:()->Unit){onAirEnded=listener}
 fun setOnAirStartedListener(listener:(Int)->Unit){onAirStarted=listener}
 internal fun setHistoricalScoreListener(listener:(V2Scorecard.State?)->Unit){
  historicalScoreListener=listener
 }
 /** Keep at most 15 seconds of original, timestamped LIVE scoreboard changes. */
 internal fun recordScoreboardState(state:V2Scorecard.State){
  synchronized(scoreHistory){
   latestScoreboard=state.copy()
   val time=ring.currentVideoPtsUs()?:return
   if(scoreHistory.peekLast()?.value==state)return
   val stamp=maxOf(time,(scoreHistory.peekLast()?.ptsUs?:-1L)+1L)
   scoreHistory.addLast(TimedScoreboard(stamp,state.copy()))
   while(scoreHistory.size>1 &&
    (scoreHistory.size>1000 ||
     scoreHistory.elementAt(1).ptsUs<stamp-15_000_000L))scoreHistory.removeFirst()
  }
 }
 private fun lockScoreboardHistory(sample:V2ReplayRing.Snapshot){
  lockedScoreHistory=synchronized(scoreHistory){
   val from=sample.video.first().pts
   val to=sample.video.last().pts
   val baseline=scoreHistory.lastOrNull{it.ptsUs<=from}
   val within=scoreHistory.filter{it.ptsUs>from&&it.ptsUs<=to}
   val values= (listOfNotNull(baseline)+within)
   if(values.isEmpty())listOf(TimedScoreboard(from,latestScoreboard.copy()))
   else values
  }
 }
 internal fun historicalAtPlayhead():V2Scorecard.State? {
  val s=snapshot?:return null
  val timeline=s.video.first().pts+s.playbackOffsetUs+playheadMs*1000L
  val history=lockedScoreHistory
  return (history.lastOrNull{it.ptsUs<=timeline}?:history.firstOrNull())?.value
 }

 /**
  * Tap the button: freeze one immutable 10-second snapshot, export it on a
  * worker and leave the actual broadcast GL filter in LIVE (mode NONE).
  * No replay is on-air until a display mode is explicitly selected.
  */
 fun prepare(sourceActive:Boolean,result:(Boolean)->Unit):Boolean{
  if(!sourceActive){status("Start GO LIVE or RECORD first");return false}
  if(modeLocked||active||preparingOnAir){status("Finish the active replay before preparing another");return false}
  val sample=ring.snapshot()?:run{status("Replay buffer needs 10 seconds of footage");return false}
  backToLive()
  val request=++epoch
  snapshot=sample
  snapshotDurationMs=10_000L
  playbackOffsetMs=sample.playbackOffsetUs/1000L
  playheadMs=0L
  speed=1f
  prepared=false
  val output=File(context.cacheDir,"IMG_REPLAY_FIXED_${System.currentTimeMillis()}.mp4")
  status("Preparing replay privately")
  ring.export(sample,output){ok->
   if(request!=epoch){output.delete();return@export}
   if(!ok){prepared=false;status("Unable to prepare replay");result(false);return@export}
   preparedFile=output;prepared=true
   result(true)
  }
  return true
 }

 /** A separate TextureView on the operator phone. Never fed into encoder. */
 fun attachPrivatePreview(surface:android.view.Surface):Boolean{
  operatorSurface=surface
  // During async fresh-snapshot export the Surface is retained and will
  // display the selected mode once it is actually ready, never stale footage.
  if(preparingOnAir)return true
  val file=preparedFile?.takeIf{prepared&&it.isFile}?:return false
  operatorDecoder?.cancel()
  operatorDecoder=V2ReplayPlayback(file,surface,{speed},{
   if(!active){val visible=(it-playbackOffsetMs).coerceIn(0L,snapshotDurationMs);playheadMs=visible;timelineChanged(visible)}
  },false,{ /* private playback remains seekable after it ends */ }).also{
   it.seekTo(playbackOffsetMs,false)
   it.start()
  }
  return true
 }
 fun detachPrivatePreview(){operatorSurface=null;operatorDecoder?.cancel();operatorDecoder=null}

 /**
  * Same fixed clip is routed into encoded GPU composition ONLY on explicit
  * Full/Diagonal/Corner tap, retaining current position and speed.
  */
 /**
  * Snapshot at DISPLAY MODE selection, never at OFF AIR panel opening.
  * Export on the worker, leaving the original LIVE pipeline uninterrupted.
  */
 fun putOnAir(mode:Int):Boolean{
  if(mode !in V2ReplayFilter.FULL..V2ReplayFilter.CORNER ||
     !prepared||modeLocked||active||preparingOnAir)return false
  val fresh=ring.snapshot()?:run{
   status("Replay buffer needs a complete 10-second history");return false
  }
  modeLocked=true
  preparingOnAir=true
  // Stop the old OFF AIR preview immediately; its image must not masquerade
  // as footage from the newly selected 10-second period.
  operatorDecoder?.cancel()
  operatorDecoder=null
  val request=++epoch
  val output=File(context.cacheDir,"IMG_REPLAY_MODE_${System.currentTimeMillis()}.mp4")
  ring.export(fresh,output){ok->
   if(request!=epoch){output.delete();return@export}
   preparingOnAir=false
   if(!ok){
    modeLocked=false
    status("Unable to prepare selected replay; LIVE preserved")
    onAirEnded()
    return@export
   }
   val renderer=filter
   val surface=renderer?.decoderSurface(30L)
   if(renderer==null||surface==null){
    output.delete();modeLocked=false
    status("Replay decoder surface unavailable; LIVE preserved")
    onAirEnded()
    return@export
   }
   operatorDecoder?.cancel();operatorDecoder=null
   onAirDecoder?.cancel();onAirDecoder=null
   val old=preparedFile
   preparedFile=output
   if(old!=null&&old!==output)ui.postDelayed({old.delete()},1700L)
   snapshot=fresh
   lockScoreboardHistory(fresh)
   snapshotDurationMs=10_000L
   playbackOffsetMs=fresh.playbackOffsetUs/1000L
   playheadMs=0L
   timelineChanged(0L)
   val playbackRevision=++epoch
   active=true
   renderer.enter(mode)
   // Apply GPU graphics/layout only when the selected clip is actually ready.
   onAirStarted(mode)
   if(mode==V2ReplayFilter.FULL)historicalScoreListener(historicalAtPlayhead())
   // The operator sees the SAME selected MP4 privately on a dedicated
   // TextureView. The broadcast uses its separate GL decoder/Surface.
   operatorSurface?.let{previewSurface->
    operatorDecoder=V2ReplayPlayback(output,previewSurface,{speed},{},
     false,{ /* no effect on broadcast lifecycle */ }).also{
      it.seekTo(playbackOffsetMs,false)
      it.start()
     }
   }
   onAirDecoder=V2ReplayPlayback(output,surface,{speed},{pos->
    if(active){
     val visible=(pos-playbackOffsetMs).coerceIn(0L,snapshotDurationMs)
     playheadMs=visible
     if(mode==V2ReplayFilter.FULL)historicalScoreListener(historicalAtPlayhead())
     timelineChanged(visible)
    }
   },mode!=V2ReplayFilter.FULL,{successful->
    if(playbackRevision==epoch&&active){
     stopOnAir()
     if(!successful)status("Replay decoder stopped; LIVE restored")
    }
   }).also{
    it.seekTo(playbackOffsetMs,false)
    it.start()
   }
  }
  return true
 }

 /** Moves the decoder to an exact frame target by decoding from a keyframe.
  * dragging=true keeps it paused; releasing automatically plays forward.
  * Both preview and on-air decoder share the same operator playhead.
  */
 fun seek(positionMs:Long,dragging:Boolean){
  if(!prepared)return
  val safe=positionMs.coerceIn(0L,(snapshotDurationMs-1).coerceAtLeast(0L))
  playheadMs=safe
  timelineChanged(safe)
  if(active)onAirDecoder?.seekTo(safe+playbackOffsetMs,dragging)
  operatorDecoder?.seekTo(safe+playbackOffsetMs,dragging)
 }
 /** A completed Diagonal/Corner unlocks AFTER GPU exit, not mid-animation. */
 fun stopOnAir(){
  if(!active)return
  active=false
  epoch++
  onAirDecoder?.cancel();onAirDecoder=null
  operatorDecoder?.cancel();operatorDecoder=null
  filter?.exit()
  val exitRevision=epoch
  ui.postDelayed({
   if(exitRevision==epoch&&!active){
    modeLocked=false
    onAirEnded()
   }
  },540L)
  status("Back to LIVE")
 }
 fun backToLive(){
  epoch++ // Cancel in-flight mode export and stale decoder completion.
  preparingOnAir=false
  modeLocked=false
  if(active){
   active=false
   onAirDecoder?.cancel();onAirDecoder=null
   operatorDecoder?.cancel();operatorDecoder=null
   filter?.exit()
  }
  detachPrivatePreview()
  prepared=false
  playheadMs=0L
  snapshot=null
  lockedScoreHistory=emptyList()
  playbackOffsetMs=0L
  val old=preparedFile
  preparedFile=null
  if(old!=null)ui.postDelayed({old.delete()},1700L)
  timelineChanged(0L)
 }
 fun setSpeed(value:Float){
  if(value in listOf(.25f,.5f,1f))speed=value
 }
 fun zoom(amount:Float,x:Float,y:Float){if(active)filter?.setZoom(amount,x,y)}
 fun resetZoom(){filter?.resetZoom()}
 fun save(){
  val captured=snapshot
  if(captured==null){status("No prepared 10-second replay available");return}
  val output=File(context.cacheDir,"IMG_REPLAY_SAVE_${System.currentTimeMillis()}.mp4")
  status("Saving full prepared replay MP4")
  ring.export(captured,output){ok->
   if(!ok){status("Replay MP4 save failed");return@export}
   Thread {
    val good=publish(output)
    output.delete()
    ui.post{status(if(good)"Replay saved to phone gallery" else
     "Unable to save clip; main recording remains active")}
   }.start()
  }
 }
 private fun publish(source:File):Boolean{
  if(!source.isFile||source.length()<4096L)return false
  var destination:android.net.Uri?=null
  return try {
   if(Build.VERSION.SDK_INT<29){
    val dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    if(!dir.exists()&&!dir.mkdirs())return false
    source.copyTo(File(dir,source.name),overwrite=false)
    true
   }else{
    val values=ContentValues().apply{
     put(MediaStore.Video.Media.DISPLAY_NAME,"IMG-REPLAY-${System.currentTimeMillis()}.mp4")
     put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
     put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/IMG LIVE Studio")
     put(MediaStore.Video.Media.IS_PENDING,1)
    }
    destination=context.contentResolver.insert(
     MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:return false
    context.contentResolver.openOutputStream(destination!!)?.use{dst->
     FileInputStream(source).use{it.copyTo(dst)}
    }?:throw IllegalStateException("Gallery output unavailable")
    context.contentResolver.update(destination!!,ContentValues().apply{
     put(MediaStore.Video.Media.IS_PENDING,0)
    },null,null)
    // The gallery MP4 also appears in IMG LIVE Studio's saved local clips.
    // Its platform is Offline: exporting a video is not a livestream.
    indexSavedClip(destination!!,source.name)
    true
   }
  }catch(_:Exception){
   destination?.let{try{context.contentResolver.delete(it,null,null)}catch(_:Exception){}}
   false
  }
 }
 private fun indexSavedClip(uri:android.net.Uri,name:String){
  try{
   val prefs=context.getSharedPreferences("img-v2-native-clips",Context.MODE_PRIVATE)
   val history=try{org.json.JSONArray(prefs.getString("clips","[]"))}
    catch(_:Exception){org.json.JSONArray()}
   val item=org.json.JSONObject()
    .put("id",System.currentTimeMillis().toString())
    .put("name",name)
    .put("createdAt",System.currentTimeMillis())
    .put("uri",uri.toString())
    .put("home","REPLAY")
    .put("away","")
    .put("scores",org.json.JSONArray())
    .put("sport","basketball")
    .put("kind","instant_replay")
    .put("platform","Offline")
    .put("platforms",org.json.JSONArray())
   history.put(item)
   // Trim metadata to 80 entries, never delete user's actual saved MP4 files.
   val start=(history.length()-80).coerceAtLeast(0)
   val trimmed=org.json.JSONArray()
   for(i in start until history.length())trimmed.put(history.get(i))
   prefs.edit().putString("clips",trimmed.toString()).apply()
  }catch(_:Exception){}
 }
 fun release(){
  cleanCapture.stop()
  backToLive()
  ring.setCleanCaptureEnabled(false)
  ring.setCaptureEnabled(false)
  sourceEngine=null
  filter=null
  timelineChanged={}
  onAirEnded={}
  onAirStarted={}
 }
}
