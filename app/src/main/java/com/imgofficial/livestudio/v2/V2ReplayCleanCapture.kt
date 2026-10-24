package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
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
    try {
     while(capturing.get()){
      val index=prepared!!.dequeueOutputBuffer(info,20_000L)
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
     // Source and streaming encoder remain completely independent.
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
  codec=null;surface=null;engine=null
  // No blocking wait in the broadcast service's main/UI control path.
  thread(name="img-clean-replay-release",isDaemon=true){
   try {worker?.join(400L)}catch(_:Exception){}
   try{c?.stop()}catch(_:Exception){}
   try{c?.release()}catch(_:Exception){}
   try{formerSurface?.release()}catch(_:Exception){}
  }
  worker=null
 }
}
