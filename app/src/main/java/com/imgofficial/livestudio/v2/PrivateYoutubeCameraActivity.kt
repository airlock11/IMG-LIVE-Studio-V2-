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
import android.widget.ScrollView
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
 private lateinit var portraitButton:Button
 private lateinit var landscapeButton:Button
 private lateinit var flipButton:Button
 private lateinit var scoreStatus:TextView
 private lateinit var scoreboardToggle:Button
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
   // Restore the selected framing from Intent on the FIRST orientation tap.
   val portrait=intent.getStringExtra("img_video_mode")!="landscape"
   if(checkPermissions())broadcast?.prepareCamera()
   if(broadcast?.isPortrait()!=portrait)broadcast?.changeFraming(portrait)
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
  text=value;textSize=size;setTextColor(Color.rgb(9,47,41))
  gravity=Gravity.CENTER
 }
 private fun greenButton(value:String,run:()->Unit):Button = action(value,run).apply{
  background=rounded(Color.rgb(0,212,123),50)
  setTextColor(Color.rgb(0,48,37))
  textSize=14f
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
  setPadding(0,0,0,0)
 }
 private fun darkButton(value:String,run:()->Unit):Button=action(value,run).apply{
  background=rounded(Color.rgb(0,77,59),14)
  setTextColor(Color.WHITE);textSize=12f
  minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
 }
 private fun card():LinearLayout=LinearLayout(this).apply{
  orientation=LinearLayout.VERTICAL
  background=rounded(Color.WHITE,24)
  setPadding(dp(11),dp(10),dp(11),dp(9))
 }
 private fun updateGameClock(){
  if(!::clockDisplay.isInitialized)return
  val value="%02d:%02d".format(gameSeconds/60,gameSeconds%60)
  clockDisplay.text=value
  broadcast?.setClock(value)
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
 private fun openOperatorSettings(){
  val service=broadcast
  val choices=arrayOf(
   if(service?.scorecardVisible()==true)"Hide scoreboard" else "Show scoreboard",
   "Switch front / rear camera",
   "Portrait orientation",
   "Landscape orientation",
   "Edit game clock",
   "Reset game clock to 10:00"
  )
  android.app.AlertDialog.Builder(this).setTitle("IMG broadcast settings")
   .setItems(choices){_,which->
    when(which){
     0->{service?.setScorecardVisible(!(service.scorecardVisible()));refreshScoreboard()}
     1->{service?.flipCamera();refresh()}
     2->changeFraming(true)
     3->changeFraming(false)
     4->editClock()
     5->{gameSeconds=600;gameRunning=false;lastTick=0;updateGameClock()}
    }
   }.setNegativeButton("Close",null).show()
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
  val landscape=intent.getStringExtra("img_video_mode")=="landscape"
  requestedOrientation=if(landscape)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

  val panel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(221,234,226))
  }
  val body=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(10),dp(9),dp(10),dp(8))
  }
  val area=FrameLayout(this).apply{
   background=rounded(Color.BLACK,23)
   clipToOutline=true
  }
  monitor=SurfaceView(this)
  area.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  area.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
   val maxW=r-l; val maxH=b-t
   if(maxW>0&&maxH>0){
    val land=intent.getStringExtra("img_video_mode")=="landscape"
    val rw=if(land)16 else 9
    val rh=if(land)9 else 16
    val w=minOf(maxW,maxH*rw/rh)
    val h=w*rh/rw
    val old=monitor.layoutParams
    if(w>0&&h>0&&(old.width!=w||old.height!=h))
     monitor.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
   }
  }
  monitor.holder.addCallback(this)
  val clockCard=card()
  clockCard.addView(ink("Clock",14f),LinearLayout.LayoutParams(-1,dp(35)))
  val clockRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  clockRow.addView(greenButton("✎"){editClock()},LinearLayout.LayoutParams(dp(44),dp(44)))
  clockDisplay=ink("10:00",23f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  clockRow.addView(clockDisplay,LinearLayout.LayoutParams(0,dp(44),1f))
  clockRow.addView(greenButton("▶"){
   gameRunning=!gameRunning
   lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
   updateGameClock()
  },LinearLayout.LayoutParams(dp(44),dp(44)))
  clockCard.addView(clockRow)
  val quarterCard=card()
  quarterCard.addView(ink(if(intent.getStringExtra("img_sport")=="pickleball")"Game" else "Quarter",14f),
   LinearLayout.LayoutParams(-1,dp(35)))
  val quarterRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  quarterRow.addView(greenButton("−"){broadcast?.adjustPeriod(-1);refreshScoreboard()},
   LinearLayout.LayoutParams(dp(44),dp(44)))
  periodDisplay=ink("1st",21f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  quarterRow.addView(periodDisplay,LinearLayout.LayoutParams(0,dp(44),1f))
  quarterRow.addView(greenButton("+"){broadcast?.nextPeriod();refreshScoreboard()},
   LinearLayout.LayoutParams(dp(44),dp(44)))
  quarterCard.addView(quarterRow)
  val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  val margin=dp(8)
  if(landscape){
   top.addView(area,LinearLayout.LayoutParams(0,dp(160),1f))
   top.addView(clockCard,LinearLayout.LayoutParams(0,dp(160),1f).apply{leftMargin=margin})
   top.addView(quarterCard,LinearLayout.LayoutParams(0,dp(160),1f).apply{leftMargin=margin})
   body.addView(top)
  }else{
   body.addView(area,LinearLayout.LayoutParams(-1,dp(230)))
   top.addView(clockCard,LinearLayout.LayoutParams(0,dp(128),1f))
   top.addView(quarterCard,LinearLayout.LayoutParams(0,dp(128),1f).apply{leftMargin=margin})
   body.addView(top,LinearLayout.LayoutParams(-1,-2).apply{topMargin=margin})
  }
  fun teamCard(team:Int):LinearLayout{
   val c=card()
   val teamName=intent.getStringExtra(if(team==0)"img_home" else "img_away")
    ?.take(18)?.ifBlank{if(team==0)"HOME" else "AWAY"}?:if(team==0)"HOME" else "AWAY"
   c.addView(ink(teamName,14f),LinearLayout.LayoutParams(-1,dp(27)))
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
   row.addView(greenButton("−"){broadcast?.adjustScore(team,-1);refreshScoreboard()},
    LinearLayout.LayoutParams(dp(39),dp(41)))
   val score=ink("0",22f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
   if(team==0)homeDisplay=score else awayDisplay=score
   row.addView(score,LinearLayout.LayoutParams(0,dp(45),1f))
   val pickleball=intent.getStringExtra("img_sport")=="pickleball"
   for(points in (if(pickleball)listOf(1) else listOf(1,2,3))){
    val btn=greenButton("+$points"){broadcast?.adjustScore(team,points);refreshScoreboard()}
    row.addView(btn,LinearLayout.LayoutParams(dp(if(landscape)37 else 44),dp(41))
     .apply{leftMargin=dp(3)})
   }
   c.addView(row)
   return c
  }
  val scoreGrid=LinearLayout(this).apply{
   orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
  }
  scoreGrid.addView(teamCard(0),if(landscape) LinearLayout.LayoutParams(0,dp(132),1f)
   else LinearLayout.LayoutParams(-1,dp(118)))
  scoreGrid.addView(teamCard(1),if(landscape)LinearLayout.LayoutParams(0,dp(132),1f)
   .apply{leftMargin=margin} else LinearLayout.LayoutParams(-1,dp(118)).apply{topMargin=margin})
  body.addView(scoreGrid,LinearLayout.LayoutParams(-1,-2).apply{topMargin=margin})
  scoreStatus=ink("HOME: 0  •  AWAY: 0  Q1",13f)
  body.addView(scoreStatus,LinearLayout.LayoutParams(-1,dp(34)))
  if(intent.getStringExtra("img_sport")!="pickleball"){
   val fouls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
   fouls.addView(greenButton("HOME FOUL"){broadcast?.addFoul(0);refreshScoreboard()},
    LinearLayout.LayoutParams(0,dp(42),1f))
   fouls.addView(greenButton("AWAY FOUL"){broadcast?.addFoul(1);refreshScoreboard()},
    LinearLayout.LayoutParams(0,dp(42),1f).apply{leftMargin=margin})
   body.addView(fouls)
  }
  val scroller=ScrollView(this).apply{
   isFillViewport=false
   addView(body)
   isVerticalScrollBarEnabled=false
  }
  panel.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
  val footer=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(0,46,36))
   setPadding(dp(8),dp(5),dp(8),dp(5))
  }
  status=text("Camera preview",11f)
  status.maxLines=2
  footer.addView(status,LinearLayout.LayoutParams(-1,dp(28)))
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  start=darkButton("▶ GO LIVE"){
   if(!checkPermissions())return@darkButton
   val service=broadcast
   if(service==null)status.text="Camera service is not ready."
   else if(!service.activateForeground())status.text="Android could not start the foreground service."
   else service.startPrivateUpload(draftId)
   refresh()
  }
  stop=darkButton("■ STOP"){broadcast?.stopBroadcast();refresh()}
  actions.addView(start,LinearLayout.LayoutParams(0,dp(49),1.4f))
  actions.addView(stop,LinearLayout.LayoutParams(0,dp(49),1f).apply{leftMargin=dp(3)})
  actions.addView(darkButton("EXIT"){finish()},LinearLayout.LayoutParams(0,dp(49),1f).apply{leftMargin=dp(3)})
  actions.addView(darkButton("Replay"){unavailableFeature("Instant replay")},
   LinearLayout.LayoutParams(0,dp(49),1.2f).apply{leftMargin=dp(3)})
  actions.addView(darkButton("Media"){unavailableFeature("Sponsor & media")},
   LinearLayout.LayoutParams(0,dp(49),1.2f).apply{leftMargin=dp(3)})
  actions.addView(darkButton("⚙"){openOperatorSettings()},
   LinearLayout.LayoutParams(0,dp(49),.65f).apply{leftMargin=dp(3)})
  footer.addView(actions)
  portraitButton=darkButton("Portrait"){changeFraming(true)}
  landscapeButton=darkButton("Landscape"){changeFraming(false)}
  flipButton=darkButton("Flip camera"){broadcast?.flipCamera();refresh()}
  scoreboardToggle=darkButton("Hide scoreboard"){
   broadcast?.setScorecardVisible(!(broadcast?.scorecardVisible()?:true))
   refreshScoreboard()
  }
  val extras=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  for(btn in listOf(portraitButton,landscapeButton,flipButton,scoreboardToggle))
   extras.addView(btn,LinearLayout.LayoutParams(0,dp(36),1f).apply{leftMargin=dp(3)})
  footer.addView(extras,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
  panel.addView(footer,LinearLayout.LayoutParams(-1,-2))
  setContentView(panel)
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
  if(!::scoreStatus.isInitialized)return
  val score=broadcast?.scorecard()?:return
  val label=if(score.sport=="pickleball")"Game" else "Q"
  scoreStatus.text="${score.home}: ${score.homeScore}   •   ${score.away}: ${score.awayScore}   $label${score.period}"
  homeDisplay.text=score.homeScore.toString()
  awayDisplay.text=score.awayScore.toString()
  periodDisplay.text=if(score.sport=="pickleball")"Game ${score.period}" else when(score.period){
   1->"1st";2->"2nd";3->"3rd";4->"4th";else->"OT${score.period-4}"
  }
  scoreboardToggle.text=if(broadcast?.scorecardVisible()==true)"Hide board" else "Show board"
 }
 private fun refresh(){
  val service=broadcast
  if(!::start.isInitialized)return
  val usable=service!=null&&checkPermissions()
  start.isEnabled=usable&&service?.isActive()!=true&&service?.isEnded()!=true
  stop.isEnabled=service?.isActive()==true
  portraitButton.isEnabled=usable
  landscapeButton.isEnabled=usable
  flipButton.isEnabled=usable
  if(service!=null) {
   status.text=service.getStatus()+"\n"+service.connectionSummary()
   portraitButton.text=if(service.isPortrait())"✓ Portrait" else "Portrait"
   landscapeButton.text=if(service.isPortrait())"Landscape" else "✓ Landscape"
   flipButton.text=if(service.frontCamera())"Switch to rear camera" else "Switch to front camera"
   refreshScoreboard()
  }
 }
 private fun changeFraming(portrait:Boolean){
  val target=if(portrait)"portrait" else "landscape"
  // Intent state must be saved BEFORE Android rotates/recreates this view.
  // The newly bound service also reads this state in onServiceConnected.
  intent.putExtra("img_video_mode",target)
  broadcast?.changeFraming(portrait)
  val newOrientation=if(portrait)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
   else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  if(requestedOrientation!=newOrientation)requestedOrientation=newOrientation
  refresh()
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