package com.imgofficial.livestudio.v2

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
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
import androidx.browser.customtabs.CustomTabsIntent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

/** Offline-first hybrid shell with HTTPS assets and Android media permission mediation. */
class MainActivity : Activity() {
 private val savedPlayer by lazy { V2SavedMp4Player(this) }
 // Receive origin-verified original sponsor images in bounded chunks and
 // store them locally, without thumbnail degradation or Intent-size failures.
 private val incomingSponsorParts=mutableMapOf<String,MutableList<StringBuilder>>()
 private fun receiveSponsorTransfer(command:String){
  val pieces=command.split(':',limit=4)
  if(pieces.size<2)return
  val token=pieces[1]
  if(!token.matches(Regex("^[a-f0-9]{16}$")))return
  when(pieces[0]){
   "native-sponsor-begin"->{
    val count=pieces.getOrNull(2)?.toIntOrNull()?:return
    if(count !in 1..12)return
    incomingSponsorParts.clear()
    incomingSponsorParts[token]=MutableList(count){StringBuilder()}
   }
   "native-sponsor-chunk"->{
    if(pieces.size!=4)return
    val index=pieces[2].toIntOrNull()?:return
    val arr=incomingSponsorParts[token]?:return
    if(index !in arr.indices)return
    val chunk=pieces[3]
    if(chunk.length>48000||chunk.any{
      it !in 'A'..'Z'&&it !in 'a'..'z'&&it !in '0'..'9'&&it!='+'&&it!='/'&&it!='='
     })return
    if(arr[index].length+chunk.length>3500000)return
    arr[index].append(chunk)
   }
   "native-sponsor-end"->{
    val arr=incomingSponsorParts.remove(token)?:return
    var ok=true
    arr.forEachIndexed{index,source->
     if(source.isEmpty())return@forEachIndexed
     try{
      val bytes=android.util.Base64.decode(source.toString(),android.util.Base64.DEFAULT)
      if(bytes.size !in 1..2500000)throw IllegalArgumentException("Image size")
      val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
      android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
      if(bounds.outWidth !in 1..4096||bounds.outHeight !in 1..4096)
       throw IllegalArgumentException("Image dimensions")
      java.io.File(cacheDir,"imgsponsor_${token}_${index}.img").writeBytes(bytes)
     }catch(_:Exception){ok=false}
    }
    val response=JSONObject().put("token",token).put("ok",ok).toString()
    runOnUiThread{
     if(::browser.isInitialized)
      browser.evaluateJavascript("window.dispatchEvent(new CustomEvent('img-native-sponsors-ready',{detail:"+response+"}));",null)
    }
   }
  }
 }
 private var graphicsGalleryPayload:String?=null
 @Volatile private var graphicsGalleryBusy=false
 // The picker shows snapshots produced by THE ACTUAL native H.264 overlay
 // renderer. No fabricated CSS thumbnails: the operator sees true artwork.
 private fun generateGraphicsGallery(){
  val existing=graphicsGalleryPayload
  if(existing!=null){
   browser.evaluateJavascript("window.dispatchEvent(new CustomEvent('img-native-graphics-gallery',{detail:"+existing+"}));",null)
   return
  }
  if(graphicsGalleryBusy)return
  graphicsGalleryBusy=true
  Thread{
   val items=JSONArray()
   val example=V2Scorecard.State(home="HOME",away="AWAY",homeScore=82,awayScore=89,
    homeFouls=3,awayFouls=4,period=3,clock="3:01")
   try{
    for(i in V2BroadcastGraphicsRenderer.names.indices){
     val full=V2BroadcastGraphicsRenderer.render(example,1f,null,i,null,null)
     val compact=V2BroadcastGraphicsRenderer.compact(i)
     val thumb=android.graphics.Bitmap.createScaledBitmap(full,
      if(compact)126 else 360,if(compact)126 else 54,true)
     val bytes=java.io.ByteArrayOutputStream()
     thumb.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes)
     val encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)
     if(encoded.length<100000)items.put(JSONObject().put("id",i)
      .put("src","data:image/png;base64,"+encoded))
     if(thumb!==full)thumb.recycle()
     full.recycle()
    }
    val result=JSONObject().put("items",items).toString()
    graphicsGalleryPayload=result
    runOnUiThread{
     if(!isFinishing && ::browser.isInitialized)
      browser.evaluateJavascript("window.dispatchEvent(new CustomEvent('img-native-graphics-gallery',{detail:"+result+"}));",null)
    }
   }catch(_:Exception){}finally{graphicsGalleryBusy=false}
  }.start()
 }
 // Camera previews in Broadcast Settings use the actual same native graphic
 // renderer as the H.264 GL filter, rather than WebView's legacy scorebar.
 @Volatile private var frameRequestEpoch=0
 private fun decodeGraphicLogo(data:String?):android.graphics.Bitmap?{
  if(data.isNullOrBlank()||data.length>65000||
   !(data.startsWith("data:image/png;base64,")||
     data.startsWith("data:image/jpeg;base64,")))return null
  return try{
   val bytes=android.util.Base64.decode(data.substringAfter(','),android.util.Base64.DEFAULT)
   if(bytes.size !in 1..48000)return null
   val opts=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
   android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
   if(opts.outWidth !in 1..256||opts.outHeight !in 1..256)return null
   android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
  }catch(_:Exception){null}
 }
 // Probe real AVC encoder profiles on the Android device. Results are
 // advisory until camera permission is granted; native prepareCamera also
 // enforces capture/encoder support and never silently changes resolution.
 private fun reportVideoCapabilities(){
  Thread {
   val supported=JSONArray()
   try{
    val avc=android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
     .codecInfos.filter{it.isEncoder&&it.supportedTypes.any{type->type.equals("video/avc",true)}}
    for(height in listOf(720,1080))for(fps in listOf(30,60)){
     val width=height*16/9
     val ok=avc.any{codec->
      try{codec.getCapabilitiesForType("video/avc").videoCapabilities
       .areSizeAndRateSupported(width,height,fps.toDouble())
      }catch(_:Exception){false}
     }
     if(ok)supported.put(height.toString()+"p:"+fps)
    }
   }catch(_:Exception){
    // Unknown capability is not proof a profile is unsupported; the encoder
    // performs the final check when starting the actual camera.
    return@Thread
   }
   val detail=JSONObject().put("supported",supported).toString()
   runOnUiThread {
    if(!isFinishing&&::browser.isInitialized)browser.evaluateJavascript(
     "window.dispatchEvent(new CustomEvent('img-native-video-capabilities',{detail:"+detail+"}));",null)
   }
  }.start()
 }
 private fun nativeGraphicFrame(encoded:String){
  if(encoded.length !in 3..140000)return
  val epoch=++frameRequestEpoch
  Thread {
   var homeLogo:android.graphics.Bitmap?=null
   var awayLogo:android.graphics.Bitmap?=null
   try{
    val json=JSONObject(Uri.decode(encoded))
    val id=json.optInt("style",0).coerceIn(0,11)
    val key=json.optString("key").take(360)
    if(key.isBlank())return@Thread
    homeLogo=decodeGraphicLogo(json.optString("homeLogo"))
    awayLogo=decodeGraphicLogo(json.optString("awayLogo"))
    val state=V2Scorecard.State(
     sport="basketball",
     home=json.optString("home","HOME").trim().take(18).ifEmpty{"HOME"},
     away=json.optString("away","AWAY").trim().take(18).ifEmpty{"AWAY"},
     homeScore=json.optInt("homeScore",0).coerceIn(0,999),
     awayScore=json.optInt("awayScore",0).coerceIn(0,999),
     homeFouls=json.optInt("homeFouls",0).coerceIn(0,99),
     awayFouls=json.optInt("awayFouls",0).coerceIn(0,99),
     period=json.optInt("period",1).coerceIn(1,9),
     clock=json.optString("clock","10:00").take(7),
     shotClock=json.optInt("shot",24).coerceIn(0,99)
    )
    val bmp=V2BroadcastGraphicsRenderer.render(state,
     json.optDouble("opacity",1.0).toFloat().coerceIn(0f,1f),
     null,id,homeLogo,awayLogo)
    // The browser needs only a small preview; retain full native resolution
    // for the actual encoder filter. Resize its exact artwork before PNG IPC.
    val compact=V2BroadcastGraphicsRenderer.compact(id)
    val targetWidth=if(compact)256 else 640
    val scale=minOf(1f,targetWidth.toFloat()/bmp.width)
    val reduced=if(scale<1f)android.graphics.Bitmap.createScaledBitmap(
     bmp,targetWidth,(bmp.height*scale).toInt().coerceAtLeast(1),true)else bmp
    val bytes=java.io.ByteArrayOutputStream()
    reduced.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes)
    if(reduced!==bmp)reduced.recycle()
    bmp.recycle()
    if(epoch!=frameRequestEpoch||bytes.size()>145000)return@Thread
    val src="data:image/png;base64,"+
     android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)
    val detail=JSONObject().put("key",key).put("src",src).toString()
    runOnUiThread{
     if(epoch==frameRequestEpoch && !isFinishing && ::browser.isInitialized)
      browser.evaluateJavascript(
       "window.dispatchEvent(new CustomEvent('img-native-graphics-frame',{detail:"+detail+"}));",null)
    }
   }catch(_:Exception){}finally{
    homeLogo?.recycle();awayLogo?.recycle()
   }
  }.start()
 }

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
  // Layout is explicitly inset by the real system bars (including cutouts)
  // instead of relying on WebView CSS vh, which can extend beneath the
  // status icons on phones running edge-to-edge Android.
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
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
     val localPage=url?.substringBefore('#')?.substringBefore('?')
     if(localPage == "$origin/assets/index.html" || localPage == "$origin/assets/connections.html") {
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
       } else if(action.startsWith("native-sponsor-")){
        receiveSponsorTransfer(action)
       } else if(action=="img-saved-recordings"){
        runOnUiThread{publishNativeRecordings()}
       } else if(action.startsWith("native-open-recording:")){
        val key=action.removePrefix("native-open-recording:")
        if(key.matches(Regex("^[0-9]{10,18}$")))runOnUiThread{openSavedRecording(key)}
       } else if(action=="img-video-capabilities"){
        reportVideoCapabilities()
       } else if(action=="native-graphics-gallery"){
        // Preview generation is on demand only, not during app launch or stream.
        runOnUiThread { generateGraphicsGallery() }
       } else if(action.startsWith("native-graphics-frame:")){
        nativeGraphicFrame(action.removePrefix("native-graphics-frame:"))
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
       } else if (action.startsWith("native-offline-controls:")) {
        // An explicitly tapped offline recording never requests OAuth, a
        // YouTube draft, a relay, or an RTMPS endpoint.
        val payload=action.removePrefix("native-offline-controls:")
        if(payload.length<=100000){
         val game=try{JSONObject(Uri.decode(payload))}catch(_:Exception){JSONObject()}
         fun team(key:String)=game.optString(key).trim().take(18).ifEmpty{
          if(key=="home")"HOME" else "AWAY"
         }
         val sport=if(game.optString("sport")=="pickleball")"pickleball" else "basketball"
         runOnUiThread{
          startActivityForResult(Intent(this@MainActivity,PrivateYoutubeCameraActivity::class.java)
           .putExtra("img_offline_recording",true)
           .putExtra("img_autostart",false)
           .putExtra("img_sport",sport)
           .putExtra("img_video_quality",if(game.optString("quality")=="1080p")1080 else 720)
           .putExtra("img_video_fps",if(game.optInt("fps")==60)60 else 30)
           .putExtra("img_shot_visible",game.optBoolean("shotVisible",true))
           .putExtra("img_record_local",true)
           .putExtra("img_home",team("home"))
           .putExtra("img_away",team("away"))
           .putExtra("img_graphics",game.optString("graphics").take(30))
           .putExtra("img_graphics_size",game.optInt("size",60))
           .putExtra("img_graphics_opacity",game.optInt("opacity",100))
           .putExtra("img_logo_home",game.optString("logoHome").take(35000))
           .putExtra("img_logo_away",game.optString("logoAway").take(35000))
           .putExtra("img_sponsor_token",game.optString("sponsorToken").take(16))
           .putExtra("img_sponsor_images",game.optJSONArray("sponsorImages")?.toString()?.take(60000)?:"")
           .putExtra("img_sponsor_names",game.optJSONArray("sponsorNames")?.let{items->
            (0 until minOf(12,items.length())).joinToString("\n"){i->
             items.optString(i).take(55)
            }
           }?:""),7206)
         }
        }
       } else if (action.startsWith("native-private-video-start:")) {
        val payload=action.removePrefix("native-private-video-start:")
        val draftId=payload.substringBefore(':')
        val encodedGame=payload.substringAfter(':',"")
        if(Regex("^[a-f0-9]{32}$").matches(draftId) &&
           encodedGame.length<=100000&&BuildConfig.OAUTH_BACKEND_URL.isNotBlank()){
         // Safely bounded team names/sport are NON-SECRET game metadata.
         // The Worker still checks private event ownership before RTMPS.
         val game=try{JSONObject(Uri.decode(encodedGame))}catch(_:Exception){JSONObject()}
         fun team(key:String):String=game.optString(key).trim().take(18).ifEmpty{
          if(key=="home")"HOME" else "AWAY"
         }
         val sport=if(game.optString("sport")=="pickleball")"pickleball" else "basketball"
         runOnUiThread {
          startActivityForResult(Intent(this@MainActivity,PrivateYoutubeCameraActivity::class.java)
           .putExtra("draftRequestId",draftId)
           .putExtra("img_autostart",true)
           .putExtra("img_sport",sport)
            .putExtra("img_video_quality",if(game.optString("quality")=="1080p")1080 else 720)
            .putExtra("img_video_fps",if(game.optInt("fps")==60)60 else 30)
            .putExtra("img_shot_visible",game.optBoolean("shotVisible",true))
            .putExtra("img_record_local",game.optBoolean("recordLocal",false))
           .putExtra("img_home",team("home"))
           .putExtra("img_away",team("away"))
           .putExtra("img_graphics",game.optString("graphics").take(30))
           .putExtra("img_graphics_position",game.optString("position").take(32))
           .putExtra("img_graphics_size",game.optInt("size",60))
           .putExtra("img_graphics_opacity",game.optInt("opacity",100))
           .putExtra("img_graphics_width",game.optInt("width",100))
           .putExtra("img_graphics_height",game.optInt("height",100))
           .putExtra("img_graphics_offset_x",game.optInt("offsetX",0))
           .putExtra("img_graphics_offset_y",game.optInt("offsetY",0))
           .putExtra("img_graphics_margin",game.optInt("margin",3))
           .putExtra("img_graphics_animated",game.optBoolean("animated",true))
           .putExtra("img_logo_home",game.optString("logoHome").take(35000))
           .putExtra("img_logo_away",game.optString("logoAway").take(35000))
           .putExtra("img_sponsor_token",game.optString("sponsorToken").take(16))
           .putExtra("img_sponsor_images",game.optJSONArray("sponsorImages")?.toString()?.take(60000)?:"")
           .putExtra("img_sponsor_names",game.optJSONArray("sponsorNames")?.let{items->
            (0 until minOf(12,items.length())).joinToString("\n"){index->
             items.optString(index).take(55)
            }
           }?:""),7206)
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
  root.setBackgroundColor(android.graphics.Color.rgb(16,23,27))
  root.addView(browser, FrameLayout.LayoutParams(-1,-1))
  androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
   val bars=insets.getInsets(
    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
   )
   view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
   insets
  }
  setContentView(root)
  androidx.core.view.ViewCompat.requestApplyInsets(root)
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
  if(requestCode==7206&&resultCode==RESULT_OK&&data?.getBooleanExtra("img_native_stream_ended",false)==true){
   if(::browser.isInitialized)browser.evaluateJavascript(
    "window.dispatchEvent(new Event('img-native-stream-ended'));",null)
   publishNativeRecordings()
   return
  }
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
   // Google OAuth must use a trusted browser surface, NOT an embedded WebView.
   // Chrome Custom Tabs runs as an in-app sheet and retains Google's browser
   // authentication security; once it closes, V2 verifies the pairing.
   try{
    CustomTabsIntent.Builder()
     .setShowTitle(true)
     .setToolbarColor(android.graphics.Color.rgb(16,23,27))
     .build().launchUrl(this,uri)
   }catch(_:Exception){
    startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE))
   }
  } catch (_: Exception) {
   Toast.makeText(this,"Unable to open account authorization",Toast.LENGTH_LONG).show()
  }
 }

 private fun publishNativeRecordings(){
  if(!::browser.isInitialized||isFinishing)return
  val raw=getSharedPreferences("img-v2-native-clips",MODE_PRIVATE).getString("clips","[]")?:"[]"
  val clips=try{JSONArray(raw)}catch(_:Exception){JSONArray()}
  browser.evaluateJavascript(
   "window.dispatchEvent(new CustomEvent('img-native-recordings',{detail:{clips:"+clips.toString()+"}}));",null)
 }
 private fun openSavedRecording(key:String){
  val raw=getSharedPreferences("img-v2-native-clips",MODE_PRIVATE).getString("clips","[]")?:"[]"
  val clips=try{JSONArray(raw)}catch(_:Exception){JSONArray()}
  for(index in 0 until clips.length()){
   val clip=clips.optJSONObject(index)?:continue
   if(clip.optString("id")!=key)continue
   val uri=try{Uri.parse(clip.optString("uri"))}catch(_:Exception){return}
   if(uri.scheme!="content"||uri.authority!="media")return
   val label=clip.optString("home","HOME")+" vs "+clip.optString("away","AWAY")
   savedPlayer.show(uri,label)
   return
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
 override fun onNewIntent(newIntent:Intent) {
  super.onNewIntent(newIntent)
  if(newIntent.data?.scheme=="imglivestudiov2"&&newIntent.data?.host=="oauth-complete"){
   // The deep link transports no account, token, code or pairing secret.
   // Only the original encrypted native cookie can establish connection.
   browser.evaluateJavascript("window.dispatchEvent(new Event('img-native-resume'));",null)
  }
 }
 override fun onResume() {
  super.onResume()
  if(::browser.isInitialized && browser.url?.startsWith("$origin/assets/")==true){
   browser.evaluateJavascript("window.dispatchEvent(new Event('img-native-resume'));",null)
   publishNativeRecordings()
  }
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
