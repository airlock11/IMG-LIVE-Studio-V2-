package com.imgofficial.livestudio.v2

/**
 * Coordinates are percentages of the actual ENCODED landscape video canvas,
 * with (0,0) at top left. All rectangles include frame/label margins.
 */
object V2ReplayLayout {
 data class Rect(val x:Float,val y:Float,val w:Float,val h:Float) {
  val right get()=x+w
  val bottom get()=y+h
  fun inCanvas()=x>=0f&&y>=0f&&right<=100f&&bottom<=100f
  fun meets(other:Rect)=x<other.right&&right>other.x&&y<other.bottom&&bottom>other.y
 }
 data class Result(val scoreboard:Rect,val moved:Boolean,val resized:Boolean)

 // These match the existing GPU compositor's mode coordinates.
 fun windows(mode:Int)=when(mode) {
  V2ReplayFilter.DIAGONAL->listOf(Rect(2f,2f,46f,46f),Rect(52f,52f,46f,46f))
  V2ReplayFilter.CORNER->listOf(Rect(65.5f,65.5f,32f,32f))
  else->emptyList()
 }
 /**
  * Always computes from the user's original geometry, NEVER from a prior
  * temporary transform. Compact four vertical scoreboards use bottom left.
  * Horizontal scoreboards use the unused left sector (Diagonal) or top
  * safe band (Corner) when bottom-right is occupied.
  */
 fun choose(mode:Int,original:Rect,vertical:Boolean):Result? {
  val occupied=windows(mode)
  if(occupied.isEmpty()||!original.inCanvas())return null
  if(occupied.none{it.meets(original)}&&!vertical)return Result(original,false,false)
  val zone=when(mode) {
   V2ReplayFilter.DIAGONAL->Rect(3f,53f,43f,43f)
   V2ReplayFilter.CORNER->if(vertical)Rect(3f,4f,58f,92f)
    else Rect(3f,3f,93f,51f)
   else->return null
  }
  val factor=minOf(1f,zone.w/original.w.coerceAtLeast(.001f),
   zone.h/original.h.coerceAtLeast(.001f))
  val w=original.w*factor
  val h=original.h*factor
  // No unreadable minuscule graphics: refuse the conflicting layout instead.
  val minW=if(vertical)17f else 32f
  if(w<minW||h<7f)return null
  val x=zone.x
  val y=if(vertical)zone.bottom-h
    else if(mode==V2ReplayFilter.DIAGONAL)zone.bottom-h else zone.y
  val geometry=Rect(x,y,w,h)
  if(!geometry.inCanvas()||occupied.any{geometry.meets(it)})return null
  return Result(geometry,true,factor<.999f)
 }
}
