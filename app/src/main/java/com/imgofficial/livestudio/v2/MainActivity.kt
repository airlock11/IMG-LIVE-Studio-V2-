package com.imgofficial.livestudio.v2

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/** Hybrid shell: offline packaged prototype UI, explicit native command boundary. */
class MainActivity : Activity() {
 private lateinit var browser: WebView
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  browser = WebView(this).apply {
   settings.javaScriptEnabled = true
   settings.domStorageEnabled = true
   settings.allowFileAccess = true
   settings.allowContentAccess = false
   settings.allowFileAccessFromFileURLs = false
   settings.allowUniversalAccessFromFileURLs = false
   webChromeClient = WebChromeClient()
   webViewClient = WebViewClient()
   addJavascriptInterface(NativeBridge(), "IMGNative")
   loadUrl("file:///android_asset/index.html")
  }
  setContentView(browser)
 }
 private inner class NativeBridge {
  @JavascriptInterface fun command(action: String) {
   runOnUiThread {
    when(action) {
     "camera","microphone","broadcast","record","replay" ->
      Toast.makeText(this@MainActivity,
       "$action: native broadcast engine not yet connected",Toast.LENGTH_SHORT).show()
     else -> Toast.makeText(this@MainActivity,"Unsupported command",Toast.LENGTH_SHORT).show()
    }
   }
  }
 }
 @Deprecated("Deprecated in Java")
 override fun onBackPressed() {
  if(browser.canGoBack()) browser.goBack() else super.onBackPressed()
 }
 override fun onDestroy() {
  browser.removeJavascriptInterface("IMGNative")
  browser.destroy()
  super.onDestroy()
 }
}
