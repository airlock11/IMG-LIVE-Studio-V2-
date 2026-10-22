package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import android.webkit.ValueCallback
import android.webkit.WebSettings
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

/** Offline-first hybrid shell with HTTPS assets and Android media permission mediation. */
class MainActivity : Activity() {
 private lateinit var browser: WebView
 private var oauthClient: NativeOAuthClient? = null
 private lateinit var root: FrameLayout
 private var fullscreenView: View? = null
 private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
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
  // Real system clock, signal and battery; keep usable Android navigation.
  // Let Android fit the content to safe system-bar insets.
  window.statusBarColor = android.graphics.Color.rgb(16,23,27)
  window.navigationBarColor = android.graphics.Color.rgb(16,23,27)
  window.decorView.systemUiVisibility = 0
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
  if (BuildConfig.OAUTH_BACKEND_URL.isNotBlank()) oauthClient = NativeOAuthClient(this, BuildConfig.OAUTH_BACKEND_URL)
  browser = WebView(this).apply {
   settings.javaScriptEnabled = true
   settings.mediaPlaybackRequiresUserGesture = true
   settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
   settings.domStorageEnabled = true
   settings.setSupportMultipleWindows(false)
   settings.javaScriptCanOpenWindowsAutomatically = false
   // OAuth sessions and network requests live exclusively in native Android.
   CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
   settings.allowFileAccess = false
   settings.allowContentAccess = false
   settings.allowFileAccessFromFileURLs = false
   settings.allowUniversalAccessFromFileURLs = false
   webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
     super.onPageFinished(view, url)
     if(url == "$origin/assets/index.html" || url == "$origin/assets/connections.html") {
      view.evaluateJavascript("document.documentElement.classList.add('img-native-app');", null)
      if(BuildConfig.OAUTH_BACKEND_URL.isNotBlank()){
       // Verified YouTube account state is available directly in Broadcast
       // Settings, without navigating to a separate diagnostics page.
       view.evaluateJavascript("window.IMGAccounts?.enableNative(); window.IMGAccounts?.refresh().catch(()=>{});",null)
      }
     }
    }
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
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
     if(fullscreenView != null) { callback.onCustomViewHidden(); return }
     fullscreenView = view
     fullscreenCallback = callback
     root.addView(view, FrameLayout.LayoutParams(-1,-1))
     browser.visibility = View.GONE
    }
    override fun onHideCustomView() {
     val view = fullscreenView ?: return
     root.removeView(view)
     fullscreenView = null
     browser.visibility = View.VISIBLE
     fullscreenCallback?.onCustomViewHidden()
     fullscreenCallback = null
    }
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
   if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
    WebViewCompat.addWebMessageListener(this, "IMGNativeChannel", setOf(origin),
     object : WebViewCompat.WebMessageListener {
      override fun onPostMessage(view: WebView, message: WebMessageCompat,
       sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
       if (!isMainFrame || sourceOrigin.toString().removeSuffix("/") != origin) return
       val action = message.data ?: return
       if (action.startsWith("{")) {
        try {
         val command = JSONObject(action)
         if (command.optString("type") == "oauth-api") {
          val client = oauthClient ?: return
          client.request(command) { result ->
           runOnUiThread { replyProxy.postMessage(result.toString()) }
          }
         }
        } catch (_: Exception) {
         // Never execute arbitrary unrecognized WebView messages.
        }
       } else if(action in setOf("img-orientation:landscape","img-orientation:portrait","img-orientation:auto")) {
        // Only fixed, origin-checked orientation values are accepted. Avoid
        // WebView fullscreen tricks: native system bars remain real/visible.
        runOnUiThread {
         val requested=when(action){
          "img-orientation:landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
          "img-orientation:portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
          else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
         }
         if(requestedOrientation!=requested)requestedOrientation=requested
        }
       } else if (action.startsWith("native-private-video-start:")) {
        val draftId=action.removePrefix("native-private-video-start:")
        if(Regex("^[a-f0-9]{32}$").matches(draftId) &&
           BuildConfig.OAUTH_BACKEND_URL.isNotBlank()) {
         // Explicit user action in Broadcast Settings. Native Android still
         // validates that the draft is private/session-owned before RTMPS.
         runOnUiThread {
          startActivity(Intent(this@MainActivity,PrivateYoutubeCameraActivity::class.java)
           .putExtra("draftRequestId",draftId)
           .putExtra("img_autostart",true))
         }
        }
       } else if (action.startsWith("native-private-video:")) {
        val draftId=action.removePrefix("native-private-video:")
        if(Regex("^[a-f0-9]{32}$").matches(draftId) &&
           BuildConfig.OAUTH_BACKEND_URL.isNotBlank()) {
         runOnUiThread {
          startActivity(Intent(this@MainActivity,PrivateYoutubeCameraActivity::class.java)
           .putExtra("draftRequestId",draftId))
         }
        }
       } else if (action.startsWith("oauth-open:")) {
        runOnUiThread { openOAuthBrowser(action.removePrefix("oauth-open:")) }
       } else if (action in setOf("camera", "microphone", "broadcast", "record", "replay"))
        runOnUiThread { handleNativeAction(action) }
      }
     })
   }
   if(savedInstanceState == null) loadUrl("$origin/assets/index.html")
  }
  if(savedInstanceState != null) browser.restoreState(savedInstanceState)
  root = FrameLayout(this)
  root.addView(browser, FrameLayout.LayoutParams(-1,-1))
  setContentView(root)
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

 private fun openOAuthBrowser(raw: String) {
  try {
   val uri = Uri.parse(raw)
   val host = uri.host?.lowercase()
   if(uri.scheme != "https" || host !in setOf("accounts.google.com", "www.facebook.com")
      || uri.userInfo != null || uri.port != -1 || uri.fragment != null) {
    Toast.makeText(this,"Untrusted authorization link",Toast.LENGTH_LONG).show()
    return
   }
   startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE))
  } catch (_: Exception) {
   Toast.makeText(this,"Unable to open account authorization",Toast.LENGTH_LONG).show()
  }
 }

 private fun handleNativeAction(action: String) {
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
 // Returning from the system Google login browser doesn't always fire WebView
 // visibilitychange. Explicitly prompt the existing page to verify pairing;
 // the connection badge still changes only after confirmed backend status.
 override fun onResume() {
  super.onResume()
  if(::browser.isInitialized && browser.url?.startsWith("$origin/assets/")==true)
   browser.evaluateJavascript("window.dispatchEvent(new Event('img-native-resume'));",null)
 }
 @Deprecated("Deprecated in Java")
 override fun onBackPressed() {
  if(fullscreenView != null) browser.webChromeClient?.onHideCustomView()
  else if(browser.canGoBack()) browser.goBack() else super.onBackPressed()
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
  oauthClient?.close()
  // Origin-scoped WebMessage listener is released when WebView is destroyed.
  browser.destroy()
  super.onDestroy()
 }
}
