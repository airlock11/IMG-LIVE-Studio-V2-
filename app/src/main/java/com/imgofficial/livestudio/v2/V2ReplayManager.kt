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
 private val ui=Handler(Looper.getMainLooper())
 private var filter:V2ReplayFilter?=null
 private var operatorDecoder:V2ReplayPlayback?=null
 private var onAirDecoder:V2ReplayPlayback?=null
 private var preparedFile:File?=null
 private var snapshot:V2ReplayRing.Snapshot?=null
 private var epoch=0
 @Volatile var speed=1f
  private set
 @Volatile var active=false
  private set
 @Volatile var prepared=false
  private set
 @Volatile private var playheadMs=0L
 private var timelineChanged:(Long)->Unit={}
 private var onAirEnded:()->Unit={}
 private var snapshotDurationMs=10_000L

 fun installStreamRecorder(engine:GenericStream){engine.setRecordController(ring)}
 /** Insert BEFORE scorecard overlays, so replay zoom never scales score/clock. */
 fun installGl(engine:GenericStream){
  filter=V2ReplayFilter().also{engine.getGlInterface().addFilter(it)}
 }
 fun setCapturing(enabled:Boolean){
  if(!enabled)backToLive()
  ring.setCaptureEnabled(enabled)
 }
 fun ready()=ring.ready()
 fun fullActive()=active&&filter?.isFull==true
 fun durationMs()=snapshotDurationMs
 fun positionMs()=playheadMs
 fun isPrepared()=prepared
 fun setTimelineListener(listener:(Long)->Unit){timelineChanged=listener}
 fun setOnAirEnded(listener:()->Unit){onAirEnded=listener}

 /**
  * Tap the button: freeze one immutable 10-second snapshot, export it on a
  * worker and leave the actual broadcast GL filter in LIVE (mode NONE).
  * No replay is on-air until a display mode is explicitly selected.
  */
 fun prepare(sourceActive:Boolean,result:(Boolean)->Unit):Boolean{
  if(!sourceActive){status("Start GO LIVE or RECORD first");return false}
  val sample=ring.snapshot()?:run{status("Replay buffer needs 10 seconds of footage");return false}
  backToLive()
  val request=++epoch
  snapshot=sample
  snapshotDurationMs=(sample.durationUs/1000L).coerceAtLeast(1000L)
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
  val file=preparedFile?.takeIf{prepared&&it.isFile}?:return false
  operatorDecoder?.cancel()
  operatorDecoder=V2ReplayPlayback(file,surface,{speed},{
   if(!active){playheadMs=it;timelineChanged(it)}
  },false,{ /* private playback remains seekable after it ends */ }).also{it.start()}
  return true
 }
 fun detachPrivatePreview(){operatorDecoder?.cancel();operatorDecoder=null}

 /**
  * Same fixed clip is routed into encoded GPU composition ONLY on explicit
  * Full/Diagonal/Corner tap, retaining current position and speed.
  */
 fun putOnAir(mode:Int):Boolean{
  if(mode !in V2ReplayFilter.FULL..V2ReplayFilter.CORNER||!prepared)return false
  val file=preparedFile?.takeIf{it.isFile}?:return false
  val renderer=filter?:return false
  val surface=renderer.decoderSurface(250L)?:return false
  detachPrivatePreview()
  onAirDecoder?.cancel()
  onAirDecoder=null
  val startedAt=playheadMs
  val request=++epoch
  active=true
  // The compositor stays live until a decoded frame reaches its OES texture.
  renderer.enter(mode)
  onAirDecoder=V2ReplayPlayback(file,surface,{speed},{
   if(active){playheadMs=it;timelineChanged(it)}
  },true,{successful->
   if(request==epoch&&active){
    stopOnAir()
    onAirEnded()
    if(!successful)status("Replay decoder stopped; LIVE restored")
   }
  }).also{
   it.seekTo(startedAt,false)
   it.start()
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
  if(active)onAirDecoder?.seekTo(safe,dragging)
  else operatorDecoder?.seekTo(safe,dragging)
 }
 fun stopOnAir(){
  if(!active)return
  active=false
  epoch++
  onAirDecoder?.cancel();onAirDecoder=null
  filter?.exit()
  filter?.resetZoom()
  status("Back to LIVE")
 }
 fun backToLive(){
  stopOnAir()
  epoch++
  detachPrivatePreview()
  prepared=false
  playheadMs=0L
  snapshot=null
  val old=preparedFile
  preparedFile=null
  // Give the decoder a moment to release the opened MP4 descriptor.
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
  backToLive()
  ring.setCaptureEnabled(false)
  filter=null
  timelineChanged={}
  onAirEnded={}
 }
}
