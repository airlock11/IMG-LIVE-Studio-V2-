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
import android.widget.TextView

/**
 * Compact three-row native replay panel; preview gestures are accepted ONLY
 * while Full replay is active, never on the live camera.
 */
class V2ReplayPanel(
 private val activity:Activity,
 private val service:()->V2BroadcastService?
){
 private var popup:PopupWindow?=null
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
  setPadding(dp(4),0,dp(4),0)
  setTextColor(Color.rgb(231,247,237))
  background=bg(Color.rgb(39,67,57))
  setOnClickListener{click()}
 }
 private fun line()=LinearLayout(activity).apply{
  orientation=LinearLayout.HORIZONTAL
  gravity=Gravity.CENTER_VERTICAL
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
    return true
   }
  })
 }
 fun handleTouch(view:View,event:MotionEvent):Boolean{
  if(service()?.instantReplayIsFull()!=true)return false
  target=view
  tapping.onTouchEvent(event)
  scaling.onTouchEvent(event)
  when(event.actionMasked){
   MotionEvent.ACTION_DOWN->{
    oldX=event.x;oldY=event.y
   }
   MotionEvent.ACTION_MOVE->{
    if(event.pointerCount==1&&zoom>1f){
     val dx=(event.x-oldX)/view.width.coerceAtLeast(1)
     val dy=(event.y-oldY)/view.height.coerceAtLeast(1)
     focusX=(focusX-dx/zoom).coerceIn(0f,1f)
     focusY=(focusY+dy/zoom).coerceIn(0f,1f)
     service()?.setInstantReplayZoom(zoom,focusX,focusY)
    }
    oldX=event.x;oldY=event.y
   }
  }
  return true
 }
 fun show(anchor:View){
  popup?.dismiss()
  val container=LinearLayout(activity).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(7),dp(7),dp(7),dp(7))
   background=bg(Color.rgb(15,30,25))
  }
  val layouts=line()
  listOf("Full","Diagonal","Corner").forEachIndexed{index,name->
   layouts.addView(button(name){
    if(service()?.startInstantReplay(index+1)==true) {
     zoom=1f;focusX=.5f;focusY=.5f
    }
   },LinearLayout.LayoutParams(0,dp(30),1f).apply{
    if(index!=0)leftMargin=dp(4)
   })
  }
  container.addView(layouts)
  val speeds=line()
  val speedLabel=TextView(activity).apply{
   text="SPEED";textSize=10f;setTextColor(Color.rgb(145,200,172))
   gravity=Gravity.CENTER_VERTICAL
  }
  speeds.addView(speedLabel,LinearLayout.LayoutParams(dp(52),dp(30)))
  listOf(.25f,.5f,1f).forEachIndexed{index,value->
   val name=if(value==1f)"1×" else "${value}×"
   speeds.addView(button(name){service()?.setInstantReplaySpeed(value)},
    LinearLayout.LayoutParams(0,dp(30),1f).apply{
     if(index!=0)leftMargin=dp(4)
    })
  }
  container.addView(speeds,LinearLayout.LayoutParams(-1,dp(30)).apply{topMargin=dp(3)})
  val actions=line()
  actions.addView(button("Save"){service()?.saveInstantReplay()},
   LinearLayout.LayoutParams(0,dp(31),1f))
  actions.addView(button("Back to Live"){
   service()?.stopInstantReplay()
   close()
  },LinearLayout.LayoutParams(0,dp(31),1.45f).apply{leftMargin=dp(4)})
  container.addView(actions,LinearLayout.LayoutParams(-1,dp(31)).apply{topMargin=dp(3)})
  popup=PopupWindow(container,dp(280),-2,false).apply{
   isOutsideTouchable=false
   setBackgroundDrawable(bg(Color.rgb(15,30,25)))
   elevation=dp(5).toFloat()
   showAsDropDown(anchor,0,-anchor.height-dp(125),Gravity.CENTER_HORIZONTAL)
  }
 }
 fun close(){
  popup?.dismiss();popup=null
  zoom=1f;focusX=.5f;focusY=.5f
 }
}
