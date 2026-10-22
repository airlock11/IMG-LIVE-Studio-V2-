package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

/** Offline-first hybrid shell with HTTPS assets and Android media permission mediation. */
class MainActivity : Activity() {
 private lateinit var browser: WebView
 private var pendingMediaRequest: PermissionRequest? = null
 private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
 private val fileChooserRequest = 502
 private val origin = "https://appassets.androidplatform.net"
 private val loader by lazy {
  WebViewAssetLoader.Builder()
   .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
   .build()
 }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  browser = WebView(this).apply {
   settings.javaScriptEnabled = true
   settings.mediaPlaybackRequiresUserGesture = true
   settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
   settings.domStorageEnabled = true
   settings.setSupportMultipleWindows(false)
   settings.javaScriptCanOpenWindowsAutomatically = false
   CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
   settings.allowFileAccess = false
   settings.allowContentAccess = false
   settings.allowFileAccessFromFileURLs = false
   settings.allowUniversalAccessFromFileURLs = false
   webViewClient = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
     return loader.shouldInterceptRequest(request.url)
    }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
     val url = request.url
     if(url.scheme == "https" && url.host == "appassets.androidplatform.net") return false
     if(request.isForMainFrame && request.hasGesture() &&
       (url.scheme == "https" || url.scheme == "http")) {
      try { startActivity(Intent(Intent.ACTION_VIEW, url)) }
      catch (_: Exception) { Toast.makeText(this@MainActivity,"No browser available",Toast.LENGTH_SHORT).show() }
     }
     return true
    }
   }
   webChromeClient = object : WebChromeClient() {
    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
     pendingFileChooser?.onReceiveValue(null)
     pendingFileChooser = callback
     return try {
      startActivityForResult(params.createIntent(), fileChooserRequest)
      true
     } catch (error: Exception) {
      pendingFileChooser = null
      callback.onReceiveValue(null)
      false
     }
    }
    override fun onPermissionRequest(request: PermissionRequest) {
     runOnUiThread { handleMediaRequest(request) }
    }
    override fun onPermissionRequestCanceled(request: PermissionRequest) {
     runOnUiThread {
      if(pendingMediaRequest === request) pendingMediaRequest = null
     }
    }
   }
   addJavascriptInterface(NativeBridge(), "IMGNative")
   if(savedInstanceState == null) loadUrl("$origin/assets/index.html")
  }
  if(savedInstanceState != null) browser.restoreState(savedInstanceState)
  setContentView(browser)
  window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
 }

 private fun handleMediaRequest(request: PermissionRequest) {
  if(request.origin.toString().removeSuffix("/") != origin) {
   request.deny()
   return
  }
  val allowed = setOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE,PermissionRequest.RESOURCE_AUDIO_CAPTURE)
  if(request.resources.any { it !in allowed }) { request.deny();return }
  val missing = mutableListOf<String>()
  if(PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources &&
   checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
   missing.add(Manifest.permission.CAMERA)
  if(PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources &&
   checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
   missing.add(Manifest.permission.RECORD_AUDIO)
  if(missing.isNotEmpty()) {
   pendingMediaRequest?.deny()
   pendingMediaRequest=request
   requestPermissions(missing.toTypedArray(), 501)
  } else request.grant(request.resources)
 }

 @Deprecated("Deprecated in Java")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if(requestCode == fileChooserRequest) {
   val callback = pendingFileChooser ?: return
   pendingFileChooser = null
   callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
  }
 }

 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==503 || requestCode==504) {
   val permitted=grantResults.isNotEmpty() && grantResults.all { it==PackageManager.PERMISSION_GRANTED }
   val type=if(requestCode==503) "camera" else "microphone"
   browser.evaluateJavascript("window.dispatchEvent(new CustomEvent('img-native-permission',{detail:{type:'$type',granted:$permitted}}))",null)
   return
  }
  if(requestCode==501) {
   val request=pendingMediaRequest ?: return
   pendingMediaRequest=null
   if(grantResults.isNotEmpty() && grantResults.all { it==PackageManager.PERMISSION_GRANTED })
    handleMediaRequest(request)
   else request.deny()
  }
 }

 private inner class NativeBridge {
  @JavascriptInterface fun command(action: String) {
   runOnUiThread {
    when(action) {
     "camera" -> {
      if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
       requestPermissions(arrayOf(Manifest.permission.CAMERA),503)
      else browser.evaluateJavascript("window.dispatchEvent(new Event('img-native-camera-ready'))",null)
     }
     "microphone" -> {
      if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
       requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),504)
      else browser.evaluateJavascript("window.dispatchEvent(new Event('img-native-microphone-ready'))",null)
     }
     "broadcast","record","replay" ->
      Toast.makeText(this@MainActivity,"Native broadcast engine not connected",Toast.LENGTH_LONG).show()
     else -> Toast.makeText(this@MainActivity,"Unsupported command",Toast.LENGTH_SHORT).show()
    }
   }
  }
 }
 @Deprecated("Deprecated in Java")
 override fun onBackPressed() {
  if(browser.canGoBack()) browser.goBack() else super.onBackPressed()
 }
 override fun onSaveInstanceState(outState: Bundle) {
  browser.saveState(outState)
  super.onSaveInstanceState(outState)
 }
 override fun onDestroy() {
  pendingMediaRequest?.deny()
  pendingFileChooser?.onReceiveValue(null)
  pendingFileChooser = null
  window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  browser.removeJavascriptInterface("IMGNative")
  browser.destroy()
  super.onDestroy()
 }
}
