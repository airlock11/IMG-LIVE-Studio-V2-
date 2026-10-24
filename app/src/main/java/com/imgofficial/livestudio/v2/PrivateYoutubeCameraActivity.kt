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
 private val offlineMode by lazy {intent.getBooleanExtra("img_offline_recording",false)}
 private var timeoutActive=false
 private var timeoutSlideshowEnabled=false
 private var gameWasRunningBeforeTimeout=false
 private var scoreWasVisibleBeforeTimeout=true
 private var timeoutAuto=true
 private var timeoutIntervalSeconds=5
 private var timeoutTransitionMillis=500
 private lateinit var timeoutButton:Button
 private var draftReady=true
 private var videoProfileReady=true
 private lateinit var monitor:SurfaceView
 private var previousGraphicBitmap:android.graphics.Bitmap?=null
 private var previousGraphicBounds=intArrayOf(-1,-1,-1,-1)
 private lateinit var graphicsPreview:android.widget.ImageView
 private var previewGraphicsPos=floatArrayOf(8f,70f,84f,22f)
 private lateinit var status:TextView
 private fun placePreviewGraphic(){
  if(!::graphicsPreview.isInitialized||!::monitor.isInitialized)return
  val mw=monitor.width;val mh=monitor.height
  if(mw<=0||mh<=0)return
  val p=previewGraphicsPos
  val pw=(mw*p[2]/100f).toInt().coerceAtLeast(1)
  val ph=(mh*p[3]/100f).toInt().coerceAtLeast(1)
  val parent=graphicsPreview.parent as? FrameLayout?:return
  val parentWidth=parent.width
  val parentHeight=parent.height
  val bx=if(parentWidth>0)(parentWidth-mw)/2 else monitor.left
  val by=if(parentHeight>0)(parentHeight-mh)/2 else monitor.top
  val left=bx+(mw*p[0]/100f).toInt()
  val top=by+(mh*p[1]/100f).toInt()
  if(previousGraphicBounds.contentEquals(intArrayOf(left,top,pw,ph)))return
  previousGraphicBounds=intArrayOf(left,top,pw,ph)
  graphicsPreview.layoutParams=FrameLayout.LayoutParams(pw,ph,Gravity.TOP or Gravity.LEFT).apply{
   leftMargin=left
   topMargin=top
  }
 }
 override fun onPreviewGraphics(image:android.graphics.Bitmap,x:Float,y:Float,w:Float,h:Float){
  if(!::graphicsPreview.isInitialized)return
  runOnUiThread {
   if(isFinishing||isDestroyed)return@runOnUiThread
   previewGraphicsPos=floatArrayOf(x,y,w,h)
   // SeekBar movement reuses the same bitmap. Avoid resetting the
   // ImageView texture on every finger-movement tick.
   if(previousGraphicBitmap!==image){
    graphicsPreview.setImageBitmap(image)
    previousGraphicBitmap=image
   }
   placePreviewGraphic()
  }
 }
 private lateinit var start:Button
 private val draftId by lazy {intent.getStringExtra("draftRequestId").orEmpty()}
 private val conn=object:ServiceConnection {
  override fun onServiceConnected(name:ComponentName?,binder:IBinder?){
   broadcast=(binder as? V2BroadcastService.LocalBinder)?.service()
   broadcast?.register(this@PrivateYoutubeCameraActivity)
    broadcast?.setReplayTimelineListener{pos->
     if(!isFinishing&&!isDestroyed)replayUi.onTimelinePosition(pos)
    }
    broadcast?.setReplayCompletedListener{
     if(!isFinishing&&!isDestroyed&&broadcast?.instantReplayIsPrepared()==true)
      showPrivateReplay()
    }
   draftReady=offlineMode||broadcast?.prepareNextDraft(draftId)==true
   if(!draftReady){
    autoLaunchAllowed=false
    refresh()
    return
   }
   val resolution=intent.getIntExtra("img_video_quality",720)
    val fps=intent.getIntExtra("img_video_fps",30)
    videoProfileReady=broadcast?.isActive()==true||broadcast?.configureVideoProfile(resolution,fps)==true
    if(!videoProfileReady)autoLaunchAllowed=false
    broadcast?.configureLocalRecording(!offlineMode&&intent.getBooleanExtra("img_record_local",false))
    val names=intent.getStringExtra("img_sponsor_names").orEmpty().split("\n")
     .map{it.trim()}.filter{it.isNotEmpty()}.take(12)
    val images=try{org.json.JSONArray(intent.getStringExtra("img_sponsor_images")?:"[]")}
     catch(_:Exception){org.json.JSONArray()}
     val token=intent.getStringExtra("img_sponsor_token").orEmpty()
     broadcast?.configureSponsorSlides(names,names.indices.map{i->
      decodeSponsorFile(token,i)?:decodeSponsorImage(images.optString(i))
     })
    broadcast?.configureScorecard(
    intent.getStringExtra("img_sport")?:"basketball",
    intent.getStringExtra("img_home")?:"HOME",
    intent.getStringExtra("img_away")?:"AWAY"
   )
   broadcast?.setShotClockVisible(shotClockVisible)
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
    true,
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
 // Respect source EXIF display orientation, not camera orientation.
 // A portrait sponsor stays upright and is fitted, never rotated to landscape.
 private fun decodeSponsorFile(token:String,index:Int):android.graphics.Bitmap?{
  if(!token.matches(Regex("^[a-f0-9]{16}$"))||index !in 0..11)return null
  val file=java.io.File(cacheDir,"imgsponsor_${token}_${index}.img")
  if(!file.isFile||file.length() !in 1..2500000L)return null
  return try{
   val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
   android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
   if(bounds.outWidth !in 1..4096||bounds.outHeight !in 1..4096)return null
   val options=android.graphics.BitmapFactory.Options()
   var sample=1
   while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1920)sample*=2
   options.inSampleSize=sample
   val pixels=android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)?:return null
   val orientation=try{
    android.media.ExifInterface(file.absolutePath).getAttributeInt(
     android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL)
   }catch(_:Exception){android.media.ExifInterface.ORIENTATION_NORMAL}
   val transform=android.graphics.Matrix()
   when(orientation){
    android.media.ExifInterface.ORIENTATION_ROTATE_90->transform.postRotate(90f)
    android.media.ExifInterface.ORIENTATION_ROTATE_180->transform.postRotate(180f)
    android.media.ExifInterface.ORIENTATION_ROTATE_270->transform.postRotate(270f)
    android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL->transform.postScale(-1f,1f)
    android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL->transform.postScale(1f,-1f)
    android.media.ExifInterface.ORIENTATION_TRANSPOSE->{
     transform.postScale(-1f,1f);transform.postRotate(270f)
    }
    android.media.ExifInterface.ORIENTATION_TRANSVERSE->{
     transform.postScale(-1f,1f);transform.postRotate(90f)
    }
   }
   if(transform.isIdentity)pixels else android.graphics.Bitmap.createBitmap(
    pixels,0,0,pixels.width,pixels.height,transform,true).also{
     if(it!==pixels)pixels.recycle()
    }
  }catch(_:Exception){null}
 }
 private fun decodeSponsorImage(raw:String?):android.graphics.Bitmap?{
  if(raw.isNullOrEmpty()||raw.length>5000||!raw.startsWith("data:image/jpeg;base64,"))return null
  return try{
   val bytes=android.util.Base64.decode(raw.substringAfter(','),android.util.Base64.DEFAULT)
   if(bytes.size !in 1..3500)return null
   val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
   android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
   if(opts.outWidth !in 1..64||opts.outHeight !in 1..64)return null
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
 private var displayedClockPlaying:Boolean?=null
 private lateinit var resetClockButton:Button
 private lateinit var shotClockDisplay:TextView
 private var configuredGameSeconds=600
 private var shotClockVisible=true
 private var timeoutPreview:android.widget.PopupWindow?=null
 private val replayUi by lazy{V2ReplayPanel(this,{broadcast},{hidePrivateReplay()},{hidePrivateReplay()})}
 private lateinit var privateReplayView:android.view.TextureView
 private var privateReplaySurface:android.view.Surface?=null
 private var privateReplayVisible=false
 private fun attachPrivateReplay(){
  if(!privateReplayVisible||!::privateReplayView.isInitialized||!privateReplayView.isAvailable)return
  val texture=privateReplayView.surfaceTexture?:return
  broadcast?.detachPrivateReplayPreview()
  privateReplaySurface?.release()
  privateReplaySurface=android.view.Surface(texture)
  if(broadcast?.attachPrivateReplayPreview(privateReplaySurface!!)!=true)
   privateReplayView.alpha=0f
 }
 private fun showPrivateReplay(){
  if(!::privateReplayView.isInitialized)return
  privateReplayVisible=true;privateReplayView.alpha=0f;privateReplayView.visibility=View.VISIBLE
  if(privateReplayView.isAvailable)attachPrivateReplay()
 }
 private fun hidePrivateReplay(){
  privateReplayVisible=false
  broadcast?.detachPrivateReplayPreview()
  if(::privateReplayView.isInitialized){
   privateReplayView.visibility=View.GONE;privateReplayView.alpha=0f
  }
  privateReplaySurface?.release();privateReplaySurface=null
 }
 private val periodQuickButtons=mutableListOf<Button>()
 private var lastScoreChange:Pair<Int,Int>?=null
 private var touchFeedback=true
 private var gameSeconds=600
 private var shotSeconds=24
 private var gameRunning=false
 private var lastTick=0L
 private val timer=android.os.Handler(android.os.Looper.getMainLooper())
 private val tick=object:Runnable {
  override fun run(){
   if(gameRunning){
    val now=android.os.SystemClock.elapsedRealtime()
    if(lastTick>0){
     val passed=((now-lastTick)/1000L).toInt()
     if(passed>0){
      val applied=minOf(passed,gameSeconds)
      gameSeconds=(gameSeconds-applied).coerceAtLeast(0)
      shotSeconds=(shotSeconds-applied).coerceAtLeast(0)
      lastTick+=passed*1000L
     }
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
 private fun resetGameClock(){
  gameSeconds=configuredGameSeconds
  gameRunning=false
  lastTick=0L
  updateGameClock()
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
 // Android renders imported SVG path geometry using real VectorDrawable
 // resources. Never substitute Unicode glyphs or emoji for operator icons.
 private fun iconFor(kind:String,dark:Boolean=false):android.graphics.drawable.Drawable {
  val res=when(kind){
   "camera"->R.drawable.img_icon_camera
   "clock"->R.drawable.img_icon_clock
   "undo"->R.drawable.img_icon_undo
   "reset"->R.drawable.img_icon_reset
   "tools"->R.drawable.img_icon_tools
   "live"->R.drawable.img_icon_live
   "stop"->R.drawable.img_icon_stop
   "play"->R.drawable.img_icon_play
   "pause"->R.drawable.img_icon_pause
   "close"->R.drawable.img_icon_close
   "image"->R.drawable.img_icon_image
   else->R.drawable.img_icon_tools
  }
  val drawable=androidx.core.content.ContextCompat.getDrawable(this,res)!!.mutate()
  androidx.core.graphics.drawable.DrawableCompat.setTint(drawable,
   if(dark)Color.rgb(6,38,24) else Color.rgb(230,245,236))
  return drawable
 }
 private fun iconButton(btn:Button,kind:String,dark:Boolean=false):Button{
  btn.setCompoundDrawablesWithIntrinsicBounds(iconFor(kind,dark),null,null,null)
  btn.compoundDrawablePadding=dp(3)
  btn.gravity=Gravity.CENTER
  return btn
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
  if(::clockToggleButton.isInitialized){
   clockToggleButton.text=if(timeoutActive)"PAUSED" else if(gameRunning)"PAUSE" else "START"
   clockToggleButton.isEnabled=!timeoutActive
   if(displayedClockPlaying!=gameRunning){
    iconButton(clockToggleButton,if(gameRunning)"pause" else "play",true)
    displayedClockPlaying=gameRunning
   }
  }
  if(broadcast?.scorecard()?.clock!=value)broadcast?.setClock(value)
  if(::shotClockDisplay.isInitialized)shotClockDisplay.text=shotSeconds.toString()
  if(broadcast?.scorecard()?.shotClock!=shotSeconds)broadcast?.setShotClock(shotSeconds)
 }
 // Compact, dark broadcast UI dialogs replace Android's generic gray system dialogs.
 private fun customSheet(heading:String):Pair<android.app.Dialog,LinearLayout>{
  val dialog=android.app.Dialog(this)
  val panel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   background=rounded(Color.rgb(17,30,27),17)
   setPadding(dp(15),dp(12),dp(15),dp(14))
  }
  panel.addView(ink(heading,20f).apply{
   gravity=Gravity.CENTER_VERTICAL
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  },LinearLayout.LayoutParams(-1,dp(38)))
  dialog.setContentView(panel)
  dialog.setCanceledOnTouchOutside(true)
  dialog.show()
  dialog.window?.apply{
   setBackgroundDrawableResource(android.R.color.transparent)
   addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
   setDimAmount(.48f)
   setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
   setLayout(minOf((resources.displayMetrics.widthPixels*.65f).toInt(),dp(640)),-2)
   attributes=attributes.apply{gravity=Gravity.CENTER}
  }
  return dialog to panel
 }
 private fun editClock(){
  val (dialog,panel)=customSheet("Set time")
  val display=ink("",35f).apply{setTypeface(typeface,android.graphics.Typeface.BOLD)}
  fun show(){display.text="%02d:%02d".format(gameSeconds/60,gameSeconds%60)}
  show();panel.addView(display,LinearLayout.LayoutParams(-1,dp(58)))
  val adjust=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val quick=listOf(-60,-10,10,60)
  for((i,seconds) in quick.withIndex()){
   adjust.addView(darkButton(if(seconds>0)"+${seconds}s" else "${seconds}s"){
    gameSeconds=(gameSeconds+seconds).coerceIn(0,5999);show()
   },LinearLayout.LayoutParams(0,dp(47),1f).apply{if(i>0)leftMargin=dp(6)})
  }
  panel.addView(adjust)
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val mm=android.widget.EditText(this).apply{
   inputType=android.text.InputType.TYPE_CLASS_NUMBER
   setSingleLine(true);setText((gameSeconds/60).toString())
   textSize=24f;setTextColor(Color.WHITE)
   gravity=Gravity.CENTER;background=rounded(Color.rgb(34,53,45),10)
   setSelectAllOnFocus(true)
  }
  val ss=android.widget.EditText(this).apply{
   inputType=android.text.InputType.TYPE_CLASS_NUMBER
   setSingleLine(true);setText((gameSeconds%60).toString())
   textSize=24f;setTextColor(Color.WHITE)
   gravity=Gravity.CENTER;background=rounded(Color.rgb(34,53,45),10)
   setSelectAllOnFocus(true)
  }
  row.addView(mm,LinearLayout.LayoutParams(0,dp(54),1f))
  row.addView(ink(":",25f),LinearLayout.LayoutParams(dp(20),dp(54)))
  row.addView(ss,LinearLayout.LayoutParams(0,dp(54),1f))
  panel.addView(smallLabel("MINUTES                          SECONDS"),
   LinearLayout.LayoutParams(-1,dp(28)))
  panel.addView(row)
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  actions.addView(darkButton("CANCEL"){dialog.dismiss()},
   LinearLayout.LayoutParams(0,dp(48),1f))
  actions.addView(greenButton("APPLY TIME"){
   val m=mm.text.toString().toIntOrNull()
   val sec=ss.text.toString().toIntOrNull()
   if(m==null||m !in 0..99||sec==null||sec !in 0..59){
    android.widget.Toast.makeText(this,"Enter valid minutes and seconds",android.widget.Toast.LENGTH_SHORT).show()
   }else{
    gameSeconds=m*60+sec;configuredGameSeconds=gameSeconds;gameRunning=false;lastTick=0L
    updateGameClock();dialog.dismiss()
   }
  },LinearLayout.LayoutParams(0,dp(48),1.25f).apply{leftMargin=dp(8)})
  panel.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
 }
 private fun setTimeoutMode(enabled:Boolean){
  if(enabled==timeoutActive)return
  timeoutActive=enabled
  if(enabled){
   // Both game and shot clocks count down only while gameRunning is true.
   // Keep video upload and local MP4 recording continuous through timeout.
   gameWasRunningBeforeTimeout=gameRunning
   scoreWasVisibleBeforeTimeout=broadcast?.scorecardVisible()?:true
   gameRunning=false;lastTick=0L
   timeoutSlideshowEnabled=false
   broadcast?.setSponsorSlideshow(false)
   // Fade scoreboard out whenever Timeout begins, even without slideshow.
   broadcast?.setScorecardVisible(false)
  }else{
   // A timeout does not implicitly start a previously stopped clock.
   timeoutSlideshowEnabled=false
   timeoutPreview?.dismiss();timeoutPreview=null
   broadcast?.setSponsorSlideshow(false)
   broadcast?.setScorecardVisible(scoreWasVisibleBeforeTimeout)
   gameRunning=gameWasRunningBeforeTimeout&&gameSeconds>0
   lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
   gameWasRunningBeforeTimeout=false
  }
   if(::timeoutButton.isInitialized){
    timeoutButton.text=if(timeoutActive)"RESUME" else "TIMEOUT"
    iconButton(timeoutButton,if(timeoutActive)"play" else "clock")
   }
   updateGameClock()
  }
  private fun showTimeoutPreview(anchor:View){
  if(!timeoutActive)setTimeoutMode(true)
  timeoutPreview?.dismiss()
  // Compact options above the bottom Timeout/Resume control, clear of camera.
  val content=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   background=rounded(Color.rgb(17,30,27),12)
   setPadding(dp(9),dp(8),dp(9),dp(8))
  }
  content.addView(smallLabel("TIMEOUT · CLOCKS PAUSED"),LinearLayout.LayoutParams(-1,dp(24)))
  val showRow=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  }
  showRow.addView(ink("Show Slideshow",12f),LinearLayout.LayoutParams(0,dp(32),1f))
  val toggle=android.widget.Switch(this).apply{
   isChecked=timeoutSlideshowEnabled
   isEnabled=broadcast?.hasSponsorSlides()==true
   thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(225,245,233))
   trackTintList=android.content.res.ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
    intArrayOf(Color.rgb(35,170,112),Color.rgb(74,91,80)))
  }
  showRow.addView(toggle,LinearLayout.LayoutParams(dp(48),dp(32)))
  content.addView(showRow)
  val nextSponsor=darkButton("NEXT SPONSOR"){broadcast?.nextSponsorSlide()}.apply{
   isEnabled=timeoutSlideshowEnabled;textSize=11f
  }
  fun timingRow(label:String,choices:List<Int>,selected:()->Int,
   display:(Int)->String,changed:(Int)->Unit){
   val line=LinearLayout(this).apply{
    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
   }
   line.addView(ink(label,11f),LinearLayout.LayoutParams(0,dp(32),1f))
   lateinit var choice:Button
   choice=darkButton(display(selected())){
    val i=choices.indexOf(selected()).coerceAtLeast(0)
    val value=choices[(i+1)%choices.size]
    changed(value);choice.text=display(value)
    broadcast?.setSponsorTiming(timeoutAuto,timeoutIntervalSeconds,timeoutTransitionMillis)
   }.apply{textSize=11f}
   line.addView(choice,LinearLayout.LayoutParams(dp(74),dp(29)))
   content.addView(line)
  }
  timingRow("Mode",listOf(1,0),{if(timeoutAuto)1 else 0},
   {if(it==1)"AUTO" else "MANUAL"},{timeoutAuto=it==1})
  timingRow("Slide every",listOf(3,5,8,10,15),{timeoutIntervalSeconds},
   {"${it}s"},{timeoutIntervalSeconds=it})
  timingRow("Transition",listOf(0,250,500,1000,1500),{timeoutTransitionMillis},
   {if(it==0)"NONE" else "${it/1000f}s"},{timeoutTransitionMillis=it})
  content.addView(nextSponsor,LinearLayout.LayoutParams(-1,dp(35)).apply{
   topMargin=dp(4)
  })
  if(broadcast?.hasSponsorSlides()!=true)content.addView(
   smallLabel("Add sponsor images in Step 3."),
   LinearLayout.LayoutParams(-1,dp(25)))
  toggle.setOnCheckedChangeListener{_,checked->
   timeoutSlideshowEnabled=checked&&broadcast?.hasSponsorSlides()==true
   broadcast?.setSponsorTiming(timeoutAuto,timeoutIntervalSeconds,timeoutTransitionMillis)
   broadcast?.setSponsorSlideshow(timeoutSlideshowEnabled)
   nextSponsor.isEnabled=timeoutSlideshowEnabled
  }
  val popup=android.widget.PopupWindow(content,dp(230),-2,false).apply{
   setBackgroundDrawable(rounded(Color.rgb(17,30,27),12))
   isOutsideTouchable=false
   elevation=dp(8).toFloat()
  }
  timeoutPreview=popup
  popup.showAsDropDown(anchor,0,-anchor.height-dp(207),Gravity.CENTER_HORIZONTAL)
 }
 private fun confirmStopOfflineRecording(){
  if(broadcast?.isOfflineRecording()!=true)return
  val wasClockRunning=gameRunning
  gameRunning=false;lastTick=0L;updateGameClock()
  broadcast?.pauseOfflineRecording()
  refresh()
  val (dialog,panel)=customSheet("STOP RECORDING?")
  panel.addView(smallLabel("Recording paused. Resume or finish and save to your phone."),
   LinearLayout.LayoutParams(-1,dp(48)))
  val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  buttons.addView(greenButton("RESUME"){
   if(broadcast?.resumeOfflineRecording()==true){
    dialog.dismiss()
    if(wasClockRunning&&!timeoutActive){
     gameRunning=true;lastTick=android.os.SystemClock.elapsedRealtime()
    }
    updateGameClock()
   }
   refresh()
  },LinearLayout.LayoutParams(0,dp(49),1f))
  buttons.addView(dangerButton("END"){
   dialog.dismiss()
   setTimeoutMode(false)
   broadcast?.stopBroadcast()
   setResult(Activity.RESULT_OK,Intent().putExtra("img_native_stream_ended",true)
    .putExtra("img_local_recording_requested",true))
   finish()
  },LinearLayout.LayoutParams(0,dp(49),1f).apply{leftMargin=dp(10)})
  panel.addView(buttons)
  dialog.setOnCancelListener{
   if(broadcast?.resumeOfflineRecording()==true&&wasClockRunning&&!timeoutActive){
    gameRunning=true;lastTick=android.os.SystemClock.elapsedRealtime()
   }
   updateGameClock();refresh()
  }
 }
 private fun confirmStopUpload(){
  val (dialog,panel)=customSheet("END STREAM?")
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  actions.addView(greenButton("KEEP STREAMING"){dialog.dismiss()},
   LinearLayout.LayoutParams(0,dp(50),1f))
  actions.addView(dangerButton("END STREAM"){
   dialog.dismiss()
   val wasActive=broadcast?.isActive()==true
   broadcast?.stopBroadcast()
   if(wasActive){
    setResult(Activity.RESULT_OK,Intent().putExtra("img_native_stream_ended",true)
     .putExtra("img_local_recording_requested",intent.getBooleanExtra("img_record_local",false)))
    finish()
   }else refresh()
  },LinearLayout.LayoutParams(0,dp(50),1f).apply{leftMargin=dp(10)})
  panel.addView(actions)
 }
 private var operatorSheet:android.app.Dialog?=null
 private fun openOperatorSettings(){
  operatorSheet?.dismiss()
  val dialog=android.app.Dialog(this)
  val outer=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   background=rounded(Color.rgb(18,31,27),17)
   setPadding(dp(13),dp(9),dp(13),dp(12))
  }
  val header=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  }
  header.addView(ink("Broadcast tools",16f).apply{
   gravity=Gravity.CENTER_VERTICAL
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  },LinearLayout.LayoutParams(0,dp(39),1f))
  header.addView(iconButton(darkButton("Close"){dialog.dismiss()},"close"),
   LinearLayout.LayoutParams(dp(81),dp(34)))
  outer.addView(header)
  outer.addView(smallLabel("SCOREBOARD SIZE").apply{
   gravity=Gravity.CENTER_VERTICAL
  },LinearLayout.LayoutParams(-1,dp(27)).apply{topMargin=dp(4)})
  val sizeRow=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  }
  val sizeReadout=ink("${broadcast?.graphicsSize()?:60}%",14f)
  val sizeSlider=android.widget.SeekBar(this).apply{
   max=100;progress=(broadcast?.graphicsSize()?:60)-15
   progressTintList=android.content.res.ColorStateList.valueOf(Color.rgb(46,214,146))
   thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(67,233,166))
   setOnSeekBarChangeListener(object:android.widget.SeekBar.OnSeekBarChangeListener{
    override fun onProgressChanged(seekBar:android.widget.SeekBar?,value:Int,fromUser:Boolean){
     if(fromUser){
      val size=value+15
      sizeReadout.text="${size}%"
      broadcast?.setGraphicsSize(size)
     }
    }
    override fun onStartTrackingTouch(seekBar:android.widget.SeekBar?){}
    override fun onStopTrackingTouch(seekBar:android.widget.SeekBar?){}
   })
  }
  sizeRow.addView(sizeSlider,LinearLayout.LayoutParams(0,dp(46),1f))
  sizeRow.addView(sizeReadout,LinearLayout.LayoutParams(dp(52),dp(46)))
  outer.addView(sizeRow)
  dialog.setContentView(outer)
  dialog.setCanceledOnTouchOutside(true)
  dialog.setOnDismissListener{if(operatorSheet===dialog)operatorSheet=null}
  operatorSheet=dialog
  dialog.show()
  dialog.window?.apply{
   setBackgroundDrawableResource(android.R.color.transparent)
   addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
   setDimAmount(.18f)
   val width=resources.displayMetrics.widthPixels
   setLayout(minOf((width*.56f).toInt(),dp(550)),-2)
   attributes=attributes.apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL;x=dp(7)}
  }
 }
 private fun unavailableFeature(name:String){
  android.app.AlertDialog.Builder(this).setTitle(name)
   .setMessage("This feature is not yet connected to the V2 live video encoder. Your live upload and scoring remain active.")
   .setPositiveButton("Continue",null).show()
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  if(savedInstanceState!=null){
   gameSeconds=savedInstanceState.getInt("v2_clock_seconds",600).coerceIn(0,5999)
   shotSeconds=savedInstanceState.getInt("v2_shot_seconds",24).coerceIn(0,99)
    configuredGameSeconds=savedInstanceState.getInt("v2_initial_clock_seconds",600).coerceIn(0,5999)
    shotClockVisible=savedInstanceState.getBoolean("v2_shot_visible",true)
    gameRunning=false
    timeoutActive=savedInstanceState.getBoolean("v2_timeout_active",false)
    gameWasRunningBeforeTimeout=savedInstanceState.getBoolean("v2_timeout_clock_before",false)
    scoreWasVisibleBeforeTimeout=savedInstanceState.getBoolean("v2_timeout_score_before",true)
    timeoutSlideshowEnabled=savedInstanceState.getBoolean("v2_timeout_sponsors",false)
    timeoutAuto=savedInstanceState.getBoolean("v2_timeout_auto",true)
    timeoutIntervalSeconds=savedInstanceState.getInt("v2_timeout_interval",5)
    timeoutTransitionMillis=savedInstanceState.getInt("v2_timeout_transition",500)
   }
   if(savedInstanceState==null)shotClockVisible=intent.getBooleanExtra("img_shot_visible",true)
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
  val flip=iconButton(darkButton(""){broadcast?.flipCamera();refresh()},"camera").apply{
   contentDescription="Flip camera";setPadding(0,0,0,0)
  }
  monitor=SurfaceView(this).apply{
    setOnTouchListener{view,event->replayUi.handleTouch(view,event)}
   }
  camera.addView(monitor,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  // Operator-only TextureView: never supplied to the live H264 encoder.
  privateReplayView=android.view.TextureView(this).apply{
   visibility=View.GONE;alpha=0f;isOpaque=true
   surfaceTextureListener=object:android.view.TextureView.SurfaceTextureListener{
    override fun onSurfaceTextureAvailable(t:android.graphics.SurfaceTexture,w:Int,h:Int){
     attachPrivateReplay()
    }
    override fun onSurfaceTextureSizeChanged(t:android.graphics.SurfaceTexture,w:Int,h:Int){}
    override fun onSurfaceTextureUpdated(t:android.graphics.SurfaceTexture){
     if(privateReplayVisible&&alpha<1f)alpha=1f
    }
    override fun onSurfaceTextureDestroyed(t:android.graphics.SurfaceTexture):Boolean{
     broadcast?.detachPrivateReplayPreview()
     privateReplaySurface?.release();privateReplaySurface=null
     return true
    }
   }
  }
  camera.addView(privateReplayView,FrameLayout.LayoutParams(1,1,Gravity.CENTER))
  // Separate native operator preview of the *same* GL-uploaded Bitmap.
  // Screen-only ImageView never enters camera capture or H.264 encoding.
  // The livestream still uses the original ImageFilterRender directly.
  graphicsPreview=android.widget.ImageView(this).apply{
   scaleType=android.widget.ImageView.ScaleType.FIT_XY
   isClickable=false
   isFocusable=false
   importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }
  camera.addView(graphicsPreview,FrameLayout.LayoutParams(1,1,Gravity.TOP or Gravity.LEFT))
  camera.addView(flip,FrameLayout.LayoutParams(dp(34),dp(34),Gravity.TOP or Gravity.RIGHT).apply{
   rightMargin=dp(7);topMargin=dp(7)
  })
  camera.addOnLayoutChangeListener{_,l,t,r,b,_,_,_,_->
   val maxW=r-l;val maxH=b-t
   if(maxW>0&&maxH>0){
    val w=minOf(maxW,maxH*16/9);val h=w*9/16
    val old=monitor.layoutParams
    if(w>0&&h>0&&(old.width!=w||old.height!=h))
     monitor.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
    val replayParams=privateReplayView.layoutParams
    if(w>0&&h>0&&(replayParams.width!=w||replayParams.height!=h))
     privateReplayView.layoutParams=FrameLayout.LayoutParams(w,h,Gravity.CENTER)
    graphicsPreview.post{placePreviewGraphic()}
   }
  }
  monitor.holder.addCallback(this)

  // Single aligned strip: video, game clock, and game period.
  val top=horizontal()
  top.addView(camera,LinearLayout.LayoutParams(0,-1,.36f))
  val clockCard=card()
  val clockHeader=horizontal()
  clockHeader.addView(smallLabel("GAME CLOCK"),LinearLayout.LayoutParams(0,dp(22),1f))
  resetClockButton=iconButton(darkButton(""){resetGameClock()},"reset").apply{
   contentDescription="Reset game clock to its configured starting time"
   minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
   compoundDrawablePadding=0
   setPadding(0,0,0,0)
  }
  clockHeader.addView(resetClockButton,LinearLayout.LayoutParams(dp(31),dp(26)))
  clockCard.addView(clockHeader,LinearLayout.LayoutParams(-1,dp(28)))
  val clockMain=horizontal()
  clockDisplay=ink("10:00",26f).apply{
   gravity=Gravity.CENTER_VERTICAL
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  }
  clockMain.addView(clockDisplay,LinearLayout.LayoutParams(0,-1,1f))
  clockToggleButton=greenButton("START"){
   if(!timeoutActive){
    gameRunning=!gameRunning
    lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
    updateGameClock()
   }
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
  // Main-screen compact shot controls and a genuine on-air visibility switch.
  val shotRow=horizontal().apply{
   background=rounded(Color.rgb(23,37,32),10)
   setPadding(dp(6),0,dp(6),0)
  }
  shotRow.addView(smallLabel("SHOT"),LinearLayout.LayoutParams(dp(43),dp(25)))
  shotClockDisplay=ink(shotSeconds.toString(),15f).apply{
   setTypeface(typeface,android.graphics.Typeface.BOLD)
  }
  shotRow.addView(shotClockDisplay,LinearLayout.LayoutParams(dp(29),dp(29)))
  val shotButtons=listOf(
   darkButton("24"){shotSeconds=24;updateGameClock()},
   darkButton("14"){shotSeconds=14;updateGameClock()},
   darkButton("−1"){shotSeconds=(shotSeconds-1).coerceAtLeast(0);updateGameClock()},
   darkButton("+1"){shotSeconds=(shotSeconds+1).coerceAtMost(99);updateGameClock()}
  )
  for((index,btn) in shotButtons.withIndex())
   shotRow.addView(btn,LinearLayout.LayoutParams(0,dp(29),1f).apply{
    if(index>0)leftMargin=dp(3)
   })
  val visibleSwitch=android.widget.Switch(this).apply{
   contentDescription="Show or hide shot clock on live video"
   text="SHOW";textSize=10f
   setTextColor(Color.rgb(230,245,235))
   isChecked=shotClockVisible
   thumbTintList=android.content.res.ColorStateList.valueOf(Color.rgb(237,254,239))
   trackTintList=android.content.res.ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
    intArrayOf(Color.rgb(26,157,103),Color.rgb(70,91,79)))
   setOnCheckedChangeListener{_,checked->
    shotClockVisible=checked
    broadcast?.setShotClockVisible(checked)
   }
  }
  shotRow.addView(visibleSwitch,LinearLayout.LayoutParams(dp(87),dp(30)).apply{leftMargin=dp(5)})
  body.addView(shotRow,LinearLayout.LayoutParams(-1,dp(35)).apply{topMargin=dp(3)})
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
  start=greenButton(if(offlineMode)"RECORD" else "GO LIVE"){
   val service=broadcast
   if(offlineMode){
    if(service?.isOfflineRecording()==true){confirmStopOfflineRecording();return@greenButton}
    if(!checkPermissions())return@greenButton
    if(!videoProfileReady){refresh();return@greenButton}
    if(service?.startOfflineRecording()!=true)status.text=service?.getStatus()?:"Camera service is not ready."
    refresh()
    return@greenButton
   }
   if(service?.isActive()==true){confirmStopUpload();return@greenButton}
   if(!checkPermissions())return@greenButton
   if(!draftReady||!videoProfileReady){refresh();return@greenButton}
   if(service==null)status.text="Camera service is not ready."
   else if(!service.activateForeground())status.text="Android could not start the foreground service."
   else service.startPrivateUpload(draftId)
   refresh()
  }.apply{textSize=14f}
  if(!offlineMode)iconButton(start,"live",true)
   timeoutButton=iconButton(darkButton("TIMEOUT"){},"clock")
   timeoutButton.setOnClickListener{
    if(timeoutActive){
     timeoutPreview?.dismiss();timeoutPreview=null
     setTimeoutMode(false)
    }else showTimeoutPreview(timeoutButton)
   }
   val replayButton=darkButton("INSTANT REPLAY"){}
   replayButton.setOnClickListener{
    replayUi.show(replayButton)
    val accepted=broadcast?.prepareInstantReplay{ready->
     if(!isFinishing&&!isDestroyed){
      replayUi.setPrepared(ready,broadcast?.instantReplayDurationMs()?:10_000L)
      if(ready)showPrivateReplay() else hidePrivateReplay()
     }
    }==true
    if(!accepted)replayUi.setPrepared(false)
   }
   val keys=listOf(start,timeoutButton,replayButton,
    iconButton(darkButton("TOOLS"){openOperatorSettings()},"tools"))
   val widths=listOf(1.35f,1.0f,1.50f,.95f)
   replayButton.textSize=10f
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
   gameRunning=if(timeoutActive)false else (savedInstanceState?.getBoolean("v2_clock_running",false)?:false)
   if(::timeoutButton.isInitialized){
    timeoutButton.text=if(timeoutActive)"RESUME" else "TIMEOUT"
    iconButton(timeoutButton,if(timeoutActive)"play" else "clock")
   }
   lastTick=if(gameRunning)android.os.SystemClock.elapsedRealtime() else 0L
  timer.post(tick)

  if(!offlineMode&&(!Regex("^[a-f0-9]{32}$").matches(draftId)||BuildConfig.OAUTH_BACKEND_URL.isBlank())){
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
  if(offlineMode||!autoLaunchAllowed||!draftReady||!videoProfileReady||!checkPermissions())return
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
  if(::resetClockButton.isInitialized)resetClockButton.isEnabled=true
 }
 private fun refresh(){
  val service=broadcast
  if(!::start.isInitialized)return
  val usable=service!=null&&checkPermissions()
  val offlineNow=service?.isOfflineRecording()==true
  val liveNow=service?.isActive()==true
  val starting=service?.isStarting()==true
  start.isEnabled=usable&&draftReady&&videoProfileReady&&service?.isEnded()!=true&&
   (offlineMode||!starting)
  val label=when{
   offlineMode&&offlineNow->"STOP"
   offlineMode->"RECORD"
   liveNow->"STOP STREAMING"
   starting->"STARTING STREAM…"
   else->"GO LIVE"
  }
  if(start.text.toString()!=label){
   start.text=label
   if(offlineMode&&offlineNow||!offlineMode&&liveNow){
    start.background=touchSurface(Color.rgb(181,34,44),11)
    start.setTextColor(Color.WHITE)
    start.setCompoundDrawablesWithIntrinsicBounds(null,null,null,null)
   }else{
    start.background=touchSurface(Color.rgb(30,220,130),11)
    start.setTextColor(Color.rgb(7,36,27))
    if(offlineMode||starting)start.setCompoundDrawablesWithIntrinsicBounds(null,null,null,null)
    else iconButton(start,"live",true)
   }
  }
  if(service!=null){
   status.text=when{
    !draftReady->"This YouTube event has ended. Create a new broadcast from IMG setup."
    !videoProfileReady->"Selected video quality is unavailable. Return to setup."
    offlineMode->service.getStatus()
    else->service.getStatus()+" • "+service.connectionSummary()
   }
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
  outState.putInt("v2_shot_seconds",shotSeconds)
   outState.putInt("v2_initial_clock_seconds",configuredGameSeconds)
   outState.putBoolean("v2_shot_visible",shotClockVisible)
   outState.putBoolean("v2_clock_running",gameRunning)
   outState.putBoolean("v2_timeout_active",timeoutActive)
   outState.putBoolean("v2_timeout_clock_before",gameWasRunningBeforeTimeout)
   outState.putBoolean("v2_timeout_score_before",scoreWasVisibleBeforeTimeout)
   outState.putBoolean("v2_timeout_sponsors",timeoutSlideshowEnabled)
   outState.putBoolean("v2_timeout_auto",timeoutAuto)
   outState.putInt("v2_timeout_interval",timeoutIntervalSeconds)
   outState.putInt("v2_timeout_transition",timeoutTransitionMillis)
   super.onSaveInstanceState(outState)
 }
 override fun onDestroy(){
  timer.removeCallbacks(tick)
  operatorSheet?.dismiss()
  operatorSheet=null
  timeoutPreview?.dismiss()
   timeoutPreview=null
   replayUi.close()
   hidePrivateReplay()
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