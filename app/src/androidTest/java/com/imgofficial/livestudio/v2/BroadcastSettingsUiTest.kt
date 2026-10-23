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
  val selector="[data-action="+action+"]"
  // Only wait until the tap is queued on Android's UI thread. A WebView
  // response callback can be lost during DOM replacement or emulator startup.
  // Each caller verifies the resulting screen or control after this action.
  val queued=CountDownLatch(1)
  activity.runOnUiThread {
   web.evaluateJavascript("document.querySelector('"+selector+"')?.click()",null)
   queued.countDown()
  }
  assertTrue("Could not dispatch "+action,queued.await(8,TimeUnit.SECONDS))
 }
 private fun capture(activity:android.app.Activity,step:Int,part:String=""){
  val target=File(activity.filesDir,"img-v2-wizard-step"+step+(if(part.isBlank())"" else "-"+part)+".png")
  // WebView is hardware-composited: View.draw(Canvas) can return the PREVIOUS
  // frame after a successful navigation. Read the actual Android display.
  SystemClock.sleep(600)
  val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
  assertTrue("Android could not capture displayed Step "+step,bitmap!=null)
  target.outputStream().use{bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it)}
  bitmap!!.recycle()
  assertTrue("Step "+step+" screenshot missing",target.exists()&&target.length()>0)
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
   // Verify that the ACTUAL native bitmap was composited, not merely that
   // a gallery of static thumbnails exists elsewhere on the screen.
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-score-art.img-wizard-score-ready'))")
   js(activity,web,"(function(){const pane=document.querySelector('.img-wizard-scroll'),art=document.querySelector('.img-wizard-score-art');if(!pane||!art)return false;pane.scrollTop+=art.getBoundingClientRect().top-pane.getBoundingClientRect().top-130;return true})()")
   SystemClock.sleep(650)
   assertEquals("Landscape preview uses 16:9 broadcast coordinates","true",js(activity,web,
    "(function(){const c=document.querySelector('.img-wizard-score-origin canvas');return !!c&&c.width===1280&&c.height===720})()"))
   assertEquals("Preview becomes sticky only after it scrolls out","true",js(activity,web,
    "(function(){const p=document.querySelector('.img-wizard-scroll'),a=document.querySelector('.img-wizard-score-origin');if(!p||!a)return false;p.scrollTop+=a.getBoundingClientRect().bottom-p.getBoundingClientRect().top+30;p.dispatchEvent(new Event('scroll',{bubbles:true}));return true})()"))
   SystemClock.sleep(700)
   try {
    waitFor(activity,web,"(function(){const e=document.querySelector('.img-wizard-sticky-preview');return !!e&&!e.hidden})()")
   }catch(ex:AssertionError){
    val debug=js(activity,web,"""(function(){
     const p=document.querySelector('.img-wizard-scroll'),a=document.querySelector('.img-wizard-score-origin'),
      c=document.querySelector('[data-img-scoreboard-settings]'),h=document.querySelector('.img-wizard-sticky-preview');
     return JSON.stringify({scroll:p?.scrollTop,maxScroll:p?(p.scrollHeight-p.clientHeight):0,
      paneTop:p?.getBoundingClientRect().top,artBottom:a?.getBoundingClientRect().bottom,
      sectionBottom:c?.getBoundingClientRect().bottom,hasHolder:!!h,holderHidden:h?.hidden,
      sectionCount:document.querySelectorAll('[data-img-scoreboard-settings]').length});
    })()""")
    throw AssertionError("Sticky preview diagnostic: "+debug,ex)
   }
   assertEquals("Sticky preview must preserve native 16:9 space","true",js(activity,web,
    "(function(){const c=document.querySelector('.img-wizard-sticky-preview canvas');return !!c&&c.width===1280&&c.height===720})()"))
   capture(activity,2,"scoreboard")
   js(activity,web,"(function(){const p=document.querySelector('.img-wizard-scroll');if(!p)return false;p.scrollTop=0;return true})()")
   waitFor(activity,web,"(function(){const e=document.querySelector('.img-wizard-sticky-preview');return !!e&&e.hidden})()")

   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('[data-upload=\"sponsor-clip\"]'))")
   waitFor(activity,web,"(function(){const e=document.querySelector('.img-wizard-scroll');return !!e&&e.scrollTop<5&&window.scrollY<5})()")
   SystemClock.sleep(650)
   capture(activity,3)
   click(activity,web,"setup-next")
   waitFor(activity,web,"Boolean(document.querySelector('.img-wizard-quality'))")
   waitFor(activity,web,"(function(){const e=document.querySelector('.img-wizard-scroll');return !!e&&e.scrollTop<5&&window.scrollY<5})()")
   SystemClock.sleep(650)
   capture(activity,4)
   assertEquals("Offline Recording must be a button, never a switch","true",js(activity,web,
    "(function(){const b=document.querySelector('[data-action=setup-record-offline]');return !!b&&b.tagName==='BUTTON'&&b.getAttribute('role')!=='switch'})()"))
   assertEquals("Video Quality summary block must be removed","false",js(activity,web,
    "Boolean(document.querySelector('.img-wizard-output'))"))
   assertEquals("Go Live must not advertise PRIVATE in button label","false",js(activity,web,
    "(document.querySelector('[data-action=start-broadcast]')?.textContent||'').includes('PRIVATE')"))
   assertEquals("Offline recording must use its own entry, not main GO LIVE CTA","true",js(activity,web,
    "(document.querySelector('[data-action=start-broadcast]')?.textContent||'').includes('GO LIVE NOW')"))
   val localToggle=js(activity,web,"Boolean(document.querySelector('[data-action=destination-yt]'))")
   assertEquals("YouTube destination missing","true",localToggle)
   click(activity,web,"destination-yt")
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
   // The operator must deliberately press Connect before any progress text
   // appears. Expanding the YouTube row alone never starts Google OAuth.
   assertEquals("No premature Connecting below Connect","false",js(activity,web,
    "Boolean(document.querySelector('#youtube-destination .img-wizard-platform-error'))"))
   assertEquals("Clicking Offline Recording opens Broadcasting ready state","true",js(activity,web,
    "Boolean(document.querySelector('[data-action=setup-record-offline]'))"))
   click(activity,web,"setup-record-offline")
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=start-offline-recording]'))")
   assertEquals("Offline entry must NOT start recording","false",js(activity,web,
    "Boolean(window.IMGMedia?.isRecording?.())"))

  }finally{
   activity.runOnUiThread{activity.finish()}
  }
 }
 @Test fun createLeagueBeginsWithChooseSports(){
  val instrument=InstrumentationRegistry.getInstrumentation()
  val activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java).apply {
   addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  })
  try{
   val web=seek(activity.window.decorView)!!
   waitFor(activity,web,"Boolean(document.querySelector('[data-go=create]'))")
   js(activity,web,"document.querySelector('[data-go=create]')?.click();true")
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=create-sport-basketball]'))")
   assertEquals("Create League starts with Choose Sports","true",js(activity,web,
    "(document.querySelector('.create-wizard-page')?.innerText||'').includes('Choose Sports')"))
   js(activity,web,"document.querySelector('[data-action=create-sport-pickleball]')?.click();true")
   assertEquals("Pickleball can be selected","true",js(activity,web,
    "document.querySelector('[data-action=create-sport-pickleball]')?.getAttribute('aria-pressed')==='true'"))
   click(activity,web,"create-next")
   waitFor(activity,web,"Boolean(document.querySelector('#league-name'))")
  }finally{
   activity.runOnUiThread{activity.finish()}
  }
 }
}
