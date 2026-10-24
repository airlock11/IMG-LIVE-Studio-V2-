package com.imgofficial.livestudio.v2

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import com.pedro.library.base.recording.RecordController
import com.pedro.library.util.AndroidMuxerRecordController
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Rolling encoded H264/AAC packets from RootEncoder's existing camera encoder.
 *
 * The normal MP4 recorder is delegated UNCHANGED; this tap never stops,
 * restarts, reconfigures or consumes its encoder buffers. Bounded byte/frame
 * storage guards device RAM during long basketball matches.
 *
 * Only the secondary PREVIEW-surface encoder supplies replay video. The main
 * composited RootEncoder packets are delegated unchanged for local MP4 saving.
 */
class V2ReplayRing(
  private val delegate: RecordController = AndroidMuxerRecordController()
) : RecordController by delegate {
  data class Packet(val bytes: ByteArray, val pts: Long, val flags: Int) {
    val size: Int get() = bytes.size
    val key: Boolean get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
  }
  data class Snapshot(
    val videoFormat: MediaFormat,
    val audioFormat: MediaFormat?,
    val video: List<Packet>,
    val audio: List<Packet>,
    val durationUs: Long,
    // Preceding sync frame is decoder preroll, NOT part of the visible clip.
    val playbackOffsetUs: Long
  )
  private val lock = Any()
  private val video = ArrayDeque<Packet>()
  private val audio = ArrayDeque<Packet>()
  private var videoFormat: MediaFormat? = null
  private var audioFormat: MediaFormat? = null
  private var storedBytes = 0L
  private var lastPts = 0L
  @Volatile private var accepting = false
  @Volatile private var cleanRequired=false
  // Fail closed: no encoded graphics may enter this ring after clean capture
  // has been configured, even if the secondary codec later fails.
  @Volatile private var cleanOnlySession=false
  private var cleanLastWallUs=0L
  private val saver = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  // Up to 13 seconds, including one GOP prior to the 10-second cut. Hard
  // limits avoid an OOM with high bitrate 1080p/60fps source.
  private val maxDurationUs = 13_000_000L
  private val maxBytes = 48L * 1024L * 1024L
  fun setCaptureEnabled(enabled: Boolean) {
    synchronized(lock) {
      accepting = enabled
      if (!enabled) {
        video.clear(); audio.clear(); storedBytes = 0L; lastPts = 0L
      }
    }
  }
  /** Switching clean input invalidates previous composited history. */
  fun setCleanCaptureEnabled(enabled:Boolean)=synchronized(lock){
    cleanRequired=enabled
    if(enabled)cleanOnlySession=true
    video.clear();audio.clear();storedBytes=0L;lastPts=0L
    cleanLastWallUs=0L
    videoFormat=null
  }
  fun setCleanVideoFormat(format:MediaFormat)=synchronized(lock){
    if(cleanRequired)videoFormat=format
  }
  fun recordCleanVideo(buf:ByteBuffer,info:MediaCodec.BufferInfo){
    if(!cleanRequired||!accepting)return
    packet(buf,info)?.let{p->
      synchronized(lock){
        if(cleanRequired&&accepting){
          val wall=android.os.SystemClock.elapsedRealtimeNanos()/1000L
          // A multi-preview encoder surface may not provide explicit EGL
          // presentation timestamps. Never allow duplicated/zero PTS to
          // collapse ten seconds of real footage into one frozen instant.
          val stamp=if(video.isEmpty()||p.pts>lastPts)p.pts else
           lastPts+(wall-cleanLastWallUs).coerceAtLeast(1L)
          val corrected=if(stamp==p.pts)p else p.copy(pts=stamp)
          video.addLast(corrected)
          cleanLastWallUs=wall
          storedBytes+=corrected.size
          lastPts=corrected.pts
          pruneLocked()
        }
      }
    }
  }
  /** Timestamp of the most recent CLEAN captured video frame.
   * Scoreboard state changes use the same video clock for historical replay.
   */
  fun currentVideoPtsUs():Long? = synchronized(lock) {
    if(accepting && video.isNotEmpty())lastPts else null
  }
  fun ready(): Boolean = synchronized(lock) {
    video.size > 5 &&
      lastPts - video.peekFirst().pts >= 10_000_000L &&
      video.any { it.key && it.pts <= lastPts - 10_000_000L } &&
      videoFormat != null
  }
  override fun setVideoFormat(videoFormat: MediaFormat) {
    synchronized(lock) { if(!cleanOnlySession)this.videoFormat=videoFormat }
    delegate.setVideoFormat(videoFormat)
  }
  override fun setAudioFormat(audioFormat: MediaFormat) {
    synchronized(lock) { this.audioFormat = audioFormat }
    delegate.setAudioFormat(audioFormat)
  }
  override fun resetFormats() {
    synchronized(lock) {
      // RootEncoder may reset its recorder while the independent clean
      // encoder continues. Keep the clean output format across this reset.
      videoFormat = if(cleanRequired)videoFormat else null
      audioFormat = null
      video.clear(); audio.clear(); storedBytes = 0L; lastPts = 0L
    }
    delegate.resetFormats()
  }
  private fun packet(buf: ByteBuffer, info: MediaCodec.BufferInfo): Packet? {
    if (info.size <= 0 || info.offset < 0 ||
      info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return null
    val duplicate = buf.duplicate()
    val end = info.offset.toLong() + info.size.toLong()
    if (end > duplicate.capacity() || info.size > 2_500_000) return null
    duplicate.position(info.offset);duplicate.limit(end.toInt())
    val bytes = ByteArray(info.size)
    duplicate.get(bytes)
    return Packet(bytes,info.presentationTimeUs,info.flags)
  }
  override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
    if (accepting && !cleanOnlySession) {
      packet(videoBuffer,videoInfo)?.let { p ->
        synchronized(lock) {
          if (accepting && !cleanOnlySession) {
            video.addLast(p)
            storedBytes += p.size
            lastPts = p.pts
            pruneLocked()
          }
        }
      }
    }
    // Source buffers must be in their original position/limit for RootEncoder.
    delegate.recordVideo(videoBuffer,videoInfo)
  }
  override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
    if (accepting) {
      packet(audioBuffer,audioInfo)?.let{p->
        synchronized(lock) {
          if (accepting) {
            audio.addLast(p);storedBytes += p.size
            pruneLocked()
          }
        }
      }
    }
    delegate.recordAudio(audioBuffer,audioInfo)
  }
  private fun pruneLocked() {
    val cutoff = lastPts - maxDurationUs
    while (video.isNotEmpty() &&
      (video.peekFirst().pts < cutoff || storedBytes > maxBytes || video.size > 950)) {
      storedBytes -= video.removeFirst().size
    }
    while (audio.isNotEmpty() &&
      (audio.peekFirst().pts < cutoff || storedBytes > maxBytes || audio.size > 1800)) {
      storedBytes -= audio.removeFirst().size
    }
  }
  fun snapshot(): Snapshot? = synchronized(lock) {
    val fmt = videoFormat ?: return@synchronized null
    if (video.size < 6 || lastPts-video.peekFirst().pts < 10_000_000L)
      return@synchronized null
    val all = video.toList()
    val threshold = lastPts - 10_000_000L
    // Prefer the nearest preceding keyframe to avoid undecodable MP4.
    val start = all.indexOfLast { it.key && it.pts <= threshold }
    if (start < 0 || start == all.lastIndex) return@synchronized null
    val frames = all.subList(start,all.size).toList()
    val begin = frames.first().pts
    // Video surface-encoder PTS uses EGL/system time while RootEncoder's
    // AAC encoder may use a different epoch. Align their newest packet clocks
    // by offset before writing a consistent MP4 timeline.
    val audioClockShift=if(audio.isNotEmpty())audio.last().pts-lastPts else 0L
    val audioPackets=audio.map { it.copy(pts=it.pts-audioClockShift) }
      .filter { it.pts>=begin && it.pts<=lastPts }
    Snapshot(fmt,audioFormat,frames,audioPackets,lastPts - begin,threshold-begin)
  }
  /** Snapshot is immutable, so exporting never blocks the encoder callback. */
  fun export(snapshot: Snapshot, file: File, result: (Boolean)->Unit) {
    saver.execute {
      val ok = try {
        mux(snapshot,file)
        true
      }catch (_:Exception) {
        try{file.delete()}catch (_:Exception){}
        false
      }
      main.post{result(ok)}
    }
  }
  private fun mux(s:Snapshot,file:File) {
    var muxer:MediaMuxer?=null
    var started=false
    try {
      file.parentFile?.mkdirs()
      muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val videoTrack=muxer.addTrack(s.videoFormat)
      val audioTrack=if(s.audio.isNotEmpty()&&s.audioFormat!=null)
        muxer.addTrack(s.audioFormat) else -1
      muxer.start();started=true
      val begin=s.video.first().pts
      val frames=ArrayList<Triple<Int,Packet,Long>>(s.video.size+s.audio.size)
      s.video.forEach{ frames.add(Triple(videoTrack,it,it.pts)) }
      if(audioTrack >= 0)s.audio.forEach{ frames.add(Triple(audioTrack,it,it.pts)) }
      frames.sortBy{it.third}
      var count=0
      for ((track,packet,_) in frames) {
        val info=MediaCodec.BufferInfo()
        info.set(0,packet.bytes.size,(packet.pts-begin).coerceAtLeast(0L),
          packet.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        muxer.writeSampleData(track,ByteBuffer.wrap(packet.bytes),info)
        if(track==videoTrack)count++
      }
      if(count<6)throw IllegalStateException("Insufficient keyframes")
    } finally {
      if(started)try{muxer?.stop()}catch(_:Exception){}
      try{muxer?.release()}catch(_:Exception){}
    }
    if(!file.exists()||file.length()<4096L)
      throw IllegalStateException("Replay export incomplete")
  }
}
