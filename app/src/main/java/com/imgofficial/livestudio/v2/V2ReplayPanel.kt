package com.imgofficial.livestudio.v2

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView

/**
 * Original compact IMG styling. Four rows only:
 * Full/Diagonal/Corner; slim bidirectional seek with timing;
 * SPEED .25x/.5x/1x; Save/Back to Live.
 *
 * Opening NEVER goes on-air. Only tapping a display mode enables the
 * compositor. Dragging shows precise decoder-targeted frames OFF AIR or
 * ON AIR, and lifting the thumb starts forward playback at selected speed.
 */
class V2ReplayPanel(
 private val activity:Activity,
 private val service:()->V2BroadcastService?,
 private val onAirSelected:(Int)->Unit,
 private val onClosed:()->Unit,
 private val onPrivateSeek:()->Unit = {},
 private val onZoomChanged:(Float,Float,Float)->Unit = {_,_,_->}
){
 private var popup:PopupWindow?=null
 private var seek:SeekBar?=null
 private var seekRow:LinearLayout?=null
 private var current:TextView?=null
 private var total:TextView?=null
 private var durationMs=10_000L
 private var dragging=false
 private var ready=false
 private var displayLocked=false
 private val displayButtons=mutableListOf<Button>()
 private var zoom=1f
 private var focusX=.5f
 private var focusY=.5f
 private var oldX=0f
 private var oldY=0f
 private var target:View?=null
 private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
 private fun bg(color:Int)=GradientDrawable().apply{
  setColor(color);cornerRadius=dp(9).toFloat()
 }
 private fun button(title:String,click:()->Unit)=Button(activity).apply{
  text=title
  isAllCaps=false
  textSize=11f
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(dp(3),0,dp(3),0)
  setTextColor(Color.rgb(231,247,237))
  background=bg(Color.rgb(39,67,57))
  setOnClickListener{click()}
 }
 private fun line()=LinearLayout(activity).apply{
  orientation=LinearLayout.HORIZONTAL
  gravity=Gravity.CENTER_VERTICAL
 }
 private fun clock(ms:Long):String{
  val seconds=ms.coerceAtLeast(0L)/1000L
  return "${seconds/60}:"+ (seconds%60).toString().padStart(2,'0')
 }
 private val scaling by lazy{
  ScaleGestureDetector(activity,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
   override fun onScale(detector:ScaleGestureDetector):Boolean{
    if(service()?.instantReplayIsFull()!=true)return false
    zoom=(zoom*detector.scaleFactor).coerceIn(1f,4f)
    val t=target?:return false
    focusX=(detector.focusX/t.width.coerceAtLeast(1)).coerceIn(0f,1f)
    focusY=(1f-detector.focusY/t.height.coerceAtLeast(1)).coerceIn(0f,1f)
    service()?.setInstantReplayZoom(zoom,focusX,focusY)
     onZoomChanged(zoom,focusX,focusY)
    return true
   }
  })
 }
 private val tapping by lazy{
  GestureDetector(activity,object:GestureDetector.SimpleOnGestureListener(){
   override fun onDown(e:MotionEvent)=true
   override fun onDoubleTap(e:MotionEvent):Boolean{
    zoom=1f;focusX=.5f;focusY=.5f
    service()?.resetInstantReplayZoom()
    onZoomChanged(1f,.5f,.5f)
    return true
   }
  })
 }
 fun handleTouch(view:View,event:MotionEvent):Boolean{
  if(service()?.instantReplayIsFull()!=true)return false
  target=view
  tapping.onTouchEvent(event);scaling.onTouchEvent(event)
  when(event.actionMasked){
   MotionEvent.ACTION_DOWN->{oldX=event.x;oldY=event.y}
   MotionEvent.ACTION_MOVE->{
    if(event.pointerCount==1&&zoom>1f){
     val dx=(event.x-oldX)/view.width.coerceAtLeast(1)
     val dy=(event.y-oldY)/view.height.coerceAtLeast(1)
     focusX=(focusX-dx/zoom).coerceIn(0f,1f)
     focusY=(focusY+dy/zoom).coerceIn(0f,1f)
     service()?.setInstantReplayZoom(zoom,focusX,focusY)
     onZoomChanged(zoom,focusX,focusY)
    }
    oldX=event.x;oldY=event.y
   }
  }
  return true
 }
 fun setDisplayLocked(locked:Boolean){
  displayLocked=locked
  displayButtons.forEach{it.isEnabled=!locked;it.alpha=if(locked).48f else 1f}
 }
 private fun setSeekVisible(full:Boolean){
  seekRow?.visibility=if(full)View.VISIBLE else View.GONE
 }
 fun onReplayFinished(){
  setDisplayLocked(false)
  setSeekVisible(false)
 }
 fun setPrepared(ok:Boolean,duration:Long=10_000L){
  ready=ok
  durationMs=duration.coerceIn(1000L,30_000L)
  seek?.max=durationMs.toInt()
  seek?.isEnabled=ok
  seek?.progress=0
  current?.text="0:00"
  total?.text=clock(durationMs)
 }
 fun onTimelinePosition(positionMs:Long){
  if(!dragging){
   val safe=positionMs.coerceIn(0L,durationMs)
   seek?.progress=safe.toInt()
   current?.text=clock(safe)
  }
 }
 fun show(anchor:View){
  close()
  val panel=LinearLayout(activity).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(7),dp(6),dp(7),dp(6))
   background=bg(Color.rgb(15,30,25))
  }
  // ROW 1: three display modes. No replay goes to video output until tap.
  val layouts=line()
  listOf("Full","Diagonal","Corner").forEachIndexed{index,label->
   val option=button(label){
    if(ready&&!displayLocked&&service()?.startInstantReplay(index+1)==true){
     setDisplayLocked(true)
     setSeekVisible(index==0)
     onAirSelected(index+1)
     zoom=1f;focusX=.5f;focusY=.5f
    }
   }
   displayButtons.add(option)
   layouts.addView(option,LinearLayout.LayoutParams(0,dp(29),1f).apply{
    if(index>0)leftMargin=dp(3)
   })
  }
  panel.addView(layouts)
  // ROW 2: smooth target-frame seek, with small current/total indicators.
  val timeline=line()
  seekRow=timeline
  fun small(label:String)=TextView(activity).apply{
   text=label;textSize=10f;gravity=Gravity.CENTER
   setTextColor(Color.rgb(180,220,195))
  }
  current=small("0:00")
  total=small("0:10")
  timeline.addView(current,LinearLayout.LayoutParams(dp(35),dp(25)))
  val bar=SeekBar(activity).apply{
   max=10_000
   progress=0
   isEnabled=false
   splitTrack=false
   setPadding(0,0,0,0)
   progressTintList=android.content.res.ColorStateList.valueOf(Color.rgb(55,210,142))
   thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(185,245,209))
   setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
    override fun onStartTrackingTouch(seekBar:SeekBar){
     dragging=true
     // Only an intentional seek reveals a PRIVATE off-air replay preview.
     if(service()?.instantReplayIsActive()!=true)onPrivateSeek()
     service()?.seekInstantReplay(seekBar.progress.toLong(),true)
    }
    override fun onProgressChanged(seekBar:SeekBar,progress:Int,fromUser:Boolean){
     if(!fromUser||!ready)return
     current?.text=clock(progress.toLong())
     // Both directions request exact presentation frames; request never
     // touches main RTMPS/MP4 encoder or changes on-air state.
     service()?.seekInstantReplay(progress.toLong(),true)
    }
    override fun onStopTrackingTouch(seekBar:SeekBar){
     dragging=false
     if(ready){
      // No extra Play button: release starts selected-speed forward playback.
      service()?.seekInstantReplay(seekBar.progress.toLong(),false)
     }
    }
   })
  }
  seek=bar
  timeline.addView(bar,LinearLayout.LayoutParams(0,dp(28),1f))
  timeline.addView(total,LinearLayout.LayoutParams(dp(35),dp(25)))
  panel.addView(timeline,LinearLayout.LayoutParams(-1,dp(29)).apply{topMargin=dp(2)})
  setSeekVisible(false)
  // ROW 3: speed affects PRIVATE and ON AIR autoplay equally.
  val speeds=line()
  speeds.addView(small("SPEED"),LinearLayout.LayoutParams(dp(48),dp(29)))
  listOf(.25f,.5f,1f).forEachIndexed{index,value->
   speeds.addView(button(if(value==1f)"1×" else "${value}×"){
    service()?.setInstantReplaySpeed(value)
   },LinearLayout.LayoutParams(0,dp(29),1f).apply{
    if(index>0)leftMargin=dp(3)
   })
  }
  panel.addView(speeds,LinearLayout.LayoutParams(-1,dp(30)).apply{topMargin=dp(2)})
  // ROW 4: save the fixed full snapshot, not just selected region.
  val actions=line()
  actions.addView(button("Save"){service()?.saveInstantReplay()},
   LinearLayout.LayoutParams(0,dp(30),1f))
  actions.addView(button("Back to Live"){
   service()?.stopInstantReplay()
   onClosed()
   close()
  },LinearLayout.LayoutParams(0,dp(30),1.45f).apply{leftMargin=dp(3)})
  panel.addView(actions,LinearLayout.LayoutParams(-1,dp(30)).apply{topMargin=dp(3)})
  popup=PopupWindow(panel,dp(285),-2,false).apply{
   isOutsideTouchable=false
   setBackgroundDrawable(bg(Color.rgb(15,30,25)))
   elevation=dp(5).toFloat()
   showAsDropDown(anchor,0,-anchor.height-dp(158),Gravity.CENTER_HORIZONTAL)
  }
  setPrepared(false)
 }
 fun close(){
  popup?.dismiss();popup=null
  displayButtons.clear();displayLocked=false
  seek=null;seekRow=null;current=null;total=null
  dragging=false;ready=false
  zoom=1f;focusX=.5f;focusY=.5f
 }
}
