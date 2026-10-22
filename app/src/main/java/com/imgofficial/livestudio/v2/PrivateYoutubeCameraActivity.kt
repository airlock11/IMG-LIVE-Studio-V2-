package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * IMG V2 private video operator view. The foreground V2BroadcastService owns
 * camera, microphone, encoder and RTMPS; this Activity only attaches preview
 * and controls it. Screen-off, Home and configuration changes must not stop it.
 */
class PrivateYoutubeCameraActivity:Activity(),SurfaceHolder.Callback,V2BroadcastService.Listener {
 private var broadcast:V2BroadcastService?=null
 private var bound=false
 private var resumed=false
 // Only a fresh, user-initiated main-screen action can auto-start upload.
 private var autoLaunchAllowed=false
 private lateinit var monitor:SurfaceView
 private lateinit var status:TextView
 private lateinit var start:Button
 private lateinit var stop:Button
 private val draftId by lazy {intent.getStringExtra("draftRequestId").orEmpty()}
 private val conn=object:ServiceConnection {
  override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
   broadcast=(binder as? V2BroadcastService.LocalBinder)?.service()
   broadcast?.register(this@PrivateYoutubeCameraActivity)
   broadcast?.configureScorecard(
    intent.getStringExtra("img_sport")?:"basketball",
    intent.getStringExtra("img_home")?:"HOME",
    intent.getStringExtra("img_away")?:"AWAY"
   )
   val ids=arrayOf("prime-tv","courtside-a","courtside-b","arena-glass",
    "rivalry-plate","center-dial","broadcast-classic","crown-pill",
    "split-box","match-cube","edge-box","legacy-tv")
   val graphicsIndex=ids.indexOf(intent.getStringExtra("img_graphics")).coerceAtLeast(0)
   broadcast?.configureGraphics(
    graphicsIndex,
    intent.getStringExtra("img_graphics_position").orEmpty(),
    intent.getIntExtra("img_graphics_size",60),
    intent.getIntExtra("img_graphics_opacity",100),
    intent.getIntExtra("img_graphics_width",100),
    intent.getIntExtra("img_graphics_height",100),
    intent.getIntExtra("img_graphics_offset_x",0),
    intent.getIntExtra("img_graphics_offset_y",0),
    intent.getIntExtra("img_graphics_margin",3),
    intent.getBooleanExtra("img_graphics_animated",true),
    decodeLogo(intent.getStringExtra("img_logo_home")),
    decodeLogo(intent.getStringExtra("img_logo_away"))
   )
   // Rotating the operator screen recreates its Activity. A service that
   // was preview-only may also be recreated when Android briefly unbinds it.
   // All V2 basketball/pickleball streams use the same landscape profile.
   // Streetball portrait will be a distinct future mode, not a toggle here.
   if(broadcast?.isPortrait()==true)broadcast?.changeFraming(false)
   if(checkPermissions())broadcast?.prepareCamera()
   if(checkPermissions()&&resumed&&monitor.holder.surface.isValid)
    broadcast?.attachPreview(monitor)
   refresh()
   maybeStartPrivateUpload()
  }
  override fun onServiceDisconnected(name:ComponentName?){
   broadcast=null;status.text="Broadcast service disconnected."
   refresh()
  }
 }
 private fun decodeLogo(data:String?):android.graphics.Bitmap?{
  if(data==null||data.length>35000||!data.startsWith("data:image/png;base64,"))return null
  return try{
   val bytes=android.util.Base64.decode(data.substringAfter(','),android.util.Base64.DEFAULT)
   if(bytes.size !in 1..27000)return null
   val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
   android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
   if(options.outWidth !in 1..128 || options.outHeight !in 1..128)return null
   android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
  }catch(_:Exception){null}
 }
 private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
 private fun text(value:String,size:Float=14f)=TextView(this).apply {
  text=value;textSize=size;setTextColor(Color.WHITE)
  setPadding(dp(4),dp(4),dp(4),dp(6))
 }
 private fun action(name:String,run:()->Unit)=Button(this).apply {
  text=name;textSize=12f;isAllCaps=false
  setOnClickListener{run()}
 }

 private lateinit var clockDisplay:TextView
 private lateinit var periodDisplay:TextView
 private lateinit var homeDisplay:TextView
 private lateinit var awayDisplay:TextView
 private lateinit var homeFoulsDisplay:TextView
 private lateinit var awayFoulsDisplay:TextView
 private lateinit var clockToggleButton:Button
 private lateinit var undoButton:Button
 private val periodQuickButtons=mutableListOf<Button>()
 private var lastScoreChange:Pair<Int,Int>?=null
 private var touchFeedback=true
 private var gameSeconds=600
 private var gameRunning=false
 private var lastTick=0L
 private val timer=android.os.Handler(android.os.Looper.getMainLooper())
 private val tick=object:Runnable {
  override fun run(){
   if(gameRunning){
    val now=android.os.SystemClock.elapsedRealtime()
    if(lastTick>0){
     val passed=((now-lastTick)/1000L).toInt()
     if(passed>0){gameSeconds=(gameSeconds-passed).coerceAtLeast(0);lastTick+=passed*1000L}
    }else lastTick=now
    if(gameSeconds==0)gameRunning=false
   }
   updateGameClock()
   timer.postDelayed(this,1000)
  }
 }
 private fun rounded(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply{
  shape=android.graphics.drawable.GradientDrawable.RECTANGLE
  setColor(color);cornerRadius=dp(radius).toFloat()
 }
 private fun touchSurface(color:Int,radius:Int)=android.graphics.drawable.RippleDrawable(
  android.content.res.ColorStateList.valueOf(Color.argb(60,255,255,255)),
  rounded(color,radius),null
 )
 private fun ink(value:String,size:Float=14f)=TextView(this).apply{
  text=value;textSize=size;setTextColor(Color.rgb(238,250,243))
  gravity=Gravity.CENTER
  setSingleLine(true)
  ellipsize=android.text.TextUtils.TruncateAt.END
 }
 private fun smallLabel(value:String)=ink(value,11f).apply{
  setTextColor(Color.rgb(157,191,172))
  letterSpacing=.06f
 }
 private fun adjustClock(delta:Int){
  gameSeconds=(gameSeconds+delta).coerceIn(0,5999)
  gameRunning=false
  lastTick=0L
  updateGameClock()
 }
 private fun changeTeamScore(team:Int,delta:Int){
  if(touchFeedback)try{window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)}catch(_:Exception){}
  val service=broadcast?:return
  val before=service.scorecard()
  val old=if(team==0)before.homeScore else before.awayScore
  service.adjustScore(team,delta)
  val after=service.scorecard()
  val changed=(if(team==0)after.homeScore else after.awayScore)-old
  if(changed!=0)lastScoreChange=Pair(team,changed)
  refreshScoreboard()
 }
 private fun undoScore(){
  val previous=lastScoreChange?:return
  broadcast?.adjustScore(previous.first,-previous.second)
  lastScoreChange=null
  refreshScoreboard()
 }
 private fun greenButton(value:String,run:()->Unit):Button = action(value,run).apply{
  background=touchSurface(Color.rgb(30,220,130),11)
  setTextColor(Color.rgb(7,36,27))
  textSize=14f
  setTypeface(typeface,android.graphics.Typeface.BOLD)
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(dp(3),0,dp(3),0)
 }
 private fun darkButton(value:String,run:()->Unit):Button=action(value,run).apply{
  background=touchSurface(Color.rgb(39,67,57),11)
  setTextColor(Color.rgb(231,247,237));textSize=12f
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(dp(2),0,dp(2),0)
 }
 private fun dangerButton(value:String,run:()->Unit):Button=darkButton(value,run).apply{
  background=touchSurface(Color.rgb(127,42,49),11)
  setTextColor(Color.WHITE)
 }
 private fun card():LinearLayout=LinearLayout(this).apply{
  orientation=LinearLayout.VERTICAL
  background=rounded(Color.rgb(23,37,32),15)
  setPadding(dp(7),dp(5),dp(7),dp(5))
 }
 private fun updateGameClock(){
  if(!::clockDisplay.isInitialized)return
  val value="%02d:%02d".format(gameSeconds/60,gameSeconds%60)
  clockDisplay.text=value
  if(::clockToggleButton.isInitialized)
   clockToggleButton.text=if(gameRunning)"Ⅱ PAUSE" else "▶ START"
  if(broadcast?.scorecard()?.clock!=value)broadcast?.setClock(value)
 }
 private fun editClock(){
  val input=android.widget.EditText(this).apply{
   setSingleLine(true);setText("%02d:%02d".format(gameSeconds/60,gameSeconds%60))
   inputType=android.text.InputType.TYPE_CLASS_DATETIME
  }
  android.app.AlertDialog.Builder(this).setTitle("Game clock (MM:SS)")
   .setView(input)
   .setPositiveButton("Save"){_,_->
    val p=input.text.toString().trim().split(':')
    val mm=p.getOrNull(0)?.toIntOrNull()
    val ss=p.getOrNull(1)?.toIntOrNull()
    if(p.size==2&&mm!=null&&ss!=null&&mm in 0..99&&ss in 0..59){
     gameSeconds=mm*60+ss;gameRunning=false;lastTick=0;updateGameClock()
    }else android.widget.Toast.makeText(this,"Enter a valid MM:SS",android.widget.Toast.LENGTH_SHORT).show()
   }.setNegativeButton("Cancel",null).show()
 }
 private var operatorSheet:android.app.Dialog?=null
 private fun openOperatorSettings(){
  operatorSheet?.dismiss()
  val dialog=android.app.Dialog(this)
  val outer=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   background=rounded(Color.rgb(18,31,27),17)
   setPadding(dp(12),dp(8),dp(12),dp(8))
  }
  val grip=View(this).apply{background=rounded(Color.rgb(98,128,111),3)}
  val gripRow=FrameLayout(this)
  gripRow.addView(grip,FrameLayout.LayoutParams(dp(42),dp(4),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
  outer.addView(gripRow,LinearLayout.LayoutParams(-1,dp(10)))
  val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  header.addView(ink("BROADCAST TOOLS",16f).apply{
   gravity=Gravity.CENTER_VERTICAL
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  },LinearLayout.LayoutParams(0,dp(35),1f))
  header.addView(darkButton("✕ CLOSE"){dialog.dismiss()},LinearLayout.LayoutParams(dp(66),dp(33)))
  outer.addView(header)
  val scrolling=android.widget.ScrollView(this).apply{
   isFillViewport=false
   isVerticalScrollBarEnabled=false
  }
  val tools=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  fun heading(label:String){
   tools.addView(smallLabel(label).apply{gravity=Gravity.CENTER_VERTICAL},
    LinearLayout.LayoutParams(-1,dp(21)).apply{topMargin=dp(3)})
  }
  fun tool(name:String,work:()->Unit)=darkButton(name,work).apply{textSize=12f}
  fun pair(left:Button,right:Button){
   val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
   line.addView(left,LinearLayout.LayoutParams(0,dp(43),1f))
   line.addView(right,LinearLayout.LayoutParams(0,dp(43),1f).apply{leftMargin=dp(7)})
   tools.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
  }
  heading("BROADCAST GRAPHICS • LIVE STYLES")
  val names=V2BroadcastGraphicsRenderer.names
  for(i in names.indices step 3){
   val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
   for(j in i until minOf(i+3,names.size)){
    val btn=tool(names[j]){
     broadcast?.setGraphicsStyle(j)
     refresh()
    }.apply{textSize=if(names[j].length>11)10f else 11f}
    val params=LinearLayout.LayoutParams(0,dp(39),1f)
    if(j>i)params.leftMargin=dp(4)
    line.addView(btn,params)
   }
   tools.addView(line,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
  }
  heading("SIZE & PLACEMENT")
  pair(tool("SMALLER −"){broadcast?.resizeGraphics(-5)},
   tool("LARGER +"){broadcast?.resizeGraphics(5)})
  pair(tool("BOTTOM CENTER"){broadcast?.placeGraphicsAt("bottom-center")},
   tool("BOTTOM RIGHT"){broadcast?.placeGraphicsAt("bottom-right")})
  pair(tool("BOTTOM LEFT"){broadcast?.placeGraphicsAt("bottom-left")},
   tool("TOP RIGHT"){broadcast?.placeGraphicsAt("top-right")})
  pair(tool("TOP LEFT"){broadcast?.placeGraphicsAt("top-left")},
   tool("RESET POSITION"){broadcast?.placeGraphicsAt(
    if(V2BroadcastGraphicsRenderer.compact(broadcast?.graphicsStyle()?:0))
     "bottom-right" else "bottom-center")})
  fun settingsToggle(name:String,value:Boolean,onToggle:(Boolean)->Unit):android.widget.Switch{
   return android.widget.Switch(this).apply{
    text=name
    textSize=13f
    setTextColor(Color.rgb(230,249,235))
    setPadding(dp(8),0,dp(12),0)
    isChecked=value
    thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(236,252,240))
    trackTintList=android.content.res.ColorStateList(
     arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
     intArrayOf(Color.rgb(25,171,102),Color.rgb(70,91,79))
    )
    setOnCheckedChangeListener{_,checked->onToggle(checked)}
   }
  }
  tools.addView(settingsToggle("Animate score awards (+1 / +2 / +3)",
   broadcast?.scoreEffectsEnabled()==true){broadcast?.setScoreEffectsEnabled(it)
  },LinearLayout.LayoutParams(-1,dp(42)))
  heading("CAMERA & VISIBILITY")
  tools.addView(settingsToggle("Show Broadcast Graphics",broadcast?.scorecardVisible()==true){
   broadcast?.setScorecardVisible(it)
  },LinearLayout.LayoutParams(-1,dp(42)))
  tools.addView(settingsToggle("Touch feedback",touchFeedback){
   touchFeedback=it
  },LinearLayout.LayoutParams(-1,dp(42)))
  pair(tool("SWITCH CAMERA"){broadcast?.flipCamera();refresh()},
   tool("UNDO LAST SCORE"){undoScore()})
  heading("CLOCK & GAME")
  pair(tool("EDIT GAME CLOCK"){dialog.dismiss();editClock()},
   tool("RESET TO 10:00"){gameSeconds=600;gameRunning=false;lastTick=0L;updateGameClock()})
  if(intent.getStringExtra("img_sport")!="pickleball"){
   heading("FOUL CORRECTIONS")
   pair(tool("HOME FOUL −1"){broadcast?.adjustFoul(0,-1);refreshScoreboard()},
    tool("AWAY FOUL −1"){broadcast?.adjustFoul(1,-1);refreshScoreboard()})
  }
  heading("ADVANCED BROADCAST · COMING SOON")
  val replay=tool("INSTANT REPLAY"){}
  val sponsors=tool("SPONSOR OVERLAY"){}
  replay.isEnabled=false;replay.alpha=.48f
  sponsors.isEnabled=false;sponsors.alpha=.48f
  pair(replay,sponsors)
  scrolling.addView(tools)
  outer.addView(scrolling,LinearLayout.LayoutParams(-1,0,1f))
  dialog.setContentView(outer)
  dialog.setCanceledOnTouchOutside(true)
  dialog.setOnDismissListener{if(operatorSheet===dialog)operatorSheet=null}
  operatorSheet=dialog
  dialog.show()
  dialog.window?.apply{
   setBackgroundDrawableResource(android.R.color.transparent)
   addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
   setDimAmount(.28f)
   val maxWidth=resources.displayMetrics.widthPixels
   val maxHeight=resources.displayMetrics.heightPixels
   setLayout(minOf((maxWidth*.84f).toInt(),dp(880)),minOf((maxHeight*.92f).toInt(),dp(525)))
   attributes=attributes.apply{gravity=Gravity.BOTTOM or Gravity.RIGHT;x=dp(6);y=dp(4)}
   decorView.translationY=dp(200).toFloat()
   decorView.animate().translationY(0f).setDuration(190).start()
  }
 }
 private fun unavailableFeature(name:String){
  android.app.AlertDialog.Builder(this).setTitle(name)
   .setMessage("This feature is not yet connected to the V2 live video encoder. Your live upload and scoring remain active.")
   .setPositiveButton("Continue",null).show()
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  autoLaunchAllowed=savedInstanceState==null&&intent.getBooleanExtra("img_autostart",false)
  window.statusBarColor=Color.rgb(16,23,27)
  window.navigationBarColor=Color.rgb(16,23,27)
  // Operator is landscape-only; never expose portrait for normal sports.
  intent.putExtra("img_video_mode","landscape")
  requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

  // All operator essentials are visible in one landscape page. Secondary
  // actions live in Settings; no scroll view obscures the live scoreboard.
  val isPickleball=intent.getStringExtra("img_sport")=="pickleball"
  val panel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(10,19,16))
  }
  val body=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(6),dp(5),dp(6),dp(3))
  }
  val gap=dp(5)
  fun sized(weight:Float)=LinearLayout.LayoutParams(-1,0,weight)
  fun horizontal()=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   gravity=Gravity.CENTER_VERTICAL
  }

  val camera=FrameLayout(this).apply{
   background=rounded(Color.BLACK,11)
   clipToOutline=true
  }
  monitor=SurfaceView(this)
  camera.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  camera.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
   val maxW=r-l;val maxH=b-t
   if(maxW>0&&maxH>0){
    val w=minOf(maxW,maxH*16/9);val h=w*9/16
    val old=monitor.layoutParams
    if(w>0&&h>0&&(old.width!=w||old.height!=h))
     monitor.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
   }
  }
  monitor.holder.addCallback(this)

  // Single aligned strip: video, game clock, and game period.
  val top=horizontal()
  top.addView(camera,LinearLayout.LayoutParams(0,-1,.36f))
  val clockCard=card()
  clockCard.addView(smallLabel("GAME CLOCK"),LinearLayout.LayoutParams(-1,dp(18)))
  val clockMain=horizontal()
  clockDisplay=ink("10:00",26f).apply{
   gravity=Gravity.CENTER_VERTICAL
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  }
  clockMain.addView(clockDisplay,LinearLayout.LayoutParams(0,-1,1f))
  clockToggleButton=greenButton("▶ START"){
   gameRunning=!gameRunning
   lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
   updateGameClock()
  }.apply{textSize=11f}
  clockMain.addView(clockToggleButton,LinearLayout.LayoutParams(dp(76),dp(38)))
  clockCard.addView(clockMain,LinearLayout.LayoutParams(-1,0,1f))
  val timeActions=horizontal()
  val timeButtons=listOf(
   darkButton("−10s"){adjustClock(-10)},
   darkButton("EDIT"){editClock()},
   darkButton("+10s"){adjustClock(10)}
  )
  for((index,button) in timeButtons.withIndex())
   timeActions.addView(button,LinearLayout.LayoutParams(0,dp(34),1f).apply{
    if(index>0)leftMargin=dp(4)
   })
  clockCard.addView(timeActions)
  top.addView(clockCard,LinearLayout.LayoutParams(0,-1,.34f).apply{leftMargin=gap})
  val quarterCard=card()
  quarterCard.addView(smallLabel(if(isPickleball)"GAME / ROUND" else "QUARTER"),
   LinearLayout.LayoutParams(-1,dp(18)))
  val quarterMain=horizontal()
  quarterMain.addView(darkButton("−"){broadcast?.adjustPeriod(-1);refreshScoreboard()},
   LinearLayout.LayoutParams(dp(35),dp(36)))
  periodDisplay=ink("1st",22f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  quarterMain.addView(periodDisplay,LinearLayout.LayoutParams(0,-1,1f))
  quarterMain.addView(greenButton("+"){broadcast?.nextPeriod();refreshScoreboard()},
   LinearLayout.LayoutParams(dp(35),dp(36)))
  quarterCard.addView(quarterMain,LinearLayout.LayoutParams(-1,0,1f))
  val quickPeriods=horizontal()
  val names=if(isPickleball)listOf("G1","G2","G3") else listOf("Q1","Q2","Q3","Q4")
  for((index,label) in names.withIndex()){
   val number=index+1
   val button=darkButton(label){
    broadcast?.selectPeriod(number)
    refreshScoreboard()
   }.apply{textSize=11f}
   periodQuickButtons.add(button)
   quickPeriods.addView(button,LinearLayout.LayoutParams(0,dp(32),1f).apply{
    if(index>0)leftMargin=dp(3)
   })
  }
  quarterCard.addView(quickPeriods)
  top.addView(quarterCard,LinearLayout.LayoutParams(0,-1,.30f).apply{leftMargin=gap})
  body.addView(top,sized(if(isPickleball).54f else .51f))

  // Balanced HOME and AWAY scoreboard, optimized for thumbs and one-tap scoring.
  fun teamCard(team:Int):LinearLayout{
   val c=card().apply{setPadding(dp(7),dp(4),dp(7),dp(4))}
   val teamName=intent.getStringExtra(if(team==0)"img_home" else "img_away")
    ?.trim()?.take(22)?.ifBlank{if(team==0)"HOME" else "AWAY"}
    ?:if(team==0)"HOME" else "AWAY"
   val label=smallLabel((if(team==0)"HOME  •  " else "AWAY  •  ")+teamName).apply{
    gravity=Gravity.CENTER_VERTICAL
   }
   c.addView(label,LinearLayout.LayoutParams(-1,dp(20)))
   val row=horizontal()
   row.addView(darkButton("−1"){changeTeamScore(team,-1)},
    LinearLayout.LayoutParams(dp(46),dp(44)))
   val score=ink("0",30f).apply{
    setTypeface(typeface,android.graphics.Typeface.BOLD)
   }
   if(team==0)homeDisplay=score else awayDisplay=score
   row.addView(score,LinearLayout.LayoutParams(0,-1,1f))
   val points=if(isPickleball)listOf(1) else listOf(1,2,3)
   for(p in points){
    val button=if(p==1)greenButton("+1"){changeTeamScore(team,1)}
     else darkButton("+$p"){changeTeamScore(team,p)}
    button.textSize=if(p==1)17f else 14f
    row.addView(button,LinearLayout.LayoutParams(dp(if(p==1)59 else 42),dp(44))
     .apply{leftMargin=dp(5)})
   }
   c.addView(row,LinearLayout.LayoutParams(-1,0,1f))
   return c
  }
  val scoreGrid=horizontal().apply{setPadding(0,gap,0,0)}
  scoreGrid.addView(teamCard(0),LinearLayout.LayoutParams(0,-1,1f))
  scoreGrid.addView(teamCard(1),LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=gap})
  body.addView(scoreGrid,sized(if(isPickleball).46f else .35f))

  if(!isPickleball){
   val fouls=horizontal().apply{setPadding(0,dp(3),0,0)}
   fun foulCounter(team:Int):LinearLayout{
    val row=horizontal()
    val display=ink("0",16f).apply{
     setTypeface(typeface,android.graphics.Typeface.BOLD)
     setTextColor(Color.rgb(45,218,128))
    }
    if(team==0)homeFoulsDisplay=display else awayFoulsDisplay=display
    row.addView(smallLabel(if(team==0)"HOME FOULS" else "AWAY FOULS"),
     LinearLayout.LayoutParams(0,-1,1f))
    row.addView(display,LinearLayout.LayoutParams(dp(30),-1))
    row.addView(darkButton("−"){broadcast?.adjustFoul(team,-1);refreshScoreboard()},
     LinearLayout.LayoutParams(dp(39),dp(34)))
    row.addView(greenButton("+1"){broadcast?.addFoul(team);refreshScoreboard()},
     LinearLayout.LayoutParams(dp(51),dp(34)).apply{leftMargin=dp(4)})
    return row
   }
   fouls.addView(foulCounter(0),LinearLayout.LayoutParams(0,-1,1f))
   fouls.addView(foulCounter(1),LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=gap})
   body.addView(fouls,sized(.14f))
  }
  panel.addView(body,LinearLayout.LayoutParams(-1,0,1f))

  // Always-available broadcast/navigation row. Status is condensed above it.
  val footer=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   background=rounded(Color.rgb(12,39,29),7)
   setPadding(dp(6),0,dp(6),dp(4))
  }
  status=text("READY  •  camera and microphone",10f).apply{
   maxLines=1
   ellipsize=android.text.TextUtils.TruncateAt.END
   setPadding(dp(4),0,dp(4),0)
   gravity=Gravity.CENTER_VERTICAL
   setTextColor(Color.rgb(192,231,208))
  }
  footer.addView(status,LinearLayout.LayoutParams(-1,dp(16)))
  val actions=horizontal()
  start=greenButton("▶ GO LIVE"){
   if(!checkPermissions())return@greenButton
   val service=broadcast
   if(service==null)status.text="Camera service is not ready."
   else if(!service.activateForeground())status.text="Android could not start the foreground service."
   else service.startPrivateUpload(draftId)
   refresh()
  }.apply{textSize=14f}
  stop=dangerButton("■ STOP"){
   android.app.AlertDialog.Builder(this)
    .setTitle("Stop this broadcast?")
    .setMessage("This ends the current upload. Your scores remain visible in the operator until exit.")
    .setNegativeButton("CANCEL",null)
    .setPositiveButton("STOP"){_,_->broadcast?.stopBroadcast();refresh()}
    .show()
  }
  undoButton=darkButton("↶ UNDO"){undoScore()}
  val keys=listOf(start,stop,undoButton,
   darkButton("⚙ TOOLS"){openOperatorSettings()},
   darkButton("EXIT"){finish()})
  val widths=listOf(1.55f,.96f,.98f,1.15f,.72f)
  for((index,button) in keys.withIndex())
   actions.addView(button,LinearLayout.LayoutParams(0,dp(43),widths[index]).apply{
    if(index>0)leftMargin=gap
   })
  footer.addView(actions)
  panel.addView(footer,LinearLayout.LayoutParams(-1,-2))
  // Keep controls clear of Android 15 edge-to-edge, cutouts and system bars.
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
  val safeRoot=FrameLayout(this).apply{
   setBackgroundColor(Color.rgb(12,17,24))
   addView(panel,FrameLayout.LayoutParams(-1,-1))
  }
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(safeRoot){v,insets->
   val bars=insets.getInsets(
    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
   v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   insets
  }
  setContentView(safeRoot)
  androidx.core.view.ViewCompat.requestApplyInsets(safeRoot)
  gameSeconds=savedInstanceState?.getInt("v2_clock_seconds",600)?:600
  gameRunning=savedInstanceState?.getBoolean("v2_clock_running",false)?:false
  lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
  timer.post(tick)

  if(!Regex("^[a-f0-9]{32}$").matches(draftId)||BuildConfig.OAUTH_BACKEND_URL.isBlank()){
   status.text="Choose a verified private V2 draft from Connected Accounts."
   start.isEnabled=false
   return
  }
  val serviceIntent=Intent(this,V2BroadcastService::class.java)
  bound=bindService(serviceIntent,conn,Context.BIND_AUTO_CREATE)
  if(!checkPermissions())requestPermissions(arrayOf(Manifest.permission.CAMERA,
   Manifest.permission.RECORD_AUDIO),640)
  refresh()
 }
 private fun checkPermissions():Boolean =
  checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
  checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED

 @Deprecated("Deprecated in Java")
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,results:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,results)
  if(requestCode==640){
   if(checkPermissions()){
    broadcast?.prepareCamera()
    if(resumed&&monitor.holder.surface.isValid)broadcast?.attachPreview(monitor)
   }else status.text="Allow camera and microphone to broadcast privately."
   refresh()
   maybeStartPrivateUpload()
  }
 }
 private fun maybeStartPrivateUpload(){
  if(!autoLaunchAllowed||!checkPermissions())return
  val service=broadcast?:return
  if(service.isActive()||service.isEnded()){autoLaunchAllowed=false;return}
  autoLaunchAllowed=false
  if(!service.activateForeground()){
   status.text="Android could not start private video upload."
   return
  }
  service.startPrivateUpload(draftId)
  refresh()
 }
 private fun refreshScoreboard(){
  if(!::homeDisplay.isInitialized)return
  val score=broadcast?.scorecard()?:return
  homeDisplay.text=score.homeScore.toString()
  awayDisplay.text=score.awayScore.toString()
  periodDisplay.text=if(score.sport=="pickleball")"G${score.period}" else when(score.period){
   1->"1st";2->"2nd";3->"3rd";4->"4th";else->"OT${score.period-4}"
  }
  if(::homeFoulsDisplay.isInitialized){
   homeFoulsDisplay.text=score.homeFouls.toString()
   awayFoulsDisplay.text=score.awayFouls.toString()
  }
  for((index,button) in periodQuickButtons.withIndex()){
   val active=index+1==score.period
   button.background=touchSurface(if(active)Color.rgb(35,189,117) else Color.rgb(39,67,57),9)
   button.setTextColor(if(active)Color.rgb(8,35,27) else Color.rgb(231,247,237))
  }
  if(::undoButton.isInitialized)undoButton.isEnabled=lastScoreChange!=null
 }
 private fun refresh(){
  val service=broadcast
  if(!::start.isInitialized)return
  val usable=service!=null&&checkPermissions()
  start.isEnabled=usable&&service?.isActive()!=true&&service?.isEnded()!=true
  stop.isEnabled=service?.isActive()==true
  if(service!=null) {
   status.text=service.getStatus()+" • "+service.connectionSummary()
   refreshScoreboard()
  }
 }
 override fun onBroadcastStatus(status:String,active:Boolean,connecting:Boolean,
    ended:Boolean,portrait:Boolean,front:Boolean){runOnUiThread{refresh()}}
 override fun surfaceCreated(holder:SurfaceHolder){
  if(resumed&&checkPermissions())broadcast?.attachPreview(monitor)
 }
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){
  if(width>0&&height>0)
   broadcast?.let{
    // The service continues encoding when this surface disappears.
    // Its renderer updates preview dimensions only, not encoder dimensions.
    if(monitor.holder.surface.isValid&&resumed)it.attachPreview(monitor)
   }
 }
 override fun surfaceDestroyed(holder:SurfaceHolder){broadcast?.detachPreview()}
 override fun onResume(){
  super.onResume()
  resumed=true
  if(checkPermissions()&&::monitor.isInitialized&&monitor.holder.surface.isValid)
   broadcast?.attachPreview(monitor)
 }
 override fun onStop(){
  resumed=false
  // Detach only the screen's surface. NEVER stop the foreground encoder.
  broadcast?.detachPreview()
  super.onStop()
 }
 override fun onSaveInstanceState(outState:Bundle){
  outState.putInt("v2_clock_seconds",gameSeconds)
  outState.putBoolean("v2_clock_running",gameRunning)
  super.onSaveInstanceState(outState)
 }
 override fun onDestroy(){
  timer.removeCallbacks(tick)
  operatorSheet?.dismiss()
  operatorSheet=null
  if(bound){
   broadcast?.unregister(this)
   broadcast?.detachPreview()
   unbindService(conn)
   bound=false
  }
  broadcast=null
  window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  super.onDestroy()
 }
}