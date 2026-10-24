package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.preview.MultiPreviewConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Secondary rolling H.264 encoder connected to RootEncoder's PREVIEW-only GL output.
 * The on-air output's scoreboard, sponsors, frame labels, and replay compositor
 * all use RenderMode.OUTPUT, so none of those graphics can enter this surface.
 *
 * The primary stream/recording encoders are not stopped, replaced or restarted.
 * If a phone cannot support a second encoder, only Instant Replay is unavailable.
 */
class V2ReplayCleanCapture(private val ring: V2ReplayRing) {
 private val capturing=AtomicBoolean(false)
 private var codec:MediaCodec?=null
 private var surface:Surface?=null
 private var engine:GenericStream?=null
 private var worker:Thread?=null

 @Synchronized fun start(stream:GenericStream,width:Int,height:Int,fps:Int):Boolean {
  if(capturing.get())return true
  if(!stream.isOnPreview && !stream.isStreaming)return false
  var prepared:MediaCodec?=null
  var input:Surface?=null
  return try {
   // Limit additional hardware load. Keep 16:9 and full camera framing.
   val outputWidth=if(width>1280)1280 else width
   val outputHeight=if(width>1280)height*1280/width else height
   val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,outputWidth,outputHeight).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE,if(outputWidth>=1280)2_800_000 else 1_800_000)
    setInteger(MediaFormat.KEY_FRAME_RATE,fps.coerceIn(15,60))
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
   }
   prepared=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
   prepared!!.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
   input=prepared!!.createInputSurface()
   prepared!!.start()
   ring.setCleanCaptureEnabled(true)
   // A second GL surface renders from the same camera texture, but executes
   // the PREVIEW filter path, never the OUTPUT-only composited path.
   stream.getGlInterface().addMultiPreviewSurface(input!!,
    MultiPreviewConfig(width=outputWidth,height=outputHeight,
     aspectRatioMode=AspectRatioMode.Adjust,isPortrait=false))
   codec=prepared
   surface=input
   engine=stream
   capturing.set(true)
   worker=thread(name="img-clean-replay-encoder",isDaemon=true){
    val info=MediaCodec.BufferInfo()
    var lastKeyframeRequest=SystemClock.uptimeMillis()
    try {
     while(capturing.get()){
      val now=SystemClock.uptimeMillis()
      if(now-lastKeyframeRequest>=250L){
       // More sync points mean reverse scrubbing rarely decodes more than
       // a quarter-second GOP before the selected video frame.
       try {
        prepared!!.setParameters(Bundle().apply{
         putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)
        })
       }catch(_:Exception){}
       lastKeyframeRequest=now
      }
      val index=prepared!!.dequeueOutputBuffer(info,12_000L)
      if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
       ring.setCleanVideoFormat(prepared!!.outputFormat)
      }else if(index>=0){
       try {
        if(info.size>0)prepared!!.getOutputBuffer(index)?.let{
         ring.recordCleanVideo(it,info)
        }
       }finally{prepared!!.releaseOutputBuffer(index,false)}
      }
     }
    }catch(_:Exception){
     // If the extra codec fails, remove its EGL surface immediately instead
     // of allowing a broken buffer queue to block the LIVE camera GL thread.
     if(capturing.compareAndSet(true,false)){
      Handler(Looper.getMainLooper()).post{
       ring.setCleanCaptureEnabled(false)
       stop()
      }
     }
    }
   }
   true
  }catch(_:Exception){
   capturing.set(false)
   try {if(input!=null)stream.getGlInterface().removeMultiPreviewSurface(input!!)}catch(_:Exception){}
   try{prepared?.stop()}catch(_:Exception){}
   try{prepared?.release()}catch(_:Exception){}
   try{input?.release()}catch(_:Exception){}
   codec=null;surface=null;engine=null
   ring.setCleanCaptureEnabled(false)
   false
  }
 }

 @Synchronized fun stop(){
  capturing.set(false)
  val formerSurface=surface
  try {
   if(formerSurface!=null)engine?.getGlInterface()?.removeMultiPreviewSurface(formerSurface)
  }catch(_:Exception){}
  val c=codec
  val draining=worker
  codec=null;surface=null;engine=null
  worker=null
  // No blocking wait in the broadcast service's main/UI control path.
  thread(name="img-clean-replay-release",isDaemon=true){
   try {draining?.join(400L)}catch(_:Exception){}
   try{c?.stop()}catch(_:Exception){}
   try{c?.release()}catch(_:Exception){}
   try{formerSurface?.release()}catch(_:Exception){}
  }
 }
}
