package com.imgofficial.livestudio.v2

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the exact renderer used by camera preview AND RTMPS output. */
@RunWith(AndroidJUnit4::class)
class BroadcastGraphicsRendererTest {
 private val sample=V2Scorecard.State(sport="basketball",home="HOME",away="AWAY",
  homeScore=82,awayScore=89,homeFouls=3,awayFouls=4,period=3,clock="03:01")

 private fun coverage(bitmap:Bitmap):Int {
  val pixels=IntArray(bitmap.width*bitmap.height)
  bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
  return pixels.count { android.graphics.Color.alpha(it)>100 }
 }
 @Test fun allTwelveStylesRenderVisibleArtworkInBothWideAndCompactRatios(){
  for(i in 0..11){
   val bmp=V2BroadcastGraphicsRenderer.render(sample,1f,null,i,null,null)
   try{
    assertEquals("width $i",V2BroadcastGraphicsRenderer.width(i),bmp.width)
    assertEquals("height $i",V2BroadcastGraphicsRenderer.height(i),bmp.height)
    assertTrue("Graphic $i should not be empty",coverage(bmp)>bmp.width*bmp.height/8)
    assertTrue("Graphic $i should have alpha background",bmp.config==Bitmap.Config.ARGB_8888)
   }finally{bmp.recycle()}
  }
 }
 @Test fun scoreAwardsUpdateBothWideAndCornerStyles(){
  for(i in 0..11){
   val normal=V2BroadcastGraphicsRenderer.render(sample,1f,null,i,null,null)
   val scored=V2BroadcastGraphicsRenderer.render(
    sample.copy(homeScore=84),1f,V2Scorecard.Award(0,2,.4f,82),i,null,null)
   try{
    var difference=0
    val step=(normal.width/20).coerceAtLeast(1)
    val stepY=(normal.height/20).coerceAtLeast(1)
    for(x in 0 until normal.width step step){
     for(y in 0 until normal.height step stepY){
      if(normal.getPixel(x,y)!=scored.getPixel(x,y))difference++
     }
    }
    assertTrue("Style $i should visibly animate a score award",difference>2)
   }finally{normal.recycle();scored.recycle()}
  }
 }
 @Test fun invisibleGraphicHasTransparentPixels(){
  for(i in intArrayOf(0,1,3,8,9,11)){
   val bmp=V2BroadcastGraphicsRenderer.render(sample,0f,null,i,null,null)
   try{assertEquals(0,coverage(bmp))}finally{bmp.recycle()}
  }
 }
}
