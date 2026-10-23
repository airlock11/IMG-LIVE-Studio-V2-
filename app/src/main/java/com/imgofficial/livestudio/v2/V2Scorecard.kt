package com.imgofficial.livestudio.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * IMG Broadcast Graphics. Every frame is a transparent native Bitmap fed
 * into RootEncoder's shared GL compositor: monitor AND encoded RTMPS output.
 *
 * The score-award visual follows the sports-TV movement in the reference:
 * graphic panel wipe -> moving team identity -> +2/+3 award -> score reveal.
 * Original IMG artwork; no official league logos or broadcast assets.
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
  val clock:String="",
  val shotClock:Int=24,
  val shotVisible:Boolean=true
 )
 data class Award(val team:Int,val points:Int,val progress:Float,val previousScore:Int)
 private const val W=960
 private const val H=144
 private const val I=0xFF11191C.toInt()
 private const val WHITE=0xFFF5FBF8.toInt()
 private const val GREY=0xFFB0C2BA.toInt()
 private const val GREEN=0xFF32D997.toInt()
 private const val GOLD=0xFFFFD77C.toInt()
 private fun rect(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,col:Int,r:Float=0f){
  p.shader=null;p.style=Paint.Style.FILL;p.color=col
  if(r>0f)c.drawRoundRect(RectF(x,y,x+w,y+h),r,r,p)
  else c.drawRect(x,y,x+w,y+h,p)
 }
 private fun gradient(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,a:Int,b:Int){
  p.style=Paint.Style.FILL;p.color=Color.WHITE
  p.shader=LinearGradient(x,y,x+w,y+h,a,b,Shader.TileMode.CLAMP)
  c.drawRect(x,y,x+w,y+h,p);p.shader=null
 }
 private fun stroke(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,col:Int){
  p.style=Paint.Style.STROKE;p.strokeWidth=1.8f;p.color=col
  c.drawRect(x,y,x+w,y+h,p);p.style=Paint.Style.FILL
 }
 private fun words(c:Canvas,p:Paint,v:String,x:Float,y:Float,size:Float,col:Int,
  bold:Boolean=false,align:Paint.Align=Paint.Align.CENTER){
  p.shader=null;p.color=col;p.textAlign=align
  p.typeface=Typeface.create(if(bold)"sans-serif-condensed" else "sans-serif-medium",
   if(bold)Typeface.BOLD else Typeface.NORMAL)
  p.textSize=size
  c.drawText(v,x,y,p)
 }
 private fun short(value:String)=value.trim().uppercase().take(17).ifEmpty{"TEAM"}
 private fun initials(value:String)=short(value).split(' ','-').filter{it.isNotEmpty()}
  .take(3).map{it.first()}.joinToString("")
 private fun streaks(c:Canvas,p:Paint,left:Float,top:Float,width:Float,height:Float,
  col:Int,shift:Float=0f){
  val save=c.save();c.clipRect(left,top,left+width,top+height)
  p.style=Paint.Style.STROKE;p.strokeWidth=2.7f
  p.color=col
  var i=-3
  while(i<24){
   val x=left+i*43f+shift
   c.drawLine(x,top+height,x+height*.68f,top,p);i++
  }
  p.style=Paint.Style.FILL;c.restoreToCount(save)
 }
 private fun awardMotion(c:Canvas,p:Paint,a:Award,row:Int,accent:Int,name:String,
  teamLeft:Float,teamWidth:Float,scoreLeft:Float,scoreWidth:Float,rowY:Float,rowH:Float){
  if(a.team!=row)return
  val t=a.progress.coerceIn(0f,1f)
  if(t>=.93f)return
  val panelX=teamLeft;val right=panelX+teamWidth
  val clip=c.save()
  c.clipRect(panelX,rowY,right,rowY+rowH)
  // First 12% wipes INTO the logo/identity artwork. During the middle
  // hold, diagonal energy and oversized team lettering travel across row.
  val reveal=(t/.16f).coerceIn(0f,1f)
  val retreat=((.88f-t)/.16f).coerceIn(0f,1f)
  val visible=minOf(reveal,retreat)
  val left=panelX+(1f-visible)*teamWidth
  gradient(c,p,left,rowY,teamWidth*visible,rowH,
   Color.rgb(17,34,37),Color.rgb(36,78,66))
  streaks(c,p,panelX,rowY,teamWidth,rowH,accent,180f*t)
  val slant=Path().apply{
   moveTo(left+teamWidth*visible*.6f,rowY)
   lineTo(left+teamWidth*visible*.6f+40f,rowY)
   lineTo(left+teamWidth*visible*.6f,rowY+rowH)
   lineTo(left+teamWidth*visible*.6f-40f,rowY+rowH)
   close()
  }
  p.color=0x66FFFFFF;p.style=Paint.Style.FILL;c.drawPath(slant,p)
  if(visible>.55f){
   words(c,p,initials(name),panelX+teamWidth*.53f+18f*(.5f-t),
    rowY+rowH*.76f,43f,WHITE,true)
  }
  c.restoreToCount(clip)
  if(t>.13f && t<.76f){
   // Score cell briefly becomes the point award, not an ordinary score pulse.
   gradient(c,p,scoreLeft,rowY,scoreWidth,rowH,Color.rgb(18,27,30),Color.rgb(8,12,16))
   val pulse=if(t<.26f)(t-.13f)/.13f else 1f
   words(c,p,"+${a.points}",scoreLeft+scoreWidth/2,rowY+rowH*.77f,
    40f+4f*pulse,accent,true)
   stroke(c,p,scoreLeft,rowY,scoreWidth,rowH,0x88FFFFFF.toInt())
  }else if(t>=.76f&&t<.94f){
   val sweep=((t-.76f)/.18f).coerceIn(0f,1f)
   gradient(c,p,scoreLeft,rowY,scoreWidth,rowH,
    0xFFF7FEFA.toInt(),0xFFCEE6D9.toInt())
   rect(c,p,scoreLeft+scoreWidth*sweep-11f,rowY,13f,rowH,accent)
  }
 }
 // COURTSIDE — a separate wide NBA-inspired sports-TV composition:
 // facing horizontal team nameplates, independent number windows and
 // a vertical chrome live-time tower rather than stacked score strips.
 private fun drawCourtside(c:Canvas,p:Paint,score:State,award:Award?){
  gradient(c,p,0f,0f,960f,144f,0xFF111F2A.toInt(),0xFF05080F.toInt())
  streaks(c,p,0f,0f,960f,144f,0x2279C5F3,0f)
  rect(c,p,10f,9f,122f,127f,0xFF071315.toInt(),12f)
  gradient(c,p,11f,12f,120f,8f,0xFF5EE6CC.toInt(),0xFF166DB2.toInt())
  words(c,p,"IMG",69f,68f,51f,WHITE,true)
  words(c,p,"COURTSIDE",69f,97f,17f,0xFF87D9F3.toInt(),true)
  words(c,p,"LIVE",69f,120f,18f,GREY,true)
  val nameX=floatArrayOf(146f,452f)
  val numX=floatArrayOf(350f,654f)
  val names=listOf(short(score.home),short(score.away))
  val values=listOf(score.homeScore,score.awayScore)
  for(team in 0..1){
   val x=nameX[team];val y=23f
   gradient(c,p,x,y,204f,101f,
    if(team==0)0xFF215F72.toInt() else 0xFF6C3341.toInt(),
    0xFF111C26.toInt())
   val accent=if(team==0)0xFF64F4D3.toInt() else 0xFFFFB08F.toInt()
   val sl=Path().apply{
    moveTo(x+171f,y);lineTo(x+204f,y)
    lineTo(x+180f,y+101f);lineTo(x+151f,y+101f);close()
   }
   p.color=0x553C82A1;p.style=Paint.Style.FILL;c.drawPath(sl,p)
   streaks(c,p,x,y,204f,101f,if(team==0)0x2232F7C2 else 0x33EF8B85)
   words(c,p,team.let{if(it==0)"HOME" else "AWAY"},
    x+100f,y+22f,17f,GREY,true)
   words(c,p,names[team].take(11),x+96f,y+65f,
    if(names[team].length>9)26f else 31f,WHITE,true)
   rect(c,p,x+13f,y+82f,113f,5f,accent,2f)
   stroke(c,p,x,y,204f,101f,0x776EA19F)
   gradient(c,p,numX[team],y,97f,101f,0xFFF9FFF8.toInt(),0xFFBCCED2.toInt())
   words(c,p,values[team].coerceIn(0,999).toString(),
    numX[team]+48f,y+77f,58f,0xFF10181D.toInt(),true)
   stroke(c,p,numX[team],y,97f,101f,0xFFA9CFD3.toInt())
   if(award?.team==team){
    awardMotion(c,p,award,team,accent,names[team],
     x,204f,numX[team],97f,y,101f)
   }
  }
  gradient(c,p,753f,13f,197f,72f,0xFF203346.toInt(),0xFF080B15.toInt())
  words(c,p,score.clock.ifBlank{"--:--"},851f,66f,44f,WHITE,true)
  gradient(c,p,753f,87f,197f,43f,0xFF111E2A.toInt(),0xFF060B14.toInt())
  val quarter=if(score.sport=="pickleball")"GAME ${score.period}" else
   if(score.period<=4)"Q${score.period}" else "OT${score.period-4}"
  words(c,p,quarter,849f,117f,27f,0xFF7DE8E9.toInt(),true)
  rect(c,p,142f,134f,809f,5f,0xFF62DDD9.toInt())
 }
 fun render(score:State,opacity:Float=1f,award:Award?=null,style:Int=0):Bitmap{
  val image=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
  val alpha=(opacity.coerceIn(0f,1f)*255f).toInt()
  if(alpha==0)return image
  val c=Canvas(image);val p=Paint(Paint.ANTI_ALIAS_FLAG)
  val layer=c.saveLayerAlpha(0f,0f,W.toFloat(),H.toFloat(),alpha)
  val court=style==1
  val accent=if(court)GOLD else GREEN
  val isPickle=score.sport=="pickleball"
  if(court){
   drawCourtside(c,p,score,award)
   c.restoreToCount(layer)
   return image
  }

  // Two original sports-TV broadcast treatments. Both have non-flat
  // metallic gradient material, layered panels, grillwork, and wipe motion.
  rect(c,p,1f,1f,958f,142f,I,14f)
  stroke(c,p,2f,2f,956f,140f,0xFF6D897D.toInt())
  gradient(c,p,0f,0f,163f,144f,
   if(court)0xFF28241E.toInt() else 0xFF0B2A24.toInt(),
   0xFF0B1013.toInt())
  streaks(c,p,0f,0f,163f,144f,0x225DEDB7,5f)
  words(c,p,"IMG",83f,57f,54f,WHITE,true)
  rect(c,p,33f,67f,100f,3f,accent,1f)
  words(c,p,"BROADCAST",83f,97f,20f,accent,true)
  words(c,p,"GRAPHICS",83f,116f,17f,GREY)

  val names=listOf(short(score.home),short(score.away))
  val scores=listOf(score.homeScore,score.awayScore)
  val fouls=listOf(score.homeFouls,score.awayFouls)
  for(team in 0..1){
   val y=8f+team*64f
   val top=if(court)0xFFE4C787.toInt() else 0xFF204D42.toInt()
   val bottom=if(court)0xFFAA8650.toInt() else 0xFF102C29.toInt()
   gradient(c,p,167f,y,405f,60f,top,bottom)
   streaks(c,p,167f,y,405f,60f,if(court)0x339C3C13 else 0x3352B89A)
   rect(c,p,176f,y+51f,83f,4f,if(team==0)accent else 0xFFD3E0DB.toInt(),1f)
   rect(c,p,269f,y+51f,46f,4f,0x885A675F.toInt(),1f)
   rect(c,p,326f,y+51f,53f,4f,0x885A675F.toInt(),1f)
   val labelSize=if(names[team].length>12)26f else 31f
   words(c,p,names[team],367f,y+38f,labelSize,
    if(court)0xFF161714.toInt() else WHITE,true)
   gradient(c,p,573f,y,126f,60f,WHITE,0xFFCBD7D0.toInt())
   words(c,p,scores[team].coerceIn(0,999).toString(),634f,y+44f,46f,
    0xFF11171A.toInt(),true)
   if(!isPickle){
    words(c,p,"F ${fouls[team].coerceIn(0,99)}",704f,y+55f,13f,GREY,true,
     Paint.Align.LEFT)
   }
   stroke(c,p,166f,y,533f,60f,0x995E806B.toInt())
  }
  gradient(c,p,704f,7f,251f,67f,0xFF29373D.toInt(),0xFF0A1115.toInt())
  words(c,p,score.clock.ifBlank{"--:--"},832f,55f,51f,WHITE,true)
  gradient(c,p,704f,75f,251f,61f,0xFF18272C.toInt(),0xFF070A0C.toInt())
  val quarter=if(isPickle)"GAME ${score.period}" else if(score.period<=4)"Q${score.period}"
   else "OT${score.period-4}"
  words(c,p,quarter,782f,118f,29f,WHITE,true)
  words(c,p,if(isPickle)"PICKLEBALL" else "BASKETBALL",892f,118f,18f,accent,true)
  rect(c,p,704f,71f,251f,3f,accent)
  rect(c,p,160f,135f,797f,7f,accent)

  if(award!=null && !isPickle){
   awardMotion(c,p,award,0,accent,names[0],167f,405f,573f,126f,8f,60f)
   awardMotion(c,p,award,1,accent,names[1],167f,405f,573f,126f,72f,60f)
  }
  c.restoreToCount(layer)
  return image
 }
}
