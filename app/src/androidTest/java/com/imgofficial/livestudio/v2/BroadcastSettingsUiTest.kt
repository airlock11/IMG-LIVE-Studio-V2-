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
  // Android WebView can discard the callback for a frame that navigates while
  // evaluateJavascript is in flight. Retry the same read-only query after the
  // next frame; a continuously unresponsive WebView still fails the test.
  for(attempt in 1..3){
   val result=AtomicReference("")
   val latch=CountDownLatch(1)
   activity.runOnUiThread{
    web.evaluateJavascript(code){value->result.set(value?:"");latch.countDown()}
   }
   if(latch.await(5,TimeUnit.SECONDS))return result.get()
   SystemClock.sleep(350)
  }
  throw AssertionError("WebView JavaScript did not respond after 3 attempts")
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
   val viewport=js(activity,web,"""(function(){
    const rect=el=>{if(!el)return null;const r=el.getBoundingClientRect(),c=getComputedStyle(el);return {top:r.top,bottom:r.bottom,height:r.height,scrollHeight:el.scrollHeight,clientHeight:el.clientHeight,overflow:c.overflow,display:c.display,position:c.position,background:c.backgroundColor}};
    return JSON.stringify({height:window.innerHeight,width:window.innerWidth,
      app:rect(document.querySelector('.app')),
      content:rect(document.querySelector('.app>.content')),
      wizard:rect(document.querySelector('.img-wizard-page')),
      scroll:rect(document.querySelector('.img-wizard-scroll')),
      card:rect(document.querySelector('.img-wizard-card')),
      sport:rect(document.querySelector('.img-wizard-sport')),
      body:rect(document.body)});
   })()""")
   File(context.filesDir,"img-v2-wizard-viewport.txt").writeText(viewport)
   assertEquals("Wizard must fill the real Android WebView viewport","true",js(activity,web,
    "(function(){const a=document.querySelector('.app'),w=document.querySelector('.img-wizard-page');return !!a&&!!w&&a.getBoundingClientRect().height>=window.innerHeight*0.85&&w.getBoundingClientRect().height>=window.innerHeight*0.85})()"))
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
    "(function(){const c=document.querySelector('.img-wizard-score-sticky-unit canvas');return !!c&&c.width===1280&&c.height===720})()"))
   assertEquals("Preview becomes sticky only after it scrolls out","true",js(activity,web,
    "(function(){const p=document.querySelector('.img-wizard-scroll'),a=document.querySelector('.img-wizard-score-sticky-unit');if(!p||!a)return false;p.scrollTop+=a.getBoundingClientRect().bottom-p.getBoundingClientRect().top+30;p.dispatchEvent(new Event('scroll',{bubbles:true}));return true})()"))
   SystemClock.sleep(700)
   // The FIRST native canvas itself must remain one DOM element and pin in place.
   try {
    waitFor(activity,web,"(function(){const a=document.querySelector('.img-wizard-score-sticky-unit');return !!a&&a.classList.contains('img-score-pinned')})()")
   }catch(ex:AssertionError){
    val debug=js(activity,web,"""(function(){
      const p=document.querySelector('.img-wizard-scroll'),a=document.querySelector('.img-wizard-score-sticky-unit');
      return JSON.stringify({scroll:p?.scrollTop,max:p?(p.scrollHeight-p.clientHeight):0,
       artTop:a?.getBoundingClientRect().top,paneTop:p?.getBoundingClientRect().top,
       pinned:a?.classList.contains('img-score-pinned'),canvases:document.querySelectorAll('canvas[data-setup-scoreboard]').length});
     })()""")
    throw AssertionError("Original in-place sticky preview: "+debug,ex)
   }
   assertEquals("Exactly one physical scoreboard preview","true",js(activity,web,
    "document.querySelectorAll('canvas[data-setup-scoreboard]').length===1"))
   assertEquals("Size slider is directly below original preview, without percentage","true",js(activity,web,
    """(function(){
      const unit=document.querySelector('.img-wizard-score-sticky-unit');
      const canvas=unit?.querySelector('canvas[data-setup-scoreboard]');
      const slider=unit?.querySelector('input[data-score-range="size"]');
      const art=unit?.querySelector('.img-wizard-score-art');
      return !!unit&&!!canvas&&!!slider&&!!art
       &&slider.getBoundingClientRect().top>=art.getBoundingClientRect().bottom
       &&unit.querySelectorAll('[data-score-range="size"]').length===1
       &&document.querySelectorAll('.img-wizard-page [data-score-range="size"]').length===1
       &&unit.querySelector('output')===null;
     })()"""))
   assertEquals("The anchored size slider moves with original preview","true",js(activity,web,
    """(function(){
      const unit=document.querySelector('.img-wizard-score-sticky-unit');
      const art=unit?.querySelector('.img-wizard-score-art');
      const slider=unit?.querySelector('input[data-score-range="size"]');
      return !!unit&&unit.classList.contains('img-score-pinned')
       &&!!art&&!!slider&&slider.closest('.img-wizard-score-sticky-unit')===unit;
     })()"""))
   assertEquals("Native 16:9 bitmap stays in original Scoreboard card","true",js(activity,web,
    "(function(){const c=document.querySelector('.img-wizard-score-sticky-unit canvas');return !!c&&c.width===1280&&c.height===720})()"))
   assertEquals("No duplicate preview container","false",js(activity,web,
    "Boolean(document.querySelector('.img-wizard-sticky-preview'))"))
   val visiblePinned=js(activity,web,
    """(function(){
      const a=document.querySelector('.img-wizard-score-sticky-unit'),p=document.querySelector('.img-wizard-scroll');
      if(!a||!p)return false;
      const r=a.getBoundingClientRect(),b=p.getBoundingClientRect();
      const node=document.elementFromPoint(r.left+r.width/2,r.top+Math.min(25,r.height/2));
      return getComputedStyle(a).position==='fixed'&&a.classList.contains('img-score-pinned')
       &&Math.abs(r.top-b.top)<5&&r.width>150&&r.height>80
       &&(node===a||a.contains(node));
     })()""")
   if(visiblePinned!="true"){
    val debug=js(activity,web,"""(function(){
     const a=document.querySelector('.img-wizard-score-sticky-unit'),p=document.querySelector('.img-wizard-scroll'),r=a?.getBoundingClientRect(),b=p?.getBoundingClientRect(),pt=r?{x:r.left+r.width/2,y:r.top+Math.min(25,r.height/2)}:null,n=pt?document.elementFromPoint(pt.x,pt.y):null;
     return JSON.stringify({pinned:a?.classList.contains('img-score-pinned'),parent:a?.parentElement?.className,position:a?getComputedStyle(a).position:null,
      styleTop:a?.style.getPropertyValue('--img-score-top'),styleLeft:a?.style.getPropertyValue('--img-score-left'),
      art:r?{top:r.top,left:r.left,width:r.width,height:r.height,bottom:r.bottom}:null,pane:b?{top:b.top,left:b.left,width:b.width,height:b.height}:null,
      node:n?.tagName,hitClass:n?.className,inside:a?.contains(n),atPoint:pt,device:window.devicePixelRatio});
    })()""")
    throw AssertionError("Original preview visibility diagnostic: "+debug)
   }
   capture(activity,2,"scoreboard")
   js(activity,web,"(function(){const p=document.querySelector('.img-wizard-scroll');if(!p)return false;p.scrollTop=0;return true})()")
   waitFor(activity,web,"(function(){const e=document.querySelector('.img-wizard-score-sticky-unit');return !!e&&!e.classList.contains('img-score-pinned')})()")

   // Issue 1: selecting another original on-air graphic never scrolls the pane.
   assertEquals("Choosing a scoreboard must preserve exact scrolling","true",js(activity,web,
    """(function(){
      const p=document.querySelector('.img-wizard-scroll');
      const library=document.querySelector('.graphics-library');
      if(!p||!library)return false;
      p.scrollTop=Math.min(300,p.scrollHeight-p.clientHeight);
      const before=p.scrollTop;
      const selected=library.querySelector('[data-set="overlay:courtside-a"]');
      if(!selected)return false;
      selected.click();
      return p.scrollTop===before&&selected.classList.contains('active')
       &&document.querySelector('.img-wizard-page')!==null;
     })()"""))
   // Issue 2: both dragging and releasing the Size slider avoid DOM rebuild.
   assertEquals("Scoreboard slider input and change must preserve scroll","true",js(activity,web,
    """(function(){
      const p=document.querySelector('.img-wizard-scroll');
      const slider=document.querySelector('[data-score-range="size"]');
      if(!p||!slider)return false;
      p.scrollTop=Math.min(300,p.scrollHeight-p.clientHeight);
      const before=p.scrollTop,original=slider;
      slider.value='75';
      slider.dispatchEvent(new Event('input',{bubbles:true}));
      slider.dispatchEvent(new Event('change',{bubbles:true}));
      return p.scrollTop===before&&document.querySelector('[data-score-range="size"]')===original
       &&slider.value==='75'&&slider.closest('.img-wizard-score-sticky-unit')!==null
       &&document.querySelector('.img-wizard-page [data-score-range-output="size"]')===null;
     })()"""))
   assertEquals("Scoreboard frame must have no dark side fill","true",js(activity,web,
    """(function(){
      const a=document.querySelector('.img-wizard-score-sticky-unit'),c=a?.querySelector('canvas');
      if(!a||!c)return false;
      const back=getComputedStyle(a).backgroundColor;
      return (back==='rgba(0, 0, 0, 0)'||back==='transparent')&&c.width===1280&&c.height===720;
    })()"""))


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
   assertEquals("Offline Recording action is available in Step 4","true",js(activity,web,
    "Boolean(document.querySelector('[data-action=setup-record-offline]'))"))
   assertEquals("Opening offline controls must NOT start recording","false",js(activity,web,
    "Boolean(window.IMGMedia?.isRecording?.())"))
   // Do not open a second native Activity from within this WebView setup
   // screenshot test: Android may finish the foreground host when its
   // instrumentation ends, producing a false-negative process-liveness check.
   // The bridge and native RECORD button are covered by source regressions;
   // physical encoder/start/stop requires the separate phone test.
   assertEquals("Offline click is wired to the native bridge","true",js(activity,web,
    "Boolean(document.querySelector('[data-action=setup-record-offline]'))"))

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
   // Previous instrumentation may leave the WebView hash on an offline page.
   // Navigate to Home deterministically before checking Create League.
   waitFor(activity,web,"Boolean(document.querySelector('.app'))")
   activity.runOnUiThread {
    web.evaluateJavascript("history.replaceState({screen:'home'},'', '#/home');window.dispatchEvent(new PopStateEvent('popstate',{state:{screen:'home'}}))",null)
   }
   waitFor(activity,web,"Boolean(document.querySelector('[data-go=create]'))")
   // A navigation click replaces WebView DOM; do not await an evaluateJavascript
   // return callback that Android is allowed to discard during navigation.
   activity.runOnUiThread {
    web.evaluateJavascript("document.querySelector('[data-go=create]')?.click()",null)
   }
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=create-sport-basketball]'))")
   assertEquals("Create League starts with Choose Sports","true",js(activity,web,
    "(document.querySelector('.create-wizard-page')?.innerText||'').includes('Choose Sports')"))
   activity.runOnUiThread {
    web.evaluateJavascript("document.querySelector('[data-action=create-sport-pickleball]')?.click()",null)
   }
   assertEquals("Pickleball can be selected","true",js(activity,web,
    "document.querySelector('[data-action=create-sport-pickleball]')?.getAttribute('aria-pressed')==='true'"))
   click(activity,web,"create-next")
   waitFor(activity,web,"Boolean(document.querySelector('#league-name'))")
  }finally{
   activity.runOnUiThread{activity.finish()}
  }
 }
}

// Native offline uses the existing Android scoreboard and controls, without
// requiring a YouTube draft. An actual camera/encoder cycle is for phone QA.
