package com.imgofficial.livestudio.v2

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast

/** One minimal in-app video pop-up for original recordings and saved replays. */
class V2SavedMp4Player(private val activity:Activity){
 private var dialog:Dialog?=null
 private var player:VideoView?=null
 private val handler=Handler(Looper.getMainLooper())
 private var heartbeat:Runnable?=null
 private var dismissControls:Runnable?=null
 private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()

 fun show(uri:Uri,title:String=""){
  close()
  try{
   if(uri.scheme!="content"||uri.authority!="media")
    throw IllegalArgumentException("Unsupported saved video address")
   activity.contentResolver.openAssetFileDescriptor(uri,"r")?.use{
    if(it.length==0L)throw IllegalStateException("Saved video file is empty")
   }?:throw IllegalStateException("Saved video file is missing")
   val window=Dialog(activity)
   window.requestWindowFeature(Window.FEATURE_NO_TITLE)
   window.setCanceledOnTouchOutside(true)
   val background=FrameLayout(activity).apply{
    setBackgroundColor(Color.BLACK)
    isClickable=true
   }
   val video=VideoView(activity)
   player=video
   background.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
   val overlay=FrameLayout(activity).apply{setBackgroundColor(Color.TRANSPARENT)}
   background.addView(overlay,FrameLayout.LayoutParams(-1,-1))
   var expanded=false
   var dragging=false
   val compactWidth=(activity.resources.displayMetrics.widthPixels-dp(32))
    .coerceAtMost(dp(500)).coerceAtLeast(dp(160))
   val compactHeight=(compactWidth*9/16).coerceAtLeast(dp(96))
   val seek=SeekBar(activity).apply{
    max=1000;progress=0
    progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
    thumbTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
    setPadding(0,0,0,0)
   }
   fun textIcon(text:String,onTap:()->Unit)=TextView(activity).apply{
    this.text=text
    textSize=20f
    setTextColor(Color.WHITE)
    gravity=Gravity.CENTER
    setShadowLayer(3f,0f,1f,Color.BLACK)
    setOnClickListener{onTap()}
   }
   fun cancelHide(){dismissControls?.let{handler.removeCallbacks(it)}}
   fun reveal(){
    overlay.visibility=View.VISIBLE
    cancelHide()
    if(video.isPlaying&&!dragging){
     val job=Runnable{if(video.isPlaying&&!dragging)overlay.visibility=View.GONE}
     dismissControls=job
     handler.postDelayed(job,3000L)
    }
   }
   val exit=textIcon("×"){close()}
   overlay.addView(exit,FrameLayout.LayoutParams(dp(42),dp(42),Gravity.TOP or Gravity.RIGHT))
   val transport=LinearLayout(activity).apply{
    orientation=LinearLayout.HORIZONTAL
    gravity=Gravity.CENTER_VERTICAL
    setBackgroundColor(0x46000000)
    setPadding(dp(5),0,dp(5),0)
   }
   val play=textIcon("Ⅱ"){if(video.isPlaying)video.pause() else video.start();reveal()}
   transport.addView(play,LinearLayout.LayoutParams(dp(38),dp(42)))
   transport.addView(seek,LinearLayout.LayoutParams(0,dp(42),1f))
   val expand=textIcon("⛶"){
    expanded=!expanded
    val w=window.window
    if(expanded){
     window.setCanceledOnTouchOutside(false)
     w?.setLayout(-1,-1)
    }else{
     window.setCanceledOnTouchOutside(true)
     w?.setLayout(compactWidth,compactHeight)
    }
    reveal()
   }
   transport.addView(expand,LinearLayout.LayoutParams(dp(38),dp(42)))
   overlay.addView(transport,FrameLayout.LayoutParams(-1,dp(42),Gravity.BOTTOM))
   seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
    override fun onStartTrackingTouch(bar:SeekBar){dragging=true;cancelHide()}
    override fun onProgressChanged(bar:SeekBar,progress:Int,user:Boolean){
     if(user&&video.duration>0)video.seekTo((video.duration.toLong()*progress/1000).toInt())
    }
    override fun onStopTrackingTouch(bar:SeekBar){dragging=false;reveal()}
   })
   video.setOnTouchListener{_,event->
    if(event.action==android.view.MotionEvent.ACTION_UP){
     if(overlay.visibility==View.VISIBLE&&video.isPlaying){
      overlay.visibility=View.GONE;cancelHide()
     }else reveal()
    }
    true
   }
   window.setContentView(background)
   window.window?.apply{
    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setDimAmount(.64f)
    attributes=attributes.apply{windowAnimations=android.R.style.Animation_Dialog}
    setGravity(Gravity.CENTER)
   }
   window.setOnDismissListener{
    cancelHide()
    heartbeat?.let{handler.removeCallbacks(it)};heartbeat=null
    try{video.stopPlayback()}catch(_:Exception){}
    if(dialog===window){dialog=null;player=null}
   }
   dialog=window
   video.setOnErrorListener{_,what,extra->
    Toast.makeText(activity,"Cannot play saved MP4 ($what/$extra). Original file preserved.",Toast.LENGTH_LONG).show()
    close();true
   }
   video.setOnPreparedListener{media->
    media.isLooping=false
    video.start();reveal()
    val updater=object:Runnable{
     override fun run(){
      if(dialog!==window)return
      val duration=video.duration
      if(duration>0&&!dragging)seek.progress=(video.currentPosition.toLong()*1000/duration).toInt()
      play.text=if(video.isPlaying)"Ⅱ" else "▶"
      handler.postDelayed(this,400)
     }
    }
    heartbeat=updater;handler.post(updater)
   }
   video.setOnCompletionListener{reveal()}
   video.setVideoURI(uri)
   window.show()
   window.window?.setLayout(compactWidth,compactHeight)
  }catch(e:Exception){
   close()
   Toast.makeText(activity,e.message?:"Unable to play saved video",Toast.LENGTH_LONG).show()
  }
 }
 fun close(){
  dismissControls?.let{handler.removeCallbacks(it)};dismissControls=null
  heartbeat?.let{handler.removeCallbacks(it)};heartbeat=null
  val previous=dialog;dialog=null
  try{player?.stopPlayback()}catch(_:Exception){}
  player=null
  try{previous?.dismiss()}catch(_:Exception){}
 }
}
