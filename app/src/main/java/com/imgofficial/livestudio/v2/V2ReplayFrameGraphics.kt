package com.imgofficial.livestudio.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** On-air-only transparent LIVE/REPLAY frames. Uses canvas dimensions matching
 * the encoded stream, never the phone's operator monitor resolution.
 */
object V2ReplayFrameGraphics {
 private fun RectF.copy()=RectF(left,top,right,bottom)
 fun render(mode:Int,width:Int,height:Int,opacity:Float=1f,progress:Float=1f):Bitmap{
  val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  if(mode!=V2ReplayFilter.FULL&&mode!=V2ReplayFilter.DIAGONAL&&mode!=V2ReplayFilter.CORNER)return image
  val c=Canvas(image)
  val a=opacity.coerceIn(0f,1f)
  fun rect(r:V2ReplayLayout.Rect)=RectF(width*r.x/100f,height*r.y/100f,
    width*r.right/100f,height*r.bottom/100f)
  fun frame(region:V2ReplayLayout.Rect,title:String){
   val dst=rect(region)
   val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    style=Paint.Style.STROKE
    strokeWidth=(width/1280f*2.0f).coerceAtLeast(1.4f)
    shader=LinearGradient(dst.left,dst.top,dst.right,dst.bottom,
     intArrayOf(Color.argb((240*a).toInt(),100,122,113),
      Color.argb((240*a).toInt(),26,44,38),Color.argb((225*a).toInt(),110,125,118)),
     null,Shader.TileMode.CLAMP)
    setShadowLayer((4*width/1280f).coerceAtLeast(2f),0f,2f,
     Color.argb((125*a).toInt(),0,0,0))
   }
   c.drawRoundRect(dst,5f,5f,stroke)
   stroke.clearShadowLayer()
   val accent=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    color=Color.argb((215*a).toInt(),46,201,132)
   }
   c.drawRoundRect(RectF(dst.left+4f,dst.top+2f,
    dst.left+width*.04f,dst.top+5f),2f,2f,accent)
   val size=(height*0.023f).coerceIn(10f,21f)
   val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    textSize=size
    typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
    color=Color.argb((240*a).toInt(),235,248,239)
   }
   // Labels are transparent over footage: red status for LIVE, embossed
   // white with dark text-only shadow for REPLAY. Never draw opaque boxes.
   val lx=dst.left+width*.013f
   val ly=dst.top+height*.014f+size
   if(title=="LIVE"){
    label.color=Color.argb((255*a).toInt(),244,55,62)
    label.setShadowLayer(2.5f,0f,1.2f,0xBB250709.toInt())
    val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply{
     color=Color.argb((255*a).toInt(),245,38,44)
     setShadowLayer(3f,0f,1f,0xBB3E070B.toInt())
    }
    val radius=(size*.24f).coerceAtLeast(3f)
    c.drawCircle(lx+radius,ly-size*.38f,radius,dot)
    c.drawText("LIVE",lx+radius*2f+width*.005f,ly,label)
   }else{
    label.color=Color.argb((255*a).toInt(),251,252,253)
    label.setShadowLayer(3.2f,1.1f,2f,0xEF020607.toInt())
    c.drawText("REPLAY",lx,ly+1f,label)
    label.clearShadowLayer()
    label.color=Color.argb((210*a).toInt(),255,255,255)
    c.drawText("REPLAY",lx,ly,label)
   }
  }
  // Use the SAME animated bounds as the GPU video compositor.
  // Border/label position never advances independently of replay video.
  val bounds=V2ReplayLayout.animatedWindows(mode,progress)
  if(mode==V2ReplayFilter.FULL){
   // Preserve the approved 2% broadcast-safe inset inside the SAME
   // growing Full video region; the margin scales with the shared progress.
   val video=bounds[0]
   val inset=2f*progress.coerceIn(0f,1f)
   frame(V2ReplayLayout.Rect(video.x+inset,video.y+inset,
    video.w-inset*2f,video.h-inset*2f),"REPLAY")
  }else if(mode==V2ReplayFilter.DIAGONAL){
   frame(bounds[0],"LIVE")
   frame(bounds[1],"REPLAY")
  }else{
   frame(bounds[0],"REPLAY")
   // Transparent red LIVE identifier, aligned with the video canvas.
   val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    textSize=(height*.023f).coerceIn(10f,21f)
    typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
    color=Color.argb((255*a).toInt(),244,55,62)
    setShadowLayer(3f,0f,1.5f,0xCC2B0909.toInt())
   }
   val radius=(height*.0058f).coerceAtLeast(3f)
   val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    color=Color.argb((255*a).toInt(),244,55,62)
    setShadowLayer(3f,0f,1f,0xB0220608.toInt())
   }
   val y=height*.047f
   c.drawCircle(width*.025f+radius,y-radius,radius,dot)
   c.drawText("LIVE",width*.025f+radius*2f+width*.006f,y,label)
  }
  return image
 }
}
