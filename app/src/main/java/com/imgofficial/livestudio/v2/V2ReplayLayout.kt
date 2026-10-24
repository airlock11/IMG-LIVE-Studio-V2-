package com.imgofficial.livestudio.v2

/** Percent coordinates on the encoded landscape canvas (origin: top-left). */
object V2ReplayLayout {
 data class Rect(val x:Float,val y:Float,val w:Float,val h:Float) {
  val right get()=x+w
  val bottom get()=y+h
  fun inCanvas()=x>=0f&&y>=0f&&w>0f&&h>0f&&right<=100f&&bottom<=100f
  fun meets(other:Rect)=x<other.right&&right>other.x&&y<other.bottom&&bottom>other.y
 }
 data class Result(val scoreboard:Rect,val moved:Boolean,val resized:Boolean)

 fun windows(mode:Int)=when(mode) {
  V2ReplayFilter.DIAGONAL->listOf(Rect(2.5f,2.5f,45f,45f),Rect(52.5f,52.5f,45f,45f))
  V2ReplayFilter.CORNER->listOf(Rect(66.5f,66.5f,30f,30f))
  else->emptyList()
 }

 /**
  * The current LIVE scoreboard stays LEFT of the replay window in the
  * lower-left canvas area, for both Diagonal and Corner. Preserve the
  * approved artwork and its aspect ratio.
  */
 /** Interpolated window bounds used by BOTH video shader and broadcast frames.
  * progress=0: start of transition. progress=1: final mode geometry.
  * All coordinates are TOP-left percent of the encoded 16:9 canvas.
  */
 private fun tween(a:Rect,b:Rect,f:Float):Rect{
  val t=f.coerceIn(0f,1f)
  return Rect(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,
   a.w+(b.w-a.w)*t,a.h+(b.h-a.h)*t)
 }
 fun animatedWindows(mode:Int,progress:Float):List<Rect>{
  val t=progress.coerceIn(0f,1f)
  return when(mode){
   V2ReplayFilter.FULL->listOf(
    tween(Rect(66.5f,66.5f,30f,30f),Rect(0f,0f,100f,100f),t))
   V2ReplayFilter.DIAGONAL->listOf(
    tween(Rect(0f,0f,100f,100f),windows(mode)[0],t),
    tween(Rect(97.4f,97.4f,.1f,.1f),windows(mode)[1],t))
   V2ReplayFilter.CORNER->listOf(
    tween(Rect(96.5f,96.4f,.1f,.1f),windows(mode)[0],t))
   else->emptyList()
  }
 }
 fun interpolate(a:Rect,b:Rect,progress:Float)=tween(a,b,progress)
 /** Follow the replay panel while keeping a constant no-overlap gap.
  * Artwork is uniformly resized, never stretched.
  */
 fun followingScoreboard(mode:Int,original:Rect,selected:Rect,progress:Float):Rect{
  val desired=tween(original,selected,progress)
  val replay=animatedWindows(mode,progress).lastOrNull()?:return desired
  val gap=2.5f
  val rightLimit=(replay.x-gap).coerceAtLeast(gap+1f)
  val available=(rightLimit-gap).coerceAtLeast(1f)
  val factor=minOf(1f,available/desired.w)
  val width=desired.w*factor
  val height=desired.h*factor
  val x=(minOf(desired.right,rightLimit)-width).coerceAtLeast(gap)
  val y=desired.y.coerceIn(gap,100f-gap-height)
  return Rect(x,y,width,height)
 }

 fun choose(mode:Int,original:Rect,vertical:Boolean):Result? {
  if(!original.inCanvas())return null
  val occupied=windows(mode)
  if(occupied.isEmpty())return null
  // The scoreboard ends before the replay starts, with a >=2.5% gap.
  val zone=when(mode) {
   V2ReplayFilter.CORNER->Rect(2.5f,49f,61.5f,47.5f)
   V2ReplayFilter.DIAGONAL->Rect(2.5f,51.5f,47.5f,46f)
   else->return null
  }
  val factor=minOf(1f,zone.w/original.w,zone.h/original.h)
  if(factor<=0f)return null
  val w=original.w*factor
  val h=original.h*factor
  // A scoreboard too small to read is a rejected layout, not a 'success'.
  if(w<(if(vertical)17f else 24f)||h<7f)return null
  // Keep the right edge beside the replay and anchor to the lower canvas.
  val x=zone.right-w
  val y=zone.bottom-h
  val result=Rect(x,y,w,h)
  if(!result.inCanvas()||occupied.any{result.meets(it)})return null
  return Result(result,true,factor<.999f)
 }
}
