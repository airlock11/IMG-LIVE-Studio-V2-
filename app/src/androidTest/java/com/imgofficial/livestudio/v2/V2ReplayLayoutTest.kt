package com.imgofficial.livestudio.v2

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android geometry and encoded-frame graphics assertions. */
@RunWith(AndroidJUnit4::class)
class V2ReplayLayoutTest {
 @Test fun allFourVerticalScoreboardsAvoidClippingAndRestoreOriginal(){
  for(style in 8..11){
   val original=V2ReplayLayout.Rect(66f,42f,31f,55f)
   for(mode in listOf(V2ReplayFilter.DIAGONAL,V2ReplayFilter.CORNER)){
    val result=V2ReplayLayout.choose(mode,original,true)
    assertNotNull("Vertical style $style mode $mode",result)
    val rect=result!!.scoreboard
    assertTrue("Bounds safe",rect.inCanvas())
    val replay=V2ReplayLayout.windows(mode).last()
    assertTrue("Scoreboard must be to LEFT of replay",rect.right<replay.x)
    assertTrue("Scoreboard must sit in lower part",rect.bottom>90f)
    assertTrue("Keep width readable",rect.w>=17f)
    assertTrue("No video collision",V2ReplayLayout.windows(mode).none{it.meets(rect)})
   }
   // Result never mutates the user's configured rectangle.
   assertEquals(66f,original.x,0f)
   assertEquals(31f,original.w,0f)
  }
 }
 @Test fun horizontalBoardAvoidsFrameCollisionAndRejectsTinyLayout(){
  val original=V2ReplayLayout.Rect(8f,78f,84f,18f)
  for(mode in listOf(V2ReplayFilter.DIAGONAL,V2ReplayFilter.CORNER)){
   val rect=V2ReplayLayout.choose(mode,original,false)!!.scoreboard
   assertTrue(rect.inCanvas())
   assertTrue(V2ReplayLayout.windows(mode).none{it.meets(rect)})
    assertTrue(rect.right<V2ReplayLayout.windows(mode).last().x)
  }
  val impossible=V2ReplayLayout.Rect(0f,0f,10f,100f)
  assertNull(V2ReplayLayout.choose(V2ReplayFilter.DIAGONAL,impossible,true))
 }
 @Test fun animatedFrameGeometryMatchesEachModeVideoWindows(){
  for(mode in listOf(V2ReplayFilter.FULL,V2ReplayFilter.DIAGONAL,V2ReplayFilter.CORNER)){
   val end=V2ReplayLayout.animatedWindows(mode,1f)
   assertTrue("Mode has at least one panel",end.isNotEmpty())
   for(panel in end)assertTrue("Final replay frame stays on screen",panel.inCanvas())
   for(progress in listOf(0f,.1f,.3f,.5f,.8f,1f)){
    val windows=V2ReplayLayout.animatedWindows(mode,progress)
    assertEquals(end.size,windows.size)
    assertTrue(windows.all{it.inCanvas()})
   }
   if(mode==V2ReplayFilter.FULL){
    assertEquals(100f,end[0].w,.001f)
    assertEquals(100f,end[0].h,.001f)
   }else assertEquals(V2ReplayLayout.windows(mode),end)
  }
 }
 @Test fun cornerScoreboardMovesWithoutChangingArtworkScaleRatio(){
  val original=V2ReplayLayout.Rect(8f,78f,55f,18f)
  val selected=V2ReplayLayout.choose(V2ReplayFilter.CORNER,original,false)!!.scoreboard
  val replay=V2ReplayLayout.windows(V2ReplayFilter.CORNER).last()
  assertTrue(selected.right<replay.x)
  val mid=V2ReplayLayout.interpolate(original,selected,.5f)
  assertEquals((original.x+selected.x)/2f,mid.x,.01f)
  assertEquals((original.w+selected.w)/2f,mid.w,.01f)
  assertEquals(original,V2ReplayLayout.interpolate(original,selected,0f))
  assertEquals(selected,V2ReplayLayout.interpolate(original,selected,1f))
 }
 @Test fun actualVideoFramesAreTransparentOutsideControls(){
  val frame=V2ReplayFrameGraphics.render(V2ReplayFilter.DIAGONAL,1280,720)
  assertEquals(1280,frame.width)
  assertEquals(720,frame.height)
  assertEquals("Center of unused quadrant is transparent",0,frame.getPixel(640,360))
  assertNotEquals("Metallic edge rendered",0,frame.getPixel(34,17))
  frame.recycle()
  val full=V2ReplayFrameGraphics.render(V2ReplayFilter.FULL,1280,720)
  assertEquals("Full frame reuses the Corner-style metallic border",1280,full.width)
  assertNotEquals("Full Corner-style left border",0,full.getPixel(26,360))
  full.recycle()
  val empty=V2ReplayFrameGraphics.render(V2ReplayFilter.NONE,1280,720)
  assertEquals(0,empty.getPixel(32,16))
  empty.recycle()
 }
}
