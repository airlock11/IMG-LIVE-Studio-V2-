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
 fun render(mode:Int,width:Int,height:Int,opacity:Float=1f):Bitmap{
  val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  if(mode!=V2ReplayFilter.DIAGONAL&&mode!=V2ReplayFilter.CORNER)return image
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
   val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    color=Color.argb((200*a).toInt(),12,26,22)
   }
   val tw=label.measureText(title)
   val lx=dst.left+width*.013f
   val ly=dst.top+height*.014f
   c.drawRoundRect(RectF(lx-3f,ly-3f,lx+tw+width*.01f,ly+size+4f),
    3f,3f,bg)
   c.drawText(title,lx,ly+size,label)
  }
  if(mode==V2ReplayFilter.DIAGONAL){
   frame(V2ReplayLayout.windows(mode)[0],"LIVE")
   frame(V2ReplayLayout.windows(mode)[1],"REPLAY")
  }else{
   frame(V2ReplayLayout.windows(mode)[0],"REPLAY")
   val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{
    textSize=(height*.023f).coerceIn(10f,21f)
    typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
    color=Color.argb((235*a).toInt(),78,232,152)
    setShadowLayer(2f,0f,1f,Color.BLACK)
   }
   c.drawText("LIVE",width*.025f,height*.045f,label)
  }
  return image
 }
}
