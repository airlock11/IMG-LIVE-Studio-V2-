package com.imgofficial.livestudio.v2

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas

/** The real bundled WebView must show the four-stage IMG operator wizard, not camera setup. */
@RunWith(AndroidJUnit4::class)
class BroadcastSettingsUiTest {
 private fun seek(view: View): WebView? {
  if(view is WebView)return view
  if(view is ViewGroup)for(i in 0 until view.childCount)seek(view.getChildAt(i))?.let{return it}
  return null
 }
 private fun js(activity:android.app.Activity,web:WebView,code:String):String {
  val result=AtomicReference("")
  val latch=CountDownLatch(1)
  activity.runOnUiThread{
   web.evaluateJavascript(code){value->result.set(value?:"");latch.countDown()}
  }
  assertTrue("WebView JavaScript did not respond",latch.await(8,TimeUnit.SECONDS))
  return result.get()
 }
 private fun waitFor(activity:android.app.Activity,web:WebView,test:String){
  repeat(25){
   if(js(activity,web,test)=="true")return
   SystemClock.sleep(400)
  }
  assertEquals("Expected setup state not reached","true",js(activity,web,test))
 }
 private fun click(activity:android.app.Activity,web:WebView,action:String){
  val selector="[data-action=\\\""+action+"\\\"]"
  assertEquals("Missing or unclickable: "+action,"true",
   js(activity,web,"Boolean(document.querySelector('"+selector+"'))"))
  // Do not require an evaluateJavascript result from a click that synchronously
  // replaces the DOM: Android WebView may discard that callback on navigation.
  activity.runOnUiThread {
   web.evaluateJavascript("document.querySelector('"+selector+"')?.click()",null)
  }
 }
 private fun capture(activity:android.app.Activity,step:Int,part:String=""){
  val latch=CountDownLatch(1)
  val target=File(activity.filesDir,"img-v2-wizard-step"+step+(if(part.isBlank())"" else "-"+part)+".png")
  activity.runOnUiThread {
   try{
    val root=activity.window.decorView
    if(root.width>0&&root.height>0){
     val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
     root.draw(Canvas(bitmap))
     target.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
     bitmap.recycle()
    }
   }finally{latch.countDown()}
  }
  assertTrue("Screenshot capture timed out",latch.await(12,TimeUnit.SECONDS))
  assertTrue("Step "+step+" screenshot was not saved",target.exists()&&target.length()>0)
 }
 @Test fun fourStepSetupHasNoCameraAndRealQualityControls(){
  val instrument=InstrumentationRegistry.getInstrumentation()
  val context=instrument.targetContext
  val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).apply {
   addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  })
  try{
   val web=seek(activity.window.decorView)
   assertTrue("Real native WebView is missing",web!=null)
   web!!
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=\"instant-live\"]'))")
   click(activity,web,"instant-live")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-page'))")
   assertEquals("Step 1 has no camera preview","false",js(activity,web,
    "Boolean(document.querySelector('.img-wizard-page .preview-wrap,.img-wizard-page video[data-img-camera]'))"))
   capture(activity,1)
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-score-art'))")
   SystemClock.sleep(600)
   capture(activity,2)
   assertEquals("Team name field","true",js(activity,web,"Boolean(document.querySelector('[data-quick=\"homeName\"]'))"))
   assertEquals("Real scoreboard selection gallery","true",js(activity,web,
    "Boolean(document.querySelector('.img-wizard-page .graphics-library'))"))
   assertEquals("No separate X/Y sliders","false",js(activity,web,
    "Boolean(document.querySelector('[data-graphics-range^=\"overlayOffset\"]'))"))
   js(activity,web,"document.querySelector('.img-wizard-score-art')?.scrollIntoView({block:'center'});true")
   SystemClock.sleep(650)
   capture(activity,2,"scoreboard")
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('[data-upload=\"sponsor-clip\"]'))")
   SystemClock.sleep(650)
   capture(activity,3)
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-quality'))")
   SystemClock.sleep(650)
   capture(activity,4)
   val localToggle=js(activity,web,"Boolean(document.querySelector('[data-action=setup-destination-youtube]'))")
   assertEquals("YouTube destination missing","true",localToggle)
   click(activity,web,"setup-destination-youtube")
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=toggle-record-local]'))")
   click(activity,web,"toggle-record-local")
   assertEquals("Native recording switch must work","true",
    js(activity,web,"Boolean(document.querySelector('[data-action=toggle-record-local][aria-checked=true]'))"))
   val metric=js(activity,web,"""(function(){
    const root=document.querySelector('.img-wizard-page');
    const controls=root?.querySelector('.img-wizard-quality');
    const button=root?.querySelector('button[data-action="start-broadcast"]');
    const rect=button?.getBoundingClientRect();
    return JSON.stringify({
      stageCount:root?.querySelectorAll('.img-wizard-track>div').length||0,
      noCamera:!root?.querySelector('.preview-wrap,.camera-source'),
      resolutionButtons:controls?.querySelectorAll('[data-set^="quality:"]').length||0,
      fpsButtons:controls?.querySelectorAll('[data-set^="fps:"]').length||0,
      startVisible:!!rect&&rect.width>=100&&rect.height>=36&&rect.bottom<=window.innerHeight,
      noRawIconText:!(root?.innerText||'').includes("'+ic(")
    });
   })()""")
   File(context.filesDir,"img-v2-setup-metrics.txt").writeText(metric)
   val details=JSONObject(JSONTokener(metric).nextValue().toString())
   assertEquals(4,details.optInt("stageCount"))
   assertTrue("Setup must not show a camera",details.optBoolean("noCamera"))
   assertEquals(2,details.optInt("resolutionButtons"))
   assertEquals(2,details.optInt("fpsButtons"))
   assertTrue("Start button is off screen: "+metric,details.optBoolean("startVisible"))
   assertTrue("Invalid icon template text: "+metric,details.optBoolean("noRawIconText"))
  }finally{
   activity.runOnUiThread{activity.finish()}
  }
 }
}
