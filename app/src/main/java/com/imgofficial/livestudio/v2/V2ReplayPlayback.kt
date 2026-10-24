package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import kotlin.concurrent.thread

/**
 * Decodes an immutable short MP4 to the compositor's external OES surface.
 * Playback is muted: game/microphone audio continues from the live encoder.
 * Decoder frame pacing is independent from game and shot clocks.
 */
class V2ReplayPlayback(
  private val file:File,
  private val surface:Surface,
  private val speed:()->Float,
  private val finished:(Boolean)->Unit
){
 @Volatile private var stop=false
 private val main=Handler(Looper.getMainLooper())
 private var worker:Thread?=null
 fun start(){
  if(worker!=null)return
  worker=thread(name="img-replay-decoder",isDaemon=true){decode()}
 }
 fun cancel(){stop=true;worker?.interrupt()}
 private fun decode(){
  var extractor:MediaExtractor?=null
  var decoder:MediaCodec?=null
  var completed=false
  try {
   extractor=MediaExtractor().apply{setDataSource(file.absolutePath)}
   var index=-1
   for(i in 0 until extractor.trackCount){
    val fmt=extractor.getTrackFormat(i)
    if(fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true){index=i;break}
   }
   if(index<0)throw IllegalStateException("Replay has no video track")
   extractor.selectTrack(index)
   val format=extractor.getTrackFormat(index)
   decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
   decoder.configure(format,surface,null,0)
   decoder.start()
   val info=MediaCodec.BufferInfo()
   var inputEnded=false
   var firstPts=-1L
   var startAt=0L
   var playedUs=0L
   val maxUs=50_000_000L // 10s at 0.25x, plus safety
   while(!stop){
    if(!inputEnded){
     val input=decoder.dequeueInputBuffer(10000L)
     if(input>=0){
      val target=decoder.getInputBuffer(input)!!
      target.clear()
      val n=extractor.readSampleData(target,0)
      if(n<0){
       decoder.queueInputBuffer(input,0,0,0L,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
       inputEnded=true
      }else{
       decoder.queueInputBuffer(input,0,n,extractor.sampleTime,extractor.sampleFlags)
       extractor.advance()
      }
     }
    }
    val output=decoder.dequeueOutputBuffer(info,10000L)
    if(output==MediaCodec.INFO_TRY_AGAIN_LATER)continue
    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)continue
    if(output>=0){
     if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0){
      decoder.releaseOutputBuffer(output,false)
      completed=true;break
     }
     if(info.size>0){
      if(firstPts<0){firstPts=info.presentationTimeUs;startAt=SystemClock.elapsedRealtimeNanos()}
      val mediaDeltaUs=(info.presentationTimeUs-firstPts).coerceAtLeast(0L)
      val position=mediaDeltaUs/1_000_000f
      val safeSpeed=speed().coerceIn(.25f,1f)
      // Retiming permits interactive speed changes without replaying/skipping
      // the underlying codec buffers.
      val deltaUs=(mediaDeltaUs-playedUs).coerceAtLeast(0L)
      val wantNs=(deltaUs/safeSpeed*1000f).toLong()
      var waited=0L
      while(!stop&&waited<wantNs){
       val step=(wantNs-waited).coerceAtMost(20_000_000L)
       if(step>=1_000_000L)Thread.sleep(step/1_000_000L)
       waited+=step
      }
      playedUs=mediaDeltaUs
      if(stop)decoder.releaseOutputBuffer(output,false)
      else decoder.releaseOutputBuffer(output,true)
      if(position>maxUs/1_000_000f)break
     }else decoder.releaseOutputBuffer(output,false)
    }
   }
  }catch(_:InterruptedException){
   Thread.currentThread().interrupt()
  }catch(_:Exception){
   completed=false
  }finally{
   try{decoder?.stop()}catch(_:Exception){}
   try{decoder?.release()}catch(_:Exception){}
   try{extractor?.release()}catch(_:Exception){}
   val end=completed&&!stop
   main.post{finished(end)}
  }
 }
}
