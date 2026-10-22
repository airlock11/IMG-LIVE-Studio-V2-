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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native-only basketball TV broadcast graphic renderer.
 * Draws the identical bitmap for Camera2 GL preview and H.264 stream.
 * Twelve actual broadcast compositions in four different proportions.
 * No WebView art, mock stream, stock flat template, or official league marks.
 */
internal object V2BroadcastGraphicsRenderer {
 val names=arrayOf("Prime TV","Courtside A","Courtside B","Arena Glass",
  "Rivalry Plate","Center Dial","Broadcast Classic","Crown Pill",
  "Split Box","Match Cube","Edge Box","Legacy TV")
 fun compact(style:Int)=style in 8..11
 fun width(style:Int)=if(compact(style))420 else 960
 fun height(style:Int)=if(compact(style))420 else 144
 private const val WHITE=0xFFF9FBF8.toInt()
 private const val SILVER=0xFFCBD7D5.toInt()
 private const val INK=0xFF111D28.toInt()
 private val shades=arrayOf(
  intArrayOf(0xFF214D7B.toInt(),0xFF852E41.toInt(),0xFFF4B949.toInt()),
  intArrayOf(0xFF276985.toInt(),0xFF9C293D.toInt(),0xFFDDCBA0.toInt()),
  intArrayOf(0xFFC58A1C.toInt(),0xFF114C93.toInt(),0xFFFFD146.toInt()),
  intArrayOf(0xFF346B95.toInt(),0xFF762D53.toInt(),0xFFBFDDE7.toInt()),
  intArrayOf(0xFF8C1F33.toInt(),0xFF204C99.toInt(),0xFFFFCC60.toInt()),
  intArrayOf(0xFF21787C.toInt(),0xFF9C5344.toInt(),0xFF7AE6D0.toInt()),
  intArrayOf(0xFF1C4774.toInt(),0xFF9A293D.toInt(),0xFFEDE5D7.toInt()),
  intArrayOf(0xFFC59824.toInt(),0xFF0B4F99.toInt(),0xFFFFCC57.toInt()),
  intArrayOf(0xFF8A263B.toInt(),0xFF164DA8.toInt(),0xFFF2CD55.toInt()),
  intArrayOf(0xFF367C80.toInt(),0xFF913046.toInt(),0xFFECEDF0.toInt()),
  intArrayOf(0xFF215D8D.toInt(),0xFFA04D28.toInt(),0xFFF3B65D.toInt()),
  intArrayOf(0xFF8E2132.toInt(),0xFF174AA2.toInt(),0xFFD8B949.toInt())
 )
 private fun mix(a:Int,b:Int,t:Float):Int{
  val p=t.coerceIn(0f,1f)
  return Color.argb((Color.alpha(a)*(1f-p)+Color.alpha(b)*p).roundToInt(),
   (Color.red(a)*(1f-p)+Color.red(b)*p).roundToInt(),
   (Color.green(a)*(1f-p)+Color.green(b)*p).roundToInt(),
   (Color.blue(a)*(1f-p)+Color.blue(b)*p).roundToInt())
 }
 private fun shape(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,color:Int,r:Float=0f){
  p.shader=null;p.style=Paint.Style.FILL;p.color=color
  c.drawRoundRect(RectF(x,y,x+w,y+h),r,r,p)
 }
 private fun bevel(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,primary:Int,
  bottom:Int,r:Float=6f,outline:Int=SILVER){
  p.color=WHITE;p.style=Paint.Style.FILL
  p.shader=LinearGradient(x,y,x+12f,y+h,
   intArrayOf(mix(primary,WHITE,.30f),primary,mix(primary,bottom,.68f),bottom),
   floatArrayOf(0f,.23f,.60f,1f),Shader.TileMode.CLAMP)
  c.drawRoundRect(RectF(x,y,x+w,y+h),r,r,p);p.shader=null
  p.color=Color.argb(110,255,255,255);p.style=Paint.Style.STROKE;p.strokeWidth=1.8f
  c.drawRoundRect(RectF(x+1,y+1,x+w-1,y+h-1),r,r,p)
  p.color=outline;p.strokeWidth=1.5f
  c.drawRoundRect(RectF(x,y,x+w,y+h),r,r,p);p.style=Paint.Style.FILL
  shape(c,p,x+5,y+5,w-10f,max(2f,h*.045f),Color.argb(48,255,255,255),2f)
 }
 private fun label(c:Canvas,p:Paint,value:String,x:Float,y:Float,size:Float,color:Int=WHITE,
  align:Paint.Align=Paint.Align.CENTER,maxW:Float=Float.MAX_VALUE){
  p.shader=null;p.style=Paint.Style.FILL;p.color=color
  p.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
  p.textSize=size;p.textAlign=align
  val normalScale=p.textScaleX
  if(maxW<Float.MAX_VALUE && p.measureText(value)>maxW)
   p.textScaleX=(maxW/p.measureText(value)).coerceIn(.48f,1f)
  c.drawText(value,x,y,p)
  p.textScaleX=normalScale
 }
 private fun mark(c:Canvas,p:Paint,x:Float,y:Float,r:Float,color:Int,
  name:String,logo:Bitmap?=null){
  // Embossed circular team medallion. Real user team PNG when supplied.
  shape(c,p,x-r-3,y-r-3,2*r+6,2*r+6,0xFF0D141B.toInt(),r+3)
  p.shader=LinearGradient(x-r,y-r,x+r,y+r,WHITE,0xFF586771.toInt(),Shader.TileMode.CLAMP)
  p.style=Paint.Style.FILL
  c.drawCircle(x,y,r+1f,p);p.shader=null
  p.color=mix(color,INK,.42f);c.drawCircle(x,y,r-2f,p)
  if(logo!=null&&!logo.isRecycled){
   val save=c.save()
   val clip=Path().apply{addCircle(x,y,r-4f,Path.Direction.CW)}
   c.clipPath(clip)
   c.drawBitmap(logo,null,RectF(x-r+4,y-r+4,x+r-4,y+r-4),p)
   c.restoreToCount(save)
  }else{
   val initials=name.trim().split(' ').filter{it.isNotEmpty()}.take(2)
    .joinToString(""){it.take(1).uppercase()}.ifEmpty{"IMG"}
   label(c,p,initials,x,y+r*.29f,r*.9f,WHITE)
  }
  p.color=Color.argb(90,255,255,255);p.style=Paint.Style.STROKE;p.strokeWidth=1.5f
  c.drawArc(RectF(x-r+3,y-r+3,x+r-3,y+r-3),205f,135f,false,p)
  p.style=Paint.Style.FILL
 }
 private fun hardware(c:Canvas,p:Paint,x:Float,y:Float,w:Float,h:Float,color:Int){
  val save=c.save();c.clipRect(x,y,x+w,y+h)
  p.color=Color.argb(34,240,248,245);p.strokeWidth=1.6f;p.style=Paint.Style.STROKE
  var j=-4
  while(j<22){
   val left=x+j*46f;c.drawLine(left,y+h,left+h*.72f,y,p);j++
  }
  p.style=Paint.Style.FILL
  shape(c,p,x+3,y+h-5,w-6f,2f,color)
  c.restoreToCount(save)
 }
 private fun identityTransition(c:Canvas,p:Paint,a:V2Scorecard.Award?,
  team:Int,x:Float,y:Float,w:Float,h:Float,style:Int,accent:Int,
  teamColor:Int,name:String,logo:Bitmap?){
  if(a==null||a.team!=team)return
  val t=a.progress.coerceIn(0f,1f)
  if(t>=.89f)return
  val on=(t/.14f).coerceIn(0f,1f)
  val off=((.86f-t)/.15f).coerceIn(0f,1f)
  val reveal=min(on,off)
  val save=c.save()
  c.clipRect(x,y,x+w,y+h)
  // A metal shutter wipes across the SCORING TEAM NAMEPLATE; this is
  // separate from the +points score-cell takeover and never stops the clock.
  val rightToLeft=style in listOf(0,3,4,6,8,11)
  val visible=w*reveal
  val start=if(rightToLeft)x+w-visible else x
  c.clipRect(start,y,start+visible,y+h)
  val tone=if(style in listOf(1,5,9))mix(teamColor,WHITE,.26f) else teamColor
  bevel(c,p,x,y,w,h,tone,mix(teamColor,INK,.78f),
   if(style in listOf(2,7))h*.40f else 3f,accent)
  hardware(c,p,x,y,w,h,WHITE)
  // Dedicated glowing light stripe tracks the leading edge of the wipe.
  val edge=if(rightToLeft)start else start+visible
  p.color=Color.argb((130+95*reveal).roundToInt(),255,244,200)
  p.strokeWidth=max(4f,h*.075f)
  c.drawLine(edge,y,edge-h*.22f,y+h,p)
  val logoX=if(style in listOf(2,5,7))x+w*.42f else x+w*.18f
  val rad=min(h*.38f,w*.14f)
  mark(c,p,logoX,y+h*.48f,rad,teamColor,name,logo)
  label(c,p,name.take(10).uppercase(),x+w*.62f,y+h*.58f,
   min(h*.42f,w*.115f),WHITE,Paint.Align.CENTER,w*.47f)
  shape(c,p,x+w*.46f,y+h*.78f,w*.46f,h*.055f,accent,2f)
  // Distinct choreography / moving light surfaces per graphic family.
  when(style){
   0,6->hardware(c,p,x+w*(.1f+t*.67f),y,w*.2f,h,accent)
   1,8->{
    for(i in 0..3)shape(c,p,x+w*.13f+i*w*.09f,y+h*.92f,
     w*.055f,h*.04f,if(i<=t*4)accent else 0xFF283039.toInt(),2f)
   }
   2,5->{
    p.style=Paint.Style.STROKE;p.strokeWidth=max(2f,h*.05f)
    p.color=Color.argb(145,255,239,201)
    c.drawCircle(x+w*.42f,y+h*.48f,rad+max(1f,t*28f),p)
    p.style=Paint.Style.FILL
   }
   3,7->{
    val sl=Path().apply{
     moveTo(x+w*(t-.05f),y);lineTo(x+w*(t+.14f),y)
     lineTo(x+w*(t-.02f),y+h);lineTo(x+w*(t-.18f),y+h);close()
    }
    p.color=Color.argb(85,255,255,255);c.drawPath(sl,p)
   }
   4,10->{
    p.color=Color.argb(120,255,227,146)
    c.drawRoundRect(RectF(x+w*.46f,y+h*.13f,x+w*.91f,y+h*.27f),4f,4f,p)
   }
   else->{}
  }
  c.restoreToCount(save)
 }
 private fun pointsFlash(c:Canvas,p:Paint,a:V2Scorecard.Award?,team:Int,
  x:Float,y:Float,w:Float,h:Float,accent:Int,style:Int){
  if(a==null||a.team!=team)return
  val t=a.progress.coerceIn(0f,1f)
  if(t>=.93f)return
  val enter=(t/.14f).coerceIn(0f,1f)
  val exit=((.91f-t)/.15f).coerceIn(0f,1f)
  val amount=min(enter,exit)
  val clip=c.save();c.clipRect(x,y,x+w,y+h)
  when(style%4){
   0->{
    val sweep=x+w*(1f-amount)
    bevel(c,p,sweep,y,w*amount,h,accent,mix(accent,INK,.68f),0f,accent)
    hardware(c,p,x,y,w,h,WHITE)
   }
   1->{
    // Ribbed metallic number-roll reveal.
    bevel(c,p,x,y,w,h,mix(accent,WHITE,.37f),accent,4f,WHITE)
    val offset=(1f-enter)*h
    shape(c,p,x,y+offset,w,h*.16f,Color.argb(125,255,255,255))
   }
   2->{
    bevel(c,p,x,y,w,h,mix(accent,INK,.35f),INK,12f,accent)
    val circle=Path().apply{addCircle(x+w*.5f,y+h*.5f,max(w,h)*.5f*amount,Path.Direction.CW)}
    p.color=Color.argb(100,255,255,255);p.style=Paint.Style.STROKE;p.strokeWidth=4f
    c.drawPath(circle,p);p.style=Paint.Style.FILL
   }
   else->{
    bevel(c,p,x,y,w,h,mix(accent,WHITE,.25f),INK,5f,accent)
    val line=x+w*(t*1.4f-.25f)
    p.color=Color.argb(180,255,255,255);p.strokeWidth=12f
    c.drawLine(line,y,line+w*.15f,y+h,p)
   }
  }
  if(t>.1f&&t<.79f)label(c,p,"+${a.points}",x+w*.5f,y+h*.73f,min(h*.78f,w*.60f),WHITE)
  c.restoreToCount(clip)
 }
 private fun clock(c:Canvas,p:Paint,score:V2Scorecard.State,
  x:Float,y:Float,w:Float,h:Float,style:Int,accent:Int){
  if(style==2||style==5){
   mark(c,p,x+w*.5f,y+h*.5f,min(w,h)*.48f,accent,"IMG",null)
   shape(c,p,x+w*.09f,y+h*.25f,w*.82f,h*.53f,0xE70C1923.toInt(),h*.12f)
  }else bevel(c,p,x,y,w,h,0xFF313C45.toInt(),0xFF080D13.toInt(),8f,0xFF8899A1.toInt())
  val period=if(score.sport=="pickleball")"GAME ${score.period}"
   else if(score.period<=4)"${score.period}TH" else "OT${score.period-4}"
  label(c,p,score.clock.ifBlank{"10:00"},x+w*.50f,y+h*.50f,min(42f,h*.34f),WHITE)
  label(c,p,period,x+w*.5f,y+h*.74f,min(25f,h*.19f),accent)
  if(score.sport!="pickleball")label(c,p,"24",x+w*.87f,y+h*.91f,min(20f,h*.15f),0xFFF4B638.toInt())
 }
 private fun team(c:Canvas,p:Paint,score:V2Scorecard.State,index:Int,
  x:Float,y:Float,w:Float,h:Float,style:Int,color:Int,accent:Int,
  logo:Bitmap?,award:V2Scorecard.Award?){
  val name=(if(index==0)score.home else score.away).uppercase().take(14)
  val points=(if(index==0)score.homeScore else score.awayScore).coerceIn(0,999)
  val fouls=(if(index==0)score.homeFouls else score.awayFouls).coerceIn(0,99)
  val rim=if(style in listOf(1,3,6,9))SILVER else 0xFF778E99.toInt()
  bevel(c,p,x,y,w,h,color,mix(color,INK,.84f),if(style in listOf(2,7))h*.37f else 7f,rim)
  hardware(c,p,x,y,w,h,accent)
  val logoR=min(h*.30f,w*.115f)
  mark(c,p,x+logoR+9f,y+h*.46f,logoR,color,name,logo)
  val teamSpace=w*.45f
  val largeName=if(name.length>9)min(22f,h*.23f) else min(29f,h*.30f)
  label(c,p,name,x+logoR*2f+14f,y+h*.46f,largeName,WHITE,
   Paint.Align.LEFT,teamSpace)
  val scoreX=x+w*.83f
  bevel(c,p,x+w*.71f,y+5f,w*.25f,h-10f,
   if(style in listOf(1,6,9))0xFFE4E9E6.toInt() else 0xFF1B242E.toInt(),
   if(style in listOf(1,6,9))0xFF819399.toInt() else 0xFF0A1018.toInt(),4f,rim)
  val dark=style in listOf(1,6,9)
  label(c,p,points.toString(),scoreX,y+h*.67f,min(h*.66f,w*.24f),
   if(dark)INK else WHITE)
  if(score.sport=="basketball"){
   val bonus=if(fouls>=5)" BONUS" else ""
   label(c,p,"FOULS ${fouls}${bonus}",x+w*.34f,y+h*.89f,min(19f,h*.15f),SILVER)
   // Possession and bonus diode sequence, not generic separator dots.
   for(j in 0..4){
    p.color=if(j<fouls.coerceAtMost(5))accent else 0xFF33373F.toInt()
    c.drawCircle(x+w*.08f+j*w*.052f,y+h*.84f,max(2.2f,h*.027f),p)
   }
  }
  identityTransition(c,p,award,index,x,y,w*.70f,h,style,accent,color,name,logo)
  pointsFlash(c,p,award,index,x+w*.71f,y+5f,w*.25f,h-10f,accent,style)
 }
 private fun wide(c:Canvas,p:Paint,score:V2Scorecard.State,a:V2Scorecard.Award?,
  style:Int,c1:Int,c2:Int,accent:Int,logos:Array<Bitmap?>){
  val id=style
  p.setShadowLayer(19f,1f,9f,0xB8000000.toInt())
  shape(c,p,7f,7f,946f,126f,0xFF06080C.toInt(),if(id==2||id==7)60f else 12f)
  p.clearShadowLayer()
  val left=if(id in listOf(0,6))24f else 18f
  val teamW=if(id==2||id==5)334f else if(id==7)349f else 338f
  if(id==2||id==7){
   bevel(c,p,14f,13f,930f,112f,0xFFBFCBCE.toInt(),0xFF3F4C55.toInt(),59f,SILVER)
  }
  team(c,p,score,0,left,17f,teamW,101f,id,c1,accent,logos[0],a)
  team(c,p,score,1,960f-left-teamW,17f,teamW,101f,id,c2,accent,logos[1],a)
  val cx=960f*.5f
  val towerW=if(id==2||id==5)191f else 210f
  clock(c,p,score,cx-towerW*.5f,20f,towerW,94f,id,accent)
  if(id in listOf(0,1,4,6)){
   bevel(c,p,31f,125f,898f,15f,0xFF333F46.toInt(),0xFF070A0D.toInt(),3f,SILVER)
   label(c,p,"IMG  •  LIVE",485f,136f,14f,SILVER)
  }else if(id==2||id==5){
   bevel(c,p,366f,108f,229f,32f,0xFF9CA7AA.toInt(),0xFF0D1219.toInt(),5f,SILVER)
   label(c,p,"BROADCAST",480f,130f,21f,WHITE)
  }else if(id==7){
   val plate=Path().apply{
    moveTo(390f,111f);lineTo(570f,111f)
    lineTo(546f,140f);lineTo(410f,140f);close()
   }
   p.color=0xFF151C22.toInt();c.drawPath(plate,p)
   label(c,p,"IMG · PRIME",480f,131f,16f,SILVER)
  }
 }
 private fun compactBoard(c:Canvas,p:Paint,s:V2Scorecard.State,a:V2Scorecard.Award?,
  style:Int,c1:Int,c2:Int,accent:Int,logos:Array<Bitmap?>){
  p.setShadowLayer(18f,0f,11f,0xD0000000.toInt())
  shape(c,p,13f,7f,394f,397f,0xFF05090D.toInt(),12f);p.clearShadowLayer()
  if(style==9||style==8){
   bevel(c,p,21f,16f,378f,377f,SILVER,0xFF43535A.toInt(),7f,SILVER)
  }else bevel(c,p,20f,15f,380f,376f,0xFF354148.toInt(),0xFF080B10.toInt(),11f,SILVER)
  if(style==8||style==11){
   team(c,p,s,0,30f,38f,360f,141f,style,c1,accent,logos[0],a)
   team(c,p,s,1,30f,183f,360f,141f,style,c2,accent,logos[1],a)
   clock(c,p,s,81f,329f,258f,55f,style,accent)
  }else{
   // Two narrow side-by-side vertical team towers and a spanning bottom clock.
   for(i in 0..1){
    val x=29f+i*185f
    val color=if(i==0)c1 else c2
    val teamName=if(i==0)s.home else s.away
    val pts=if(i==0)s.homeScore else s.awayScore
    val foul=if(i==0)s.homeFouls else s.awayFouls
    bevel(c,p,x,38f,178f,284f,color,mix(color,INK,.83f),5f,SILVER)
    mark(c,p,x+89f,93f,43f,color,teamName,logos[i])
    label(c,p,teamName.take(10).uppercase(),x+89f,162f,27f)
    bevel(c,p,x+20f,181f,138f,93f,0xFFE3E9EA.toInt(),0xFF81939C.toInt(),3f,SILVER)
    label(c,p,pts.toString(),x+89f,251f,69f,INK)
    label(c,p,"FOULS: ${foul}",x+89f,306f,22f)
    identityTransition(c,p,a,i,x,38f,178f,134f,style,accent,color,teamName,logos[i])
    pointsFlash(c,p,a,i,x+20f,181f,138f,93f,accent,style)
   }
   clock(c,p,s,82f,333f,256f,50f,style,accent)
  }
 }
 fun render(score:V2Scorecard.State,opacity:Float,
  award:V2Scorecard.Award?,style:Int,homeLogo:Bitmap?,awayLogo:Bitmap?):Bitmap{
  val id=style.coerceIn(0,11)
  val image=Bitmap.createBitmap(width(id),height(id),Bitmap.Config.ARGB_8888)
  val alpha=(opacity.coerceIn(0f,1f)*255f).roundToInt()
  if(alpha==0)return image
  val c=Canvas(image);val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  val layer=c.saveLayerAlpha(0f,0f,image.width.toFloat(),image.height.toFloat(),alpha)
  val palette=shades[id]
  if(compact(id))compactBoard(c,p,score,award,id,palette[0],palette[1],palette[2],arrayOf(homeLogo,awayLogo))
  else wide(c,p,score,award,id,palette[0],palette[1],palette[2],arrayOf(homeLogo,awayLogo))
  c.restoreToCount(layer)
  return image
 }
}