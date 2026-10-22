package com.imgofficial.livestudio.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Native scoreboard artwork. This bitmap is used by RootEncoder's GL
 * ImageFilterRender: it appears in both preview and the encoded RTMPS frames.
 * No WebView, HTML screenshot, fake video layer or network call is involved.
 */
internal object V2Scorecard {
 data class State(
  val sport:String="basketball",
  val home:String="HOME",
  val away:String="AWAY",
  val homeScore:Int=0,
  val awayScore:Int=0,
  val homeFouls:Int=0,
  val awayFouls:Int=0,
  val period:Int=1,
  val clock:String=""
 )
 // Slim 20:3 aspect bar: rendered proportionally in landscape GL frames.
 private const val W=960
 private const val H=144
 private const val ink=0xFF0D1B21.toInt()
 private const val green=0xFF8DE7BB.toInt()
 private const val pale=0xFFE9FFF3.toInt()
 private const val muted=0xFFADC2B9.toInt()
 private fun name(value:String)=value.trim().take(18).ifEmpty{"TEAM"}
 fun render(score:State, opacity:Float=1f):Bitmap {
  val image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(image)
  // The image filter always receives an RGBA bitmap. Hiding the board
  // renders transparent pixels rather than a null (black) texture.
  val alpha=(opacity.coerceIn(0f,1f)*255).toInt()
  if(alpha==0)return image
  val layer=canvas.saveLayerAlpha(0f,0f,W.toFloat(),H.toFloat(),alpha)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  paint.color=ink
  canvas.drawRoundRect(RectF(0f,0f,W.toFloat(),H.toFloat()),21f,21f,paint)
  paint.color=green
  canvas.drawRoundRect(RectF(0f,0f,W.toFloat(),6f),3f,3f,paint)
  paint.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
  paint.textAlign=Paint.Align.CENTER

  val left=name(score.home).uppercase()
  val right=name(score.away).uppercase()
  val isPickle=score.sport=="pickleball"
  val period=if(isPickle)"GAME ${score.period.coerceIn(1,9)}" else
   "Q${score.period.coerceIn(1,9)}"
  val leftName=if(left.length>12)left.take(12) else left
  val rightName=if(right.length>12)right.take(12) else right

  paint.color=pale
  paint.textSize=27f
  canvas.drawText(leftName,192f,39f,paint)
  canvas.drawText(rightName,768f,39f,paint)
  paint.color=green
  paint.textSize=67f
  canvas.drawText(score.homeScore.coerceIn(0,999).toString(),196f,108f,paint)
  canvas.drawText(score.awayScore.coerceIn(0,999).toString(),764f,108f,paint)

  paint.color=muted
  paint.textSize=21f
  canvas.drawText("IMG • "+if(isPickle)"PICKLEBALL" else "BASKETBALL",480f,31f,paint)
  paint.color=pale
  paint.textSize=25f
  canvas.drawText(period,480f,98f,paint)
  if(score.clock.isNotBlank()){
   paint.color=green
   paint.textSize=26f
   canvas.drawText(score.clock.take(5),480f,66f,paint)
  }
  if(!isPickle){
   paint.color=muted
   paint.textSize=21f
   canvas.drawText("FOULS ${score.homeFouls.coerceIn(0,99)}",192f,134f,paint)
   canvas.drawText("FOULS ${score.awayFouls.coerceIn(0,99)}",768f,134f,paint)
  }
  canvas.restoreToCount(layer)
  return image
 }
}
