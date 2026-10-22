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
 private var lastScoreChange:Pair<Int,Int>?=null
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
  background=rounded(Color.rgb(30,220,130),11)
  setTextColor(Color.rgb(7,36,27))
  textSize=14f
  setTypeface(typeface,android.graphics.Typeface.BOLD)
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(dp(3),0,dp(3),0)
 }
 private fun darkButton(value:String,run:()->Unit):Button=action(value,run).apply{
  background=rounded(Color.rgb(39,67,57),11)
  setTextColor(Color.rgb(231,247,237));textSize=12f
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(dp(2),0,dp(2),0)
 }
 private fun dangerButton(value:String,run:()->Unit):Button=darkButton(value,run).apply{
  background=rounded(Color.rgb(127,42,49),11)
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
  heading("CAMERA & ON-AIR GRAPHICS")
  lateinit var overlayButton:Button
  overlayButton=tool(if(broadcast?.scorecardVisible()==true)"HIDE SCOREBOARD" else "SHOW SCOREBOARD"){
   val svc=broadcast
   if(svc!=null){
    svc.setScorecardVisible(!svc.scorecardVisible())
    overlayButton.text=if(svc.scorecardVisible())"HIDE SCOREBOARD" else "SHOW SCOREBOARD"
   }
  }
  pair(overlayButton,tool("SWITCH CAMERA"){broadcast?.flipCamera();refresh()})
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
   setLayout(minOf((maxWidth*.72f).toInt(),dp(500)),minOf((maxHeight*.83f).toInt(),dp(340)))
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
  val panel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(221,234,226))
  }
  val body=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(6),dp(5),dp(6),dp(4))
  }
  val gap=dp(5)
  fun matchHeight(weight:Float)=LinearLayout.LayoutParams(-1,0,weight)
  val area=FrameLayout(this).apply {
   setBackgroundColor(Color.BLACK)
   background=rounded(Color.BLACK,13)
   clipToOutline=true
  }
  monitor=SurfaceView(this)
  area.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  area.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
   // Letterbox instead of stretching the camera or its GL scoreboard.
   val maxW=r-l
   val maxH=b-t
   if(maxW>0&&maxH>0){
    val w=minOf(maxW,maxH*16/9)
    val h=w*9/16
    val old=monitor.layoutParams
    if(w>0&&h>0&&(old.width!=w||old.height!=h))
     monitor.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
   }
  }
  monitor.holder.addCallback(this)
  val clockCard=card().apply{setPadding(dp(5),dp(3),dp(5),dp(3))}
  clockCard.addView(ink("CLOCK",12f),LinearLayout.LayoutParams(-1,dp(18)))
  val clockRow=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   gravity=Gravity.CENTER_VERTICAL
  }
  clockRow.addView(greenButton("✎"){editClock()},LinearLayout.LayoutParams(dp(33),dp(33)))
  clockDisplay=ink("10:00",21f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  clockRow.addView(clockDisplay,LinearLayout.LayoutParams(0,-1,1f))
  val clockToggle=greenButton("▶"){
   gameRunning=!gameRunning
   lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
   updateGameClock()
  }
  clockRow.addView(clockToggle,LinearLayout.LayoutParams(dp(33),dp(33)))
  clockCard.addView(clockRow,LinearLayout.LayoutParams(-1,0,1f))
  val quarterCard=card().apply{setPadding(dp(5),dp(3),dp(5),dp(3))}
  quarterCard.addView(ink(if(intent.getStringExtra("img_sport")=="pickleball")"GAME" else "QUARTER",12f),
   LinearLayout.LayoutParams(-1,dp(18)))
  val quarterRow=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   gravity=Gravity.CENTER_VERTICAL
  }
  quarterRow.addView(greenButton("−"){broadcast?.adjustPeriod(-1);refreshScoreboard()},
   LinearLayout.LayoutParams(dp(33),dp(33)))
  periodDisplay=ink("1st",19f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  quarterRow.addView(periodDisplay,LinearLayout.LayoutParams(0,-1,1f))
  quarterRow.addView(greenButton("+"){broadcast?.nextPeriod();refreshScoreboard()},
   LinearLayout.LayoutParams(dp(33),dp(33)))
  quarterCard.addView(quarterRow,LinearLayout.LayoutParams(-1,0,1f))
  val top=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  }
  top.addView(area,LinearLayout.LayoutParams(0,-1,0.42f))
  top.addView(clockCard,LinearLayout.LayoutParams(0,-1,0.29f).apply{leftMargin=gap})
  top.addView(quarterCard,LinearLayout.LayoutParams(0,-1,0.29f).apply{leftMargin=gap})
  body.addView(top,matchHeight(0.58f))
  fun teamCard(team:Int):LinearLayout{
   val c=card().apply{setPadding(dp(7),dp(3),dp(7),dp(3))}
   val teamName=intent.getStringExtra(if(team==0)"img_home" else "img_away")
    ?.take(18)?.ifBlank{if(team==0)"HOME" else "AWAY"}?:if(team==0)"HOME" else "AWAY"
   c.addView(ink(teamName,12f),LinearLayout.LayoutParams(-1,dp(18)))
   val row=LinearLayout(this).apply{
    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
   }
   row.addView(greenButton("−1"){broadcast?.adjustScore(team,-1);refreshScoreboard()},
    LinearLayout.LayoutParams(dp(42),dp(40)))
   val score=ink("0",22f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
   if(team==0)homeDisplay=score else awayDisplay=score
   row.addView(score,LinearLayout.LayoutParams(0,-1,1f))
   val points=if(intent.getStringExtra("img_sport")=="pickleball") listOf(1) else listOf(1,2,3)
   for(p in points){
    val button=greenButton("+$p"){broadcast?.adjustScore(team,p);refreshScoreboard()}
    // Most frequent +1 gets priority; secondary score actions are compact.
    button.textSize=if(p==1)15f else 12f
    if(p==1)button.setTypeface(button.typeface,android.graphics.Typeface.BOLD)
    row.addView(button,LinearLayout.LayoutParams(dp(if(p==1)55 else 40),dp(40))
     .apply{leftMargin=dp(5)})
   }
   c.addView(row,LinearLayout.LayoutParams(-1,0,1f))
   return c
  }
  val scoreGrid=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   setPadding(0,gap,0,0)
  }
  scoreGrid.addView(teamCard(0),LinearLayout.LayoutParams(0,-1,1f))
  scoreGrid.addView(teamCard(1),LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=gap})
  val pickleball=intent.getStringExtra("img_sport")=="pickleball"
  body.addView(scoreGrid,matchHeight(if(pickleball)0.42f else 0.29f))
  val fouls=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   setPadding(0,dp(3),0,0)
   gravity=Gravity.CENTER_VERTICAL
  }
  if(intent.getStringExtra("img_sport")!="pickleball"){
   fouls.addView(greenButton("HOME FOUL +1"){broadcast?.addFoul(0);refreshScoreboard()},
    LinearLayout.LayoutParams(0,-1,1f))
   fouls.addView(greenButton("AWAY FOUL +1"){broadcast?.addFoul(1);refreshScoreboard()},
    LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=gap})
  }
  if(!pickleball)body.addView(fouls,matchHeight(0.13f))
  panel.addView(body,LinearLayout.LayoutParams(-1,0,1f))
  val footer=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(0,46,36))
   setPadding(dp(6),dp(1),dp(6),dp(3))
  }
  status=text("Ready for a private broadcast",10f).apply{
   maxLines=1
   ellipsize=android.text.TextUtils.TruncateAt.END
   setPadding(dp(4),0,dp(4),0)
  }
  footer.addView(status,LinearLayout.LayoutParams(-1,dp(15)))
  val actions=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  }
  start=darkButton("▶ GO LIVE"){
   if(!checkPermissions())return@darkButton
   val service=broadcast
   if(service==null)status.text="Camera service is not ready."
   else if(!service.activateForeground())status.text="Android could not start the foreground service."
   else service.startPrivateUpload(draftId)
   refresh()
  }
  stop=darkButton("■ STOP"){broadcast?.stopBroadcast();refresh()}
  actions.addView(start,LinearLayout.LayoutParams(0,dp(40),1.5f))
  actions.addView(stop,LinearLayout.LayoutParams(0,dp(40),1f).apply{leftMargin=gap})
  actions.addView(darkButton("EXIT"){finish()},
   LinearLayout.LayoutParams(0,dp(40),1f).apply{leftMargin=gap})
  actions.addView(darkButton("⚙ SETTINGS"){openOperatorSettings()},
   LinearLayout.LayoutParams(0,dp(40),1.2f).apply{leftMargin=gap})
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
  periodDisplay.text=if(score.sport=="pickleball")"Game ${score.period}" else when(score.period){
   1->"1st";2->"2nd";3->"3rd";4->"4th";else->"OT${score.period-4}"
  }
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