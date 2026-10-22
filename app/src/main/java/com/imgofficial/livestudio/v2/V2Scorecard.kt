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
 private const val W=720
 private const val H=208
 private const val ink=0xFF0D1B21.toInt()
 private const val green=0xFF8DE7BB.toInt()
 private const val pale=0xFFE9FFF3.toInt()
 private const val muted=0xFFADC2B9.toInt()
 private fun name(value:String)=value.trim().take(18).ifEmpty{"TEAM"}
 fun render(score:State):Bitmap {
  val image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(image)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  paint.color=ink
  canvas.drawRoundRect(RectF(0f,0f,W.toFloat(),H.toFloat()),27f,27f,paint)
  paint.color=green
  canvas.drawRoundRect(RectF(0f,0f,W.toFloat(),8f),4f,4f,paint)
  paint.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
  paint.textAlign=Paint.Align.CENTER
  paint.color=muted;paint.textSize=26f
  val sport=if(score.sport=="pickleball")"PICKLEBALL" else "BASKETBALL"
  canvas.drawText("IMG  •  "+sport,360f,42f,paint)
  paint.color=pale;paint.textSize=35f
  canvas.drawText(name(score.home).uppercase(),130f,96f,paint)
  canvas.drawText(name(score.away).uppercase(),590f,96f,paint)
  paint.color=green;paint.textSize=72f
  canvas.drawText(score.homeScore.coerceIn(0,999).toString(),190f,162f,paint)
  canvas.drawText(score.awayScore.coerceIn(0,999).toString(),530f,162f,paint)
  paint.color=pale;paint.textSize=34f
  canvas.drawText("—",360f,152f,paint)
  paint.textSize=22f;paint.color=muted
  val marker=if(score.sport=="pickleball")"GAME "+score.period.coerceIn(1,9) else "Q"+score.period.coerceIn(1,9)
  canvas.drawText(marker,360f,190f,paint)
  if(score.sport=="basketball"){
   canvas.drawText("F "+score.homeFouls.coerceIn(0,99),110f,191f,paint)
   canvas.drawText("F "+score.awayFouls.coerceIn(0,99),610f,191f,paint)
  }
  if(score.clock.isNotBlank()){
   paint.color=green;paint.textSize=23f
   canvas.drawText(score.clock.take(10),360f,102f,paint)
  }
  return image
 }
}
