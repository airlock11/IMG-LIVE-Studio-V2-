package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Android integration tests for the actual encoded ring using in-memory
 * synthetic H264 packets; no phone camera, internet or dummy UI is required.
 * Decoder/compositor and export quality still need a real video device.
 */
@RunWith(AndroidJUnit4::class)
class V2ReplayRingTest {
 @Test fun rollingBufferRequiresTenSecondsAndDropsPausedFootage(){
  val ring=V2ReplayRing()
  ring.setVideoFormat(MediaFormat.createVideoFormat("video/avc",1280,720))
  ring.setCaptureEnabled(true)
  for(i in 0 until 90){
   val pts=i*100_000L
   val info=MediaCodec.BufferInfo().apply{
    set(0,32,pts,if(i%10==0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
   }
   ring.recordVideo(ByteBuffer.wrap(ByteArray(32){(i%255).toByte()}),info)
  }
  assertFalse("Do not claim replay ready with less than 10sec",ring.ready())
  for(i in 90 until 135){
   val pts=i*100_000L
   val info=MediaCodec.BufferInfo().apply{
    set(0,32,pts,if(i%10==0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
   }
   ring.recordVideo(ByteBuffer.wrap(ByteArray(32){(i%255).toByte()}),info)
  }
  assertTrue("Rolling encoded history is ready",ring.ready())
  val sample=ring.snapshot()
  assertNotNull(sample)
  assertTrue("Replay begins on valid I-frame",sample!!.video.first().key)
  assertTrue("Full ten-second clip plus decoder preroll",sample.durationUs>=10_000_000L)
  assertTrue("Pre-keyframe decoder offset retained",sample.playbackOffsetUs>=0L)
  assertEquals("Visible duration remains ten seconds",10_000_000L,sample.durationUs-sample.playbackOffsetUs)
  assertTrue("History is bounded",sample.video.size<=135)
  ring.setCaptureEnabled(false)
  assertFalse("Pause clears stale replay, rather than offering old footage",ring.ready())
  assertNull(ring.snapshot())
  ring.setCaptureEnabled(true)
  assertFalse("Resume requires fresh live frames",ring.ready())
 }
 @Test fun cleanEncoderPacketsNeverMixWithCompositedBroadcastPackets(){
  val ring=V2ReplayRing()
  val primaryFmt=MediaFormat.createVideoFormat("video/avc",1280,720)
  val cleanFmt=MediaFormat.createVideoFormat("video/avc",640,360)
  ring.setVideoFormat(primaryFmt)
  ring.setCaptureEnabled(true)
  ring.setCleanCaptureEnabled(true)
  ring.setCleanVideoFormat(cleanFmt)
  for(i in 0..125){
   val ts=i*100_000L
   val info=MediaCodec.BufferInfo().apply{
    set(0,64,ts,if(i%2==0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
   }
   ring.recordVideo(ByteBuffer.wrap(ByteArray(64){0x22.toByte()}),info)
   ring.recordCleanVideo(ByteBuffer.wrap(ByteArray(64){0x77.toByte()}),info)
  }
  assertTrue("Only the dedicated graphics-free replay feed can enable replay",ring.ready())
  val snapshot=ring.snapshot()
  assertNotNull(snapshot)
  assertEquals(640,snapshot!!.videoFormat.getInteger(MediaFormat.KEY_WIDTH))
  assertTrue("Never record bytes from the composited H264 encoder",
   snapshot.video.all{it.bytes.all{b->b==0x77.toByte()}})
  assertEquals(10_000_000L,
   snapshot.durationUs-snapshot.playbackOffsetUs)
  ring.setCleanCaptureEnabled(false)
  assertFalse("Disabling clean input invalidates all old replay packets",ring.ready())
 }

}
