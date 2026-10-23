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
  if(w<=0f||h<=0f)return
  val bounds=RectF(x,y,x+w,y+h)
  p.shader=null;p.style=Paint.Style.FILL
  // Dark extrusion beneath a chrome boundary. The plate's top glass
  // reflection and beveled lower edge reproduce TV-broadcast depth,
  // instead of solid-color rectangular graphics.
  p.color=0xB6000000.toInt()
  c.drawRoundRect(RectF(x+1.7f,y+max(2f,h*.035f),
   x+w+1.7f,y+h+max(2f,h*.035f)),r,r,p)
  p.color=outline
  c.drawRoundRect(bounds,r,r,p)
  val inset=max(2f,minOf(w,h)*.018f)
  p.shader=LinearGradient(x,y,x+max(14f,w*.14f),y+h,
   intArrayOf(
    mix(primary,WHITE,.60f),
    mix(primary,WHITE,.23f),
    primary,
    mix(primary,bottom,.52f),
    bottom,
    mix(bottom,Color.BLACK,.25f)
   ),
   floatArrayOf(0f,.055f,.28f,.54f,.83f,1f),
   Shader.TileMode.CLAMP)
  c.drawRoundRect(RectF(x+inset,y+inset,x+w-inset,y+h-inset),
   max(1f,r-inset),max(1f,r-inset),p)
  p.shader=null
  val save=c.save()
  c.clipPath(Path().apply{
   addRoundRect(RectF(x+inset,y+inset,x+w-inset,y+h-inset),
    max(1f,r-inset),max(1f,r-inset),Path.Direction.CW)
  })
  // Semi-transparent oblique glass glint and a fine metallic top rail.
  val glass=Path().apply{
   moveTo(x+w*.09f,y+inset);lineTo(x+w*.84f,y+inset)
   lineTo(x+w*.47f,y+h*.42f);lineTo(x-w*.11f,y+h*.42f)
   close()
  }
  p.shader=LinearGradient(x,y,x,y+h*.50f,
   0x66FFFFFF,0x04FFFFFF,Shader.TileMode.CLAMP)
  c.drawPath(glass,p);p.shader=null
  shape(c,p,x+w*.08f,y+h*.07f,w*.84f,max(1f,h*.022f),0x55FFFFFF,1f)
  shape(c,p,x+w*.06f,y+h*.92f,w*.88f,max(1.3f,h*.020f),0x77000000,1f)
  c.restoreToCount(save)
  p.color=Color.argb(160,243,250,245);p.style=Paint.Style.STROKE
  p.strokeWidth=max(1f,minOf(w,h)*.013f)
  c.drawRoundRect(RectF(x+.7f,y+.7f,x+w-.7f,y+h-.7f),r,r,p)
  p.color=Color.argb(90,0,0,0);p.strokeWidth=max(1.1f,minOf(w,h)*.012f)
  c.drawRoundRect(RectF(x+inset,y+inset,x+w-inset,y+h-inset),
   max(1f,r-inset),max(1f,r-inset),p)
  p.style=Paint.Style.FILL;p.shader=null
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
 private fun wide(c:Canvas,p:Paint,s:V2Scorecard.State,a:V2Scorecard.Award?,
  id:Int,c1:Int,c2:Int,accent:Int,logos:Array<Bitmap?>){
  val names=arrayOf(s.home.uppercase().take(12),s.away.uppercase().take(12))
  val points=intArrayOf(s.homeScore,s.awayScore)
  val fouls=intArrayOf(s.homeFouls,s.awayFouls)
  val col=intArrayOf(c1,c2)
  fun num(i:Int,x:Float,y:Float,w:Float,h:Float,light:Boolean=false){
   bevel(c,p,x,y,w,h,
    if(light)0xFFF5F7F4.toInt() else 0xFF21232B.toInt(),
    if(light)0xFFABBAC2.toInt() else 0xFF07090D.toInt(),3f,SILVER)
   label(c,p,points[i].toString(),x+w*.5f,y+h*.80f,min(h*.83f,w*.8f),
    if(light)INK else WHITE)
   pointsFlash(c,p,a,i,x,y,w,h,accent,id)
  }
  fun badge(i:Int,x:Float,y:Float,r:Float){
   mark(c,p,x,y,r,col[i],names[i],logos[i])
  }
  fun awardName(i:Int,x:Float,y:Float,w:Float,h:Float){
   identityTransition(c,p,a,i,x,y,w,h,id,accent,col[i],names[i],logos[i])
  }
  fun lights(i:Int,x:Float,y:Float,vertical:Boolean){
   for(j in 0..4){
    p.color=if(j<fouls[i].coerceAtMost(5))accent else 0xFF303238.toInt()
    val px=if(vertical)x else x+j*20f
    val py=if(vertical)y+j*15f else y
    c.drawCircle(px,py,6f,p)
   }
  }
  fun banner(x:Float,y:Float,w:Float,code:String){
   bevel(c,p,x,y,w,24f,0xFF30363D.toInt(),0xFF14191D.toInt(),2f,SILVER)
   label(c,p,code,x+w*.5f,y+17f,17f,SILVER)
  }
  when(id){
   0->{ // Long broadcast plate: two colored team sections, white score, right match clock.
    bevel(c,p,12f,5f,936f,136f,0xFF353839.toInt(),0xFF05080D.toInt(),5f,SILVER)
    for(i in 0..1){
     val x=18f+i*343f
     bevel(c,p,x,13f,343f,81f,col[i],mix(col[i],INK,.66f),1f,col[i])
     badge(i,x+51f,52f,36f)
     label(c,p,names[i],x+146f,66f,42f,WHITE,Paint.Align.CENTER,170f)
     num(i,x+264f,14f,77f,80f)
     awardName(i,x+89f,13f,173f,81f)
    }
    bevel(c,p,707f,13f,235f,81f,0xFF424447.toInt(),0xFF14191E.toInt(),2f,SILVER)
    label(c,p,if(s.period<=4)"${s.period}RD" else "OT",751f,65f,27f,SILVER)
    bevel(c,p,802f,22f,93f,62f,WHITE,0xFFBBC5C8.toInt(),10f,INK)
    label(c,p,s.clock.ifEmpty{"10:00"},850f,67f,37f,INK)
    label(c,p,"24",918f,70f,49f,0xFFFFBA30.toInt())
    banner(18f,98f,323f,"IMG • LIVE SPORTS")
    banner(362f,98f,337f,"BASKETBALL • Q${s.period}")
    banner(704f,98f,238f,"BROADCAST GRAPHICS")
   }
   1->{ // Chrome capsule with team LEDs, center emblem and hanging clock extension.
    bevel(c,p,11f,2f,938f,109f,SILVER,0xFF182029.toInt(),53f,SILVER)
    bevel(c,p,22f,9f,916f,92f,0xFF3D4244.toInt(),0xFF090D11.toInt(),45f,0xFF65767E.toInt())
    bevel(c,p,32f,15f,240f,83f,c1,mix(c1,INK,.8f),43f,SILVER)
    bevel(c,p,686f,15f,240f,83f,c2,mix(c2,INK,.8f),43f,SILVER)
    badge(0,81f,54f,36f);badge(1,878f,54f,36f)
    label(c,p,names[0],187f,83f,32f)
    label(c,p,names[1],773f,83f,32f)
    lights(0,288f,20f,true);lights(1,669f,20f,true)
    num(0,315f,14f,120f,84f);num(1,527f,14f,120f,84f)
    awardName(0,117f,14f,168f,84f)
    awardName(1,673f,14f,168f,84f)
    mark(c,p,481f,55f,61f,accent,"IMG",null)
    bevel(c,p,324f,105f,489f,35f,0xFFE1E4E2.toInt(),0xFF27333B.toInt(),18f,SILVER)
    label(c,p,if(s.period<=4)"${s.period}TH" else "OT",380f,131f,33f,INK)
    label(c,p,s.clock.ifEmpty{"10:00"},554f,130f,38f,WHITE)
    label(c,p,"24",737f,130f,33f,0xFFFFCA53.toInt())
   }
   2->{ // Asymmetric beveled floating team nameplates + slim bottom ticker.
    p.setShadowLayer(14f,0f,8f,0xA0000000.toInt())
    bevel(c,p,9f,13f,455f,89f,c1,mix(c1,INK,.66f),9f,SILVER)
    bevel(c,p,496f,13f,455f,89f,c2,mix(c2,INK,.67f),9f,SILVER)
    p.clearShadowLayer()
    badge(0,62f,57f,37f);badge(1,904f,57f,37f)
    label(c,p,names[0],203f,55f,34f)
    label(c,p,names[1],750f,55f,34f)
    num(0,357f,22f,103f,78f);num(1,500f,22f,103f,78f)
    awardName(0,103f,17f,237f,84f);awardName(1,619f,17f,232f,84f)
    bevel(c,p,185f,108f,598f,34f,0xFF50545B.toInt(),0xFF080C12.toInt(),10f,SILVER)
    label(c,p,if(s.period<=4)"Q${s.period}" else "OT",266f,133f,24f)
    label(c,p,s.clock.ifBlank{"10:00"},485f,133f,26f)
    label(c,p,"SHOT 24",685f,133f,24f,accent)
   }
   3->{ // NBA-like circular mid-clock strip with opposing logos at the extreme edges.
    bevel(c,p,4f,20f,952f,98f,0xFF232B31.toInt(),0xFF080B0E.toInt(),5f,SILVER)
    bevel(c,p,8f,26f,473f,83f,c1,mix(c1,INK,.7f),3f,SILVER)
    bevel(c,p,480f,26f,473f,83f,c2,mix(c2,INK,.7f),3f,SILVER)
    badge(0,62f,68f,38f);badge(1,906f,68f,38f)
    label(c,p,names[0],195f,83f,39f)
    label(c,p,names[1],757f,83f,39f)
    num(0,323f,32f,113f,75f);num(1,520f,32f,113f,75f)
    awardName(0,108f,27f,212f,80f);awardName(1,637f,27f,213f,80f)
    mark(c,p,480f,66f,62f,accent,"IMG",null)
    shape(c,p,422f,58f,116f,48f,0xED111C25.toInt(),7f)
    label(c,p,s.clock.ifBlank{"10:00"},480f,78f,32f)
    label(c,p,"Q${s.period}   24",480f,99f,21f,accent)
    banner(12f,122f,936f,"FOULS ${s.homeFouls}                  IMG LIVE                  FOULS ${s.awayFouls}")
   }
   4->{ // Vertical stacked scoreboard with separate game-clock/bonus column.
    bevel(c,p,3f,4f,954f,134f,0xFF20272C.toInt(),0xFF05080C.toInt(),7f,SILVER)
    bevel(c,p,15f,13f,234f,57f,0xFF3B3D42.toInt(),0xFF14171B.toInt(),4f,SILVER)
    label(c,p,s.clock.ifBlank{"10:00"},131f,55f,57f)
    bevel(c,p,15f,75f,234f,51f,WHITE,0xFFB5C1C6.toInt(),4f,SILVER)
    label(c,p,"Q${s.period}   24",131f,112f,38f,INK)
    for(i in 0..1){
     val y=13f+i*59f
     bevel(c,p,252f,y,695f,57f,col[i],mix(col[i],INK,.66f),1f,SILVER)
     badge(i,310f,y+28f,24f)
     label(c,p,names[i],484f,y+43f,41f)
     num(i,778f,y+3f,164f,51f)
     awardName(i,352f,y+2f,419f,54f)
    }
   }
   5->{ // Clock and period circular orb floats over the middle of the whole plate.
    bevel(c,p,14f,14f,932f,106f,0xFF3B4650.toInt(),0xFF06090E.toInt(),8f,SILVER)
    for(i in 0..1){
     val x=if(i==0)21f else 539f
     bevel(c,p,x,20f,398f,96f,col[i],mix(col[i],INK,.73f),6f,SILVER)
     badge(i,x+43f,68f,36f)
     label(c,p,names[i],x+155f,65f,36f)
     num(i,x+276f,27f,108f,82f)
     awardName(i,x+87f,21f,188f,92f)
    }
    mark(c,p,479f,69f,66f,accent,"IMG",null)
    shape(c,p,432f,55f,94f,65f,0xEC121922.toInt(),18f)
    label(c,p,s.clock.ifBlank{"10:00"},479f,81f,31f)
    label(c,p,"Q${s.period} 24",479f,107f,24f,accent)
    banner(159f,119f,644f,"IMG • LIVE GAME GRAPHICS")
   }
   6->{ // Legacy network white plates, independent fouls and possession modules.
    bevel(c,p,5f,16f,950f,101f,0xFF333C43.toInt(),0xFF060A0F.toInt(),8f,SILVER)
    bevel(c,p,13f,24f,401f,81f,c1,mix(c1,INK,.55f),4f,SILVER)
    bevel(c,p,546f,24f,401f,81f,c2,mix(c2,INK,.55f),4f,SILVER)
    badge(0,64f,64f,34f);badge(1,895f,64f,34f)
    label(c,p,names[0],193f,67f,37f);label(c,p,names[1],772f,67f,37f)
    num(0,310f,32f,101f,70f,true);num(1,548f,32f,101f,70f,true)
    awardName(0,101f,27f,201f,74f);awardName(1,653f,27f,200f,74f)
    clock(c,p,s,419f,19f,122f,93f,6,accent)
    banner(17f,113f,926f,"TEAM FOULS  ${s.homeFouls}                    IMG • Q${s.period}                     TEAM FOULS ${s.awayFouls}")
   }
   else->{ // Polished rounded capsule with extended central score clock.
    bevel(c,p,8f,5f,944f,130f,SILVER,0xFF141B20.toInt(),64f,SILVER)
    bevel(c,p,20f,16f,920f,108f,0xFF2B363C.toInt(),0xFF070D13.toInt(),55f,SILVER)
    for(i in 0..1){
     val x=if(i==0)32f else 581f
     bevel(c,p,x,23f,347f,93f,col[i],mix(col[i],INK,.78f),45f,SILVER)
     badge(i,x+54f,69f,35f)
     label(c,p,names[i],x+174f,66f,32f)
     num(i,x+253f,31f,94f,76f)
     awardName(i,x+97f,24f,156f,88f)
    }
    mark(c,p,480f,71f,61f,accent,"IMG",null)
    shape(c,p,424f,53f,112f,62f,0xDB131A22.toInt(),15f)
    label(c,p,s.clock.ifEmpty{"10:00"},480f,81f,32f)
    label(c,p,"Q${s.period}  24",480f,105f,22f,accent)
   }
  }
 }
 private fun compactBoard(c:Canvas,p:Paint,s:V2Scorecard.State,a:V2Scorecard.Award?,
  style:Int,c1:Int,c2:Int,accent:Int,logos:Array<Bitmap?>){
  p.setShadowLayer(18f,0f,11f,0xD0000000.toInt())
  shape(c,p,13f,7f,394f,397f,0xFF05090D.toInt(),12f);p.clearShadowLayer()
  if(style==9||style==8){
   bevel(c,p,21f,16f,378f,377f,SILVER,0xFF43535A.toInt(),7f,SILVER)
  }else bevel(c,p,20f,15f,380f,376f,0xFF354148.toInt(),0xFF080B10.toInt(),11f,SILVER)
  when(style){
   8->{ // Split Box: fixed clock/bonus tower plus two stacked score plates.
    bevel(c,p,28f,37f,105f,283f,0xFF454A51.toInt(),0xFF080D12.toInt(),6f,SILVER)
    label(c,p,s.clock.ifBlank{"10:00"},80f,124f,42f)
    bevel(c,p,33f,159f,95f,76f,WHITE,0xFF9FAEB6.toInt(),4f,SILVER)
    label(c,p,"Q${s.period}",80f,211f,38f,INK)
    label(c,p,"BONUS",80f,271f,27f,accent)
    team(c,p,s,0,140f,38f,249f,137f,style,c1,accent,logos[0],a)
    team(c,p,s,1,140f,182f,249f,137f,style,c2,accent,logos[1],a)
    bevel(c,p,45f,331f,345f,53f,0xFF515A61.toInt(),0xFF111821.toInt(),7f,SILVER)
    label(c,p,"IMG LIVE  •  ${s.period} PERIOD",218f,369f,31f)
   }
   9->{ // Match Cube: opposing vertically framed team towers and a clock foot.
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
   10->{ // Corner-edge: compact two rows, score module at end, clock dock at foot.
    team(c,p,s,0,27f,40f,370f,132f,style,c1,accent,logos[0],a)
    team(c,p,s,1,27f,182f,370f,132f,style,c2,accent,logos[1],a)
    bevel(c,p,30f,320f,266f,66f,0xFF253D4B.toInt(),0xFF081018.toInt(),6f,SILVER)
    label(c,p,s.clock.ifBlank{"10:00"},162f,366f,52f)
    bevel(c,p,306f,320f,91f,66f,0xFF872734.toInt(),0xFF37151E.toInt(),6f,SILVER)
    label(c,p,"24",351f,365f,51f,0xFFFFC64B.toInt())
   }
   else->{ // Legacy TV: embossed stacked teams plus protruding play-clock box.
    team(c,p,s,0,28f,35f,365f,128f,style,c1,accent,logos[0],a)
    team(c,p,s,1,28f,178f,365f,128f,style,c2,accent,logos[1],a)
    bevel(c,p,25f,321f,256f,60f,0xFF4D5358.toInt(),0xFF11151C.toInt(),6f,SILVER)
    label(c,p,if(s.period<=4)"Q${s.period}" else "OT",80f,365f,32f)
    label(c,p,s.clock.ifBlank{"10:00"},200f,365f,34f)
    bevel(c,p,285f,317f,113f,68f,0xFFAC243C.toInt(),0xFF370B16.toInt(),4f,SILVER)
    label(c,p,"PLAY  24",340f,358f,30f)
   }
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