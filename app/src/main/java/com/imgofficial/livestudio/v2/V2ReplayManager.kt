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
 private var decoder:V2ReplayPlayback?=null
 private var activeClip:File?=null
 private var snapshot:V2ReplayRing.Snapshot?=null
 private var epoch=0
 @Volatile var speed=1f
  private set
 @Volatile var active=false
  private set
 fun installStreamRecorder(engine:GenericStream){
  engine.setRecordController(ring)
 }
 /** Called before the regular scorecard filter is added to the GL chain. */
 fun installGl(engine:GenericStream){
  filter=V2ReplayFilter().also{engine.getGlInterface().addFilter(it)}
 }
 fun setCapturing(enabled:Boolean){
  if(!enabled)stop()
  ring.setCaptureEnabled(enabled)
 }
 fun ready()=ring.ready()
 fun fullActive()=active&&filter?.isFull==true

 fun start(mode:Int,sourceActive:Boolean):Boolean{
  if(mode !in V2ReplayFilter.FULL..V2ReplayFilter.CORNER||!sourceActive){
   status("Start livestreaming or RECORD before using Instant Replay")
   return false
  }
  val sample=ring.snapshot()
  if(sample==null){status("Replay buffer needs 10 seconds of footage");return false}
  val renderer=filter?:run{status("Replay video compositor unavailable");return false}
  val surface=renderer.decoderSurface(250L)
   ?:run{status("Replay surface not ready");return false}
  stop()
  val session=++epoch
  active=true;speed=1f;snapshot=sample
  val path=File(context.cacheDir,"IMG_REPLAY_${System.currentTimeMillis()}.mp4")
  status("Preparing previous 10 seconds")
  ring.export(sample,path){ok->
   if(session!=epoch||!active){path.delete();return@export}
   if(!ok){active=false;status("Replay export failed");return@export}
   activeClip?.let{old->if(old!=path)old.delete()}
   activeClip=path
   renderer.enter(mode)
   decoder=V2ReplayPlayback(path,surface,{speed}){success->
    if(session==epoch&&active){
     stop()
     if(!success)status("Replay ended; live camera restored")
    }
   }.also{it.start()}
  }
  return true
 }
 fun stop(){
  val wasActive=active
  active=false;epoch++
  decoder?.cancel();decoder=null
  filter?.exit();filter?.resetZoom()
  val old=activeClip
  activeClip=null
  if(old!=null)ui.postDelayed({old.delete()},1500L)
  if(wasActive)status("Back to LIVE")
 }
 fun setSpeed(value:Float){
  if(value in listOf(.25f,.5f,1f))speed=value
 }
 fun zoom(amount:Float,x:Float,y:Float){if(active)filter?.setZoom(amount,x,y)}
 fun resetZoom(){filter?.resetZoom()}
 fun save(){
  val captured=snapshot?:ring.snapshot()
  if(captured==null){status("No replay clip to save yet");return}
  val file=File(context.cacheDir,"IMG_REPLAY_SAVE_${System.currentTimeMillis()}.mp4")
  status("Saving replay to phone gallery")
  ring.export(captured,file){ok->
   if(!ok){status("Replay MP4 export failed");return@export}
   Thread {
    val good=publish(file)
    file.delete()
    ui.post{status(if(good)"Replay MP4 saved to phone gallery" else
      "Unable to save replay to gallery; live recording is unaffected")}
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
    true
   }
  }catch(_:Exception){
   destination?.let{try{context.contentResolver.delete(it,null,null)}catch(_:Exception){}}
   false
  }
 }
 fun release(){
  stop();ring.setCaptureEnabled(false)
  snapshot=null;filter=null
 }
}
