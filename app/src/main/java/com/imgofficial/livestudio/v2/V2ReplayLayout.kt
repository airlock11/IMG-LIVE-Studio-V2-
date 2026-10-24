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
  * Place the single scoreboard independently of the CLEAN replay footage.
  * Both mode windows occupy the right/bottom part of the canvas; displaying
  * another scoreboard INSIDE those pixels would require covering the footage.
  * Therefore use the nearest available right-edge region immediately ABOVE
  * the lower-right replay, proportionally scaling the original artwork.
  * Never stretch, crop, alter fonts or graphics, or move either replay window.
  */
 fun choose(mode:Int,original:Rect,vertical:Boolean):Result? {
  if(!original.inCanvas())return null
  val occupied=windows(mode)
  if(occupied.isEmpty())return null
  val zone=when(mode) {
   V2ReplayFilter.CORNER->Rect(64f,3f,33f,59f)
   V2ReplayFilter.DIAGONAL->Rect(52f,3f,45f,45f)
   else->return null
  }
  val factor=minOf(1f,zone.w/original.w,zone.h/original.h)
  if(factor<=0f)return null
  val w=original.w*factor
  val h=original.h*factor
  // Keep the right edge aligned and stay as close as practical to bottom-right.
  val x=zone.right-w
  val y=zone.bottom-h
  val result=Rect(x,y,w,h)
  if(!result.inCanvas()||occupied.any{result.meets(it)})return null
  return Result(result,true,factor<.999f)
 }
}
