package com.imgofficial.livestudio.v2

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast

/**
 * App-contained Android MP4 playback for saved native OFFLINE and STREAM
 * recordings. Never sends ACTION_VIEW to an external third-party player.
 * No original recording file is moved, modified or deleted by this dialog.
 */
class V2SavedMp4Player(private val activity:Activity){
 private var dialog:Dialog?=null
 private var player:VideoView?=null
 private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
 fun show(uri:Uri,title:String){
  close()
  try{
   if(uri.scheme!="content"||uri.authority!="media")
    throw IllegalArgumentException("Unsupported saved recording address")
   // Verify file is still readable before attempting decoder startup.
   activity.contentResolver.openAssetFileDescriptor(uri,"r")?.use{
    if(it.length==0L)throw IllegalStateException("This saved recording is empty")
   }?:throw IllegalStateException("Saved video file is missing or access was revoked")
   val window=Dialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
   window.requestWindowFeature(Window.FEATURE_NO_TITLE)
   val root=FrameLayout(activity).apply{setBackgroundColor(Color.BLACK)}
   val video=VideoView(activity).apply{
    setBackgroundColor(Color.BLACK)
   }
   player=video
   root.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
   val overlay=LinearLayout(activity).apply{
    orientation=LinearLayout.HORIZONTAL
    gravity=Gravity.CENTER_VERTICAL
    setPadding(dp(14),dp(12),dp(14),dp(12))
    setBackgroundColor(0xAA101C18.toInt())
   }
   overlay.addView(TextView(activity).apply{
    text=title.ifBlank{"Saved recording"}
    textSize=14f
    setTextColor(Color.WHITE)
    maxLines=1
    ellipsize=android.text.TextUtils.TruncateAt.END
   },LinearLayout.LayoutParams(0,dp(42),1f))
   overlay.addView(TextView(activity).apply{
    text="✕  Close"
    contentDescription="Close saved video and return to IMG"
    textSize=14f
    setTextColor(0xFFB9F3D5.toInt())
    gravity=Gravity.CENTER
    setPadding(dp(12),0,dp(4),0)
    setOnClickListener{close()}
   },LinearLayout.LayoutParams(-2,dp(42)))
   root.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
   window.setContentView(root)
   window.window?.apply{
    setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
     View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
     View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
   }
   window.setOnDismissListener{
    try{player?.stopPlayback()}catch(_:Exception){}
    player=null
    if(dialog===window)dialog=null
   }
   dialog=window
   video.setVideoURI(uri)
   video.setOnErrorListener{_,what,extra->
    Toast.makeText(activity,
     "Saved MP4 could not be decoded (media error $what/$extra). The file has not been deleted.",
     Toast.LENGTH_LONG).show()
    close()
    true
   }
   video.setOnPreparedListener{mp->
    val controls=MediaController(activity)
    controls.setAnchorView(video)
    video.setMediaController(controls)
    mp.isLooping=false
    video.start()
    controls.show(2500)
   }
   window.show()
   window.window?.setLayout(-1,-1)
  }catch(error:Exception){
   close()
   val message=when(error){
    is java.io.FileNotFoundException->"Recording no longer exists on this phone."
    is SecurityException->"IMG no longer has permission to read this recording."
    else->error.message?.take(140)?:"Unable to open saved recording."
   }
   Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
  }
 }
 fun close(){
  val previous=dialog;dialog=null
  try{player?.stopPlayback()}catch(_:Exception){}
  player=null
  try{previous?.dismiss()}catch(_:Exception){}
 }
}
