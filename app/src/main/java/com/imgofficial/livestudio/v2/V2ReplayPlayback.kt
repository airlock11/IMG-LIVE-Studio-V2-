package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Private or on-air video decoder, independently driven by an indexed replay
 * playhead. The live camera and encoder are never seeked or restarted.
 *
 * Exact seeks decode forward from PREVIOUS_SYNC and discard intermediate codec
 * output until the chosen presentation timestamp, rather than displaying only
 * keyframes. One persistent MediaCodec is reused; no codec recreate per drag.
 * Responsiveness still depends on source GOP and hardware decoder performance.
 */
class V2ReplayPlayback(
 private val file:File,
 private val surface:Surface,
 private val speed:()->Float,
 private val positionChanged:(Long)->Unit,
 private val returnToLiveAtEnd:Boolean,
 private val finished:(Boolean)->Unit
) {
 private val main=Handler(Looper.getMainLooper())
 private val revision=AtomicLong(1L)
 @Volatile private var stopped=false
 @Volatile private var playing=false
 @Volatile private var requestedPositionUs=0L
 @Volatile private var displayedUs=0L
 @Volatile private var lastRequestMs=0L
 @Volatile private var dragActive=false
 @Volatile private var requestedPlay=false
 private var worker:Thread?=null
 fun start(){
  if(worker!=null)return
  worker=thread(name="img-v2-replay-seek",isDaemon=true){decode()}
 }
 fun cancel(){stopped=true;revision.incrementAndGet();worker?.interrupt()}
 fun seekTo(positionMs:Long,dragging:Boolean) {
  requestedPositionUs=positionMs.coerceAtLeast(0L)*1000L
  requestedPlay=!dragging
  dragActive=dragging
  lastRequestMs=SystemClock.uptimeMillis()
  playing=false
  revision.incrementAndGet()
 }
 fun pause(){playing=false;requestedPlay=false}
 fun play(){requestedPlay=true;playing=true}
 fun positionMs()=displayedUs/1000L
 private fun decode() {
  var extractor:MediaExtractor?=null
  var decoder:MediaCodec?=null
  var playedToEnd=false
  try{
   extractor=MediaExtractor().apply{setDataSource(file.absolutePath)}
   var index=-1
   for(i in 0 until extractor.trackCount){
    if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
       ?.startsWith("video/")==true){index=i;break}
   }
   if(index<0)throw IllegalStateException("No replay video")
   extractor.selectTrack(index)
   val fmt=extractor.getTrackFormat(index)
   decoder=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
   decoder.configure(fmt,surface,null,0)
   decoder.start()
   val info=MediaCodec.BufferInfo()
   var seenRevision=0L
   var seeking=true
   var requestedUs=0L
   var inputDone=false
   var frameStartUs=0L
   var wallStartMs=0L
   var lastDecoderResetMs=0L
   while(!stopped){
    val requestedRevision=revision.get()
    if(requestedRevision!=seenRevision){
     // Coalesce rapid slider updates, especially during reverse dragging;
     // never restart the decoder for each touch sample.
     val now=SystemClock.uptimeMillis()
     if(dragActive&&now-lastDecoderResetMs<33L){
      Thread.sleep(5L)
      continue
     }
     seenRevision=requestedRevision
     requestedUs=requestedPositionUs
     val forwardNearby=!inputDone&&requestedUs>=displayedUs &&
      requestedUs-displayedUs<=550_000L
     if(!forwardNearby){
      // Backwards needs a preceding decode sync frame, followed by all
      // dependent frames; the keyframe itself is NOT what is displayed.
      extractor.seekTo(requestedUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      decoder.flush()
      inputDone=false
      lastDecoderResetMs=now
     }
     seeking=true
     frameStartUs=-1L;wallStartMs=0L
     playing=false
    }
    if(!seeking&&!requestedPlay&&!playing){
     Thread.sleep(8)
     continue
    }
    if(!inputDone){
     val indexIn=decoder.dequeueInputBuffer(5000L)
     if(indexIn>=0){
      val bytes=decoder.getInputBuffer(indexIn)!!
      bytes.clear()
      val count=extractor.readSampleData(bytes,0)
      if(count<0){
       decoder.queueInputBuffer(indexIn,0,0,0L,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
       inputDone=true
      }else{
       decoder.queueInputBuffer(indexIn,0,count,extractor.sampleTime,extractor.sampleFlags)
       extractor.advance()
      }
     }
    }
    val indexOut=decoder.dequeueOutputBuffer(info,5000L)
    if(indexOut==MediaCodec.INFO_TRY_AGAIN_LATER||
      indexOut==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)continue
    if(indexOut<0)continue
    if(seenRevision!=revision.get()){
     decoder.releaseOutputBuffer(indexOut,false)
     continue
    }
    if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0){
     decoder.releaseOutputBuffer(indexOut,false)
     if(requestedPlay&&returnToLiveAtEnd){playedToEnd=true;break}
     // Keep final private frame displayed: a later seek can reuse this codec.
     playing=false;requestedPlay=false
     while(!stopped&&revision.get()==seenRevision)Thread.sleep(12)
     continue
    }
    val pts=info.presentationTimeUs.coerceAtLeast(0L)
    if(seeking){
     // Decode all dependent P/B frames; display selected presentation frame
     // instead of jumping back to the previous I-frame.
     val frameDurationUs=1_000_000L/
       (if(fmt.containsKey(MediaFormat.KEY_FRAME_RATE))
        fmt.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1,120) else 30)
     if(pts+frameDurationUs/2<requestedUs){
      decoder.releaseOutputBuffer(indexOut,false)
      continue
     }
     decoder.releaseOutputBuffer(indexOut,true)
     displayedUs=pts
     main.post{positionChanged(pts/1000L)}
     seeking=false
     frameStartUs=pts
     wallStartMs=SystemClock.elapsedRealtime()
     playing=requestedPlay
     continue
    }
    if(!requestedPlay||!playing){
     decoder.releaseOutputBuffer(indexOut,false)
     continue
    }
    // Variable-rate playback clock follows the media presentation times.
    // Re-anchor after every emitted frame so mid-replay speed changes work.
    val rate=speed().coerceIn(.25f,1f)
    val deltaUs=(pts-frameStartUs).coerceAtLeast(0L)
    val sleepTargetMs=(deltaUs/1000f/rate).toLong()
    while(!stopped&&seenRevision==revision.get()){
     val remaining=wallStartMs+sleepTargetMs-SystemClock.elapsedRealtime()
     if(remaining<=0)break
     Thread.sleep(remaining.coerceAtMost(12L))
    }
    if(stopped||revision.get()!=seenRevision){
     decoder.releaseOutputBuffer(indexOut,false)
     continue
    }
    decoder.releaseOutputBuffer(indexOut,true)
    displayedUs=pts
    main.post{positionChanged(pts/1000L)}
    frameStartUs=pts
    wallStartMs=SystemClock.elapsedRealtime()
   }
  }catch(_:InterruptedException){
   Thread.currentThread().interrupt()
  }catch(_:Exception){
   playedToEnd=false
  }finally{
   try{decoder?.stop()}catch(_:Exception){}
   try{decoder?.release()}catch(_:Exception){}
   try{extractor?.release()}catch(_:Exception){}
   val clean=playedToEnd&&!stopped
   main.post{finished(clean)}
  }
 }
}
