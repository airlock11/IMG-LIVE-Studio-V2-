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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/**
 * Exercises the actual bundled WebView, rather than checking only app.js syntax.
 * Uses the offline setup path: the camera must stay visible without permission,
 * and the removed geometry knobs must not return.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastSettingsUiTest {
 private fun seek(view: View): WebView? {
  if(view is WebView) return view
  if(view is ViewGroup) for(i in 0 until view.childCount) {
   val result=seek(view.getChildAt(i))
   if(result!=null) return result
  }
  return null
 }
 private fun javascript(activity:android.app.Activity,web:WebView,code:String):String {
  val result=AtomicReference<String>("")
  val latch=CountDownLatch(1)
  activity.runOnUiThread {
   web.evaluateJavascript(code) { value ->
    result.set(value ?: "")
    latch.countDown()
   }
  }
  assertTrue("JavaScript query did not answer",latch.await(8,TimeUnit.SECONDS))
  return result.get()
 }
 private fun waitFor(activity:android.app.Activity,web:WebView,condition:String){
  for(i in 0 until 25){
   if(javascript(activity,web,condition)=="true")return
   SystemClock.sleep(400)
  }
  assertEquals("WebView did not reach expected state", "true", javascript(activity,web,condition))
 }
 @Test fun offlineSetupHasVisibleCameraAndSimpleControls(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val intent=Intent(context,MainActivity::class.java).apply {
   addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }
  val activity=instrumentation.startActivitySync(intent)
  try{
   val web=seek(activity.window.decorView)
   assertTrue("Native activity needs a real WebView",web!=null)
   web!!
   waitFor(activity,web,"Boolean(document.querySelector('[data-action=\"instant-live\"]'))")
   javascript(activity,web,"document.querySelector('[data-action=\"instant-live\"]').click();true")
   waitFor(activity,web,"Boolean(document.querySelector('.headerless-setup-app .preview-wrap'))")
   val metric=javascript(activity,web,"""(function(){
    const root=document.querySelector('.headerless-setup-app');
    const c=root?.querySelector('.preview-wrap .camera');
    const wrap=root?.querySelector('.preview-wrap');
    const primary=root?.querySelector('button[data-action="start-broadcast"]');
    const actionRect=primary?.getBoundingClientRect();
    return JSON.stringify({
     startButtonVisible:!!actionRect&&actionRect.width>=100&&actionRect.height>=36&&
      actionRect.top>=0&&actionRect.bottom<=window.innerHeight,
     startButtonBottom:actionRect?.bottom||0,
     viewportHeight:window.innerHeight,
     cameraHeight:c?.getBoundingClientRect().height||0,
     wrapHeight:wrap?.getBoundingClientRect().height||0,
     cameraDisplay:c?getComputedStyle(c).display:'missing',
     hasPositionControl:!!root?.querySelector('[data-set^="overlayPosition:"]'),
     hasXYControl:!!root?.querySelector('[data-graphics-range^="overlayOffset"]'),
     hasWidthHeight:!!root?.querySelector('[data-graphics-range^="overlayWidth"],[data-graphics-range^="overlayHeight"]'),
     brokenIconText:(root?.innerText||'').includes("'+ic(")
    });
   })()""")
   File(context.filesDir,"img-v2-setup-metrics.txt").writeText(metric)
   // Height measured in CSS px. A zero-area camera is unacceptable even
   // on emulator builds without attached camera hardware.
   val json=org.json.JSONObject(org.json.JSONTokener(metric).nextValue().toString())
   assertTrue("Camera viewport collapsed: "+metric,json.optDouble("cameraHeight")>=120.0)
   assertTrue("Preview wrapper collapsed: "+metric,json.optDouble("wrapHeight")>=120.0)
   assertTrue("Start/Record button must stay on screen: "+metric,json.optBoolean("startButtonVisible"))
   assertEquals("none of the old Position buttons",false,json.optBoolean("hasPositionControl"))
   assertEquals("no old X/Y offset sliders",false,json.optBoolean("hasXYControl"))
   assertEquals("no separate width/height knobs",false,json.optBoolean("hasWidthHeight"))
   assertEquals("no source template icon text",false,json.optBoolean("brokenIconText"))
  }finally{
   activity.runOnUiThread { activity.finish() }
  }
 }
}
