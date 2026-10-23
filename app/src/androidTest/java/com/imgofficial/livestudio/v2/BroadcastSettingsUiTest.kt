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
  assertEquals("Missing or unclickable: "+action,"true",js(activity,web,
   "(function(){const b=document.querySelector('[data-action=\"'+\"$action\"+'\"]');if(!b)return false;b.click();return true})()"))
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
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-score-art'))")
   assertEquals("Team name field","true",js(activity,web,"Boolean(document.querySelector('[data-quick=\"homeName\"]'))"))
   assertEquals("Real scoreboard selection gallery","true",js(activity,web,
    "Boolean(document.querySelector('.img-wizard-page .graphics-library'))"))
   assertEquals("No separate X/Y sliders","false",js(activity,web,
    "Boolean(document.querySelector('[data-graphics-range^=\"overlayOffset\"]'))"))
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('[data-upload=\"sponsor-clip\"]'))")
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-quality'))")
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
