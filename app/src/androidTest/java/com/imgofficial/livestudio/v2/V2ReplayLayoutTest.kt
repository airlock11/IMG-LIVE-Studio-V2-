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
    assertTrue("Move to left",rect.x<original.x)
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
  }
  val impossible=V2ReplayLayout.Rect(0f,0f,10f,100f)
  assertNull(V2ReplayLayout.choose(V2ReplayFilter.DIAGONAL,impossible,true))
 }
 @Test fun actualVideoFramesAreTransparentOutsideControls(){
  val frame=V2ReplayFrameGraphics.render(V2ReplayFilter.DIAGONAL,1280,720)
  assertEquals(1280,frame.width)
  assertEquals(720,frame.height)
  assertEquals("Center of unused quadrant is transparent",0,frame.getPixel(640,360))
  assertNotEquals("Metallic edge rendered",0,frame.getPixel(34,17))
  frame.recycle()
  val empty=V2ReplayFrameGraphics.render(V2ReplayFilter.NONE,1280,720)
  assertEquals(0,empty.getPixel(32,16))
  empty.recycle()
 }
}
