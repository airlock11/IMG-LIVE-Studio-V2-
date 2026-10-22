package com.imgofficial.livestudio.v2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-owned OAuth transport. A provider access token or IMG session cookie
 * never crosses the WebView bridge. Network IO runs on one worker thread.
 */
internal class NativeOAuthClient(context: Context, private val baseUrl: String) {
 private val executor = Executors.newSingleThreadExecutor()
 private val prefs = context.getSharedPreferences("img_native_oauth_v1", Context.MODE_PRIVATE)
 private val alias = "img_native_oauth_session_v1"
 private val appOrigin = "https://appassets.androidplatform.net"
 private var sessionCookie: String? = null

 init {
  val u = URL(baseUrl)
  require(u.protocol == "https" && u.port == -1 && u.path in setOf("", "/") &&
   u.query == null && u.ref == null && u.userInfo == null && u.host.isNotBlank()) {
   "Native OAuth requires a pinned HTTPS origin"
  }
 }

 private fun key(): SecretKey {
  val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey(alias, null) as? SecretKey)?.let { return it }
  val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
  generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
   .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
   .setRandomizedEncryptionRequired(true)
   .build())
  return generator.generateKey()
 }

 private fun loadSession(): String? {
  val encoded = prefs.getString("session", null) ?: return null
  return try {
   val bytes = Base64.decode(encoded, Base64.NO_WRAP)
   if (bytes.size < 29) return null
   val cipher = Cipher.getInstance("AES/GCM/NoPadding")
   cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
   val value = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
   if (Regex("^[A-Za-z0-9_-]{43}$").matches(value)) value else null
  } catch (_: Exception) { null }
 }

 private fun saveSession(value: String) {
  val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE, key())
  val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
  prefs.edit().putString("session", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
  sessionCookie = value
 }

 // The cookie stays inside this native client; a successful server logout
 // permanently clears the encrypted device copy as well.
 private fun clearSession() {
  prefs.edit().remove("session").commit()
  sessionCookie = null
 }

 private fun validProvider(provider: String?) = provider == "facebook" || provider == "youtube"

 /**
  * Commands and responses are allowlisted: only UI state, authorization URL and
  * temporary pairing code may return to JavaScript. All session authentication
  * remains within the Android Keystore-protected native client.
  */
 fun request(command: JSONObject, callback: (JSONObject) -> Unit) {
  val requestId = command.optString("requestId").take(72)
  executor.execute {
   val response = try { execute(command) }
   catch (_: Exception) {
    JSONObject().put("ok", false).put("error", "Secure account service unavailable")
   }
   response.put("requestId", requestId)
   callback(response)
  }
 }

 private fun execute(command: JSONObject): JSONObject {
  val action = command.optString("action")
  val provider = command.optString("provider")
  val code = command.optString("pairingCode")
  val route = when (action) {
   "refresh" -> "/api/connections"
   "youtubeLiveReadiness" -> "/api/connections/youtube/live-readiness"
   "createYoutubePrivateDraft" -> "/api/connections/youtube/private-draft"
   "signOut" -> "/api/connections/logout"
   "connect" -> if (validProvider(provider)) "/api/connections/$provider/authorize" else null
   "verifyPairing" -> if (validProvider(provider)) "/api/connections/$provider/pairing" else null
   "cancelPending" -> if (validProvider(provider)) "/api/connections/$provider/cancel" else null
   "disconnect" -> if (validProvider(provider)) "/api/connections/$provider/disconnect" else null
   else -> null
  } ?: return JSONObject().put("ok",false).put("error","Unsupported account action")
  if (action in setOf("verifyPairing","cancelPending") &&
    !Regex("^[A-Za-z0-9_-]{43}$").matches(code))
   return JSONObject().put("ok",false).put("error","Invalid pairing code")
  val url = URL(baseUrl.trimEnd('/') + route)
  val connection = (url.openConnection() as HttpURLConnection)
  try {
   connection.instanceFollowRedirects = false
   connection.connectTimeout = 15000
   connection.readTimeout = 15000
   connection.useCaches = false
   connection.setRequestProperty("Origin", appOrigin)
   connection.setRequestProperty("Accept", "application/json")
   connection.setRequestProperty("Cache-Control", "no-store")
   if (sessionCookie == null) sessionCookie = loadSession()
   sessionCookie?.let { connection.setRequestProperty("Cookie", "__Host-img_session=$it") }
   if (action !in setOf("refresh", "youtubeLiveReadiness")) {
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    val payload = when(action) {
     "verifyPairing","cancelPending" -> JSONObject().put("pairingCode", code).toString()
     "createYoutubePrivateDraft" -> {
      val title=command.optString("title")
      val scheduled=command.optString("scheduledStartTime")
      val startNow=command.opt("startNow") === true
      val requestId=command.optString("draftRequestId")
      val madeForKids=command.opt("madeForKids")
      if(title.length !in 5..100 || (!startNow && scheduled.length !in 20..35) ||
         !Regex("^[a-f0-9]{32}$").matches(requestId) || madeForKids !is Boolean ||
         command.opt("operatorConfirmed") !== true)
       return JSONObject().put("ok",false).put("error","Invalid private draft fields")
      JSONObject().put("title",title).put("startNow",startNow)
       .put("scheduledStartTime",if(startNow)JSONObject.NULL else scheduled)
       .put("requestId",requestId).put("madeForKids",madeForKids)
       .put("operatorConfirmed",true).toString()
     }
     else -> "{}"
    }
    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
   }
   val status = connection.responseCode
   val newCookie = connection.headerFields.entries
    .filter { it.key?.equals("Set-Cookie", ignoreCase=true) == true }
    .flatMap { it.value ?: emptyList() }
    .mapNotNull { Regex("^__Host-img_session=([A-Za-z0-9_-]{43})(?:;|$)").find(it)?.groupValues?.get(1) }
    .firstOrNull()
   if (newCookie != null) saveSession(newCookie)
   val stream = if (status in 200..299) connection.inputStream else connection.errorStream
   val raw = stream?.bufferedReader()?.use { it.readText().take(131072) } ?: "{}"
   val data = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
   if (status !in 200..299) {
    val errorCode = data.optString("error", "Request failed").take(100)
    return JSONObject().put("ok",false).put("error",errorCode).put("status",status)
   }
   val safe = JSONObject()
   when (action) {
    "refresh" -> for (p in arrayOf("facebook", "youtube")) {
     val item = data.optJSONObject(p)
     if (item == null || !item.has("connected") || item.opt("connected") !is Boolean)
      return JSONObject().put("ok",false).put("error","Invalid account status response")
     safe.put(p, JSONObject().put("connected", item.getBoolean("connected")))
    }
    "youtubeLiveReadiness" -> {
     val accessible = data.opt("liveApiAccessible")
     val enabled = data.opt("liveEnabled")
     val publishingConfigured = data.opt("publishingConfigured")
     val reason = data.optString("reason")
     val allowedReasons = setOf(
      "live_api_accessible","live_streaming_not_enabled","live_permission_blocked",
      "insufficient_live_permissions","youtube_api_quota_exceeded","youtube_api_rate_limited",
      "youtube_api_not_enabled","youtube_api_forbidden","youtube_api_bad_request",
      "youtube_api_not_found","youtube_api_temporarily_unavailable",
      "youtube_api_unexpected_response","youtube_api_network_unavailable","youtube_api_redirect_rejected")
     if (accessible !is Boolean ||
        !(enabled is Boolean || enabled == JSONObject.NULL) ||
        publishingConfigured !== false || reason !in allowedReasons)
      return JSONObject().put("ok",false).put("error","Invalid YouTube readiness response")
     safe.put("liveApiAccessible",accessible)
     safe.put("liveEnabled",enabled)
     safe.put("publishingConfigured",false)
     safe.put("reason",reason)
     val broadcasts = data.optJSONArray("broadcasts")
      ?: return JSONObject().put("ok",false).put("error","Invalid broadcast list")
     if (broadcasts.length() > 12)
      return JSONObject().put("ok",false).put("error","Broadcast list too large")
     val safeBroadcasts = org.json.JSONArray()
     val allowedStates = setOf("created","ready","testing","live")
     for (index in 0 until broadcasts.length()) {
      val item = broadcasts.optJSONObject(index)
       ?: return JSONObject().put("ok",false).put("error","Invalid broadcast item")
      val state = item.optString("status")
      if (state !in allowedStates)
       return JSONObject().put("ok",false).put("error","Invalid broadcast state")
      safeBroadcasts.put(JSONObject()
       .put("title",item.optString("title").take(150))
       .put("status",state)
       .put("privacy",item.optString("privacy").take(20))
       .put("scheduledStartTime",item.optString("scheduledStartTime").take(40))
       .put("hasStream",item.opt("hasStream") === true))
     }
     safe.put("broadcasts",safeBroadcasts)
     val draftRecords=data.optJSONArray("privateDrafts")?:org.json.JSONArray()
     if(draftRecords.length()>3)return JSONObject().put("ok",false).put("error","Invalid owned draft list")
     val safeOwned=org.json.JSONArray()
     for(index in 0 until draftRecords.length()){
      val d=draftRecords.optJSONObject(index)?:return JSONObject().put("ok",false).put("error","Invalid owned draft")
      val id=d.optString("requestId")
      val title=d.optString("title")
      if(!Regex("^[a-f0-9]{32}$").matches(id)||title.length !in 5..100)
       return JSONObject().put("ok",false).put("error","Invalid owned draft data")
      safeOwned.put(JSONObject().put("requestId",id).put("title",title)
       .put("scheduledStartTime",d.optString("scheduledStartTime").take(40)))
     }
     safe.put("privateDrafts",safeOwned)
     safe.put("canCreatePrivateDraft",accessible === true && data.opt("canCreatePrivateDraft") === true)
    }
    "createYoutubePrivateDraft" -> {
     if (data.opt("created") !== true || data.optString("privacy") != "private" ||
         data.opt("publishingConfigured") !== false)
      return JSONObject().put("ok",false).put("error","Unverified private YouTube draft")
     val details=data.optJSONObject("broadcast")
      ?: return JSONObject().put("ok",false).put("error","Missing private broadcast")
     val id=details.optString("id")
     val title=details.optString("title")
     val scheduled=details.optString("scheduledStartTime")
     if(!Regex("^[A-Za-z0-9_-]{8,100}$").matches(id) ||
         title.length !in 5..100 || scheduled.length !in 20..35)
      return JSONObject().put("ok",false).put("error","Invalid private broadcast confirmation")
     safe.put("created",true)
     safe.put("alreadyCreated",data.opt("alreadyCreated") === true)
     safe.put("privacy","private")
     safe.put("publishingConfigured",false)
     safe.put("broadcast",JSONObject().put("title",title)
      .put("scheduledStartTime",scheduled))
    }
    "connect" -> {
     val pairingCode = data.optString("pairingCode")
     val auth = data.optString("authorizationUrl")
     if (!Regex("^[A-Za-z0-9_-]{43}$").matches(pairingCode) || auth.length !in 10..8192)
      return JSONObject().put("ok",false).put("error","Invalid authorization response")
     safe.put("pairingCode",pairingCode).put("authorizationUrl",auth)
    }
    "verifyPairing" -> {
     val state = data.optString("status")
     if (state !in setOf("complete", "pending", "expired", "declined", "cancelled"))
      return JSONObject().put("ok",false).put("error","Invalid pairing response")
     safe.put("status",state)
    }
    "cancelPending" -> safe.put("cancelled",data.opt("cancelled") === true)
    "disconnect" -> {
     safe.put("disconnected",data.opt("disconnected") === true)
     safe.put("provider",data.optString("provider"))
    }
    "signOut" -> {
     if (data.opt("signedOut") !== true)
      return JSONObject().put("ok",false).put("error","Unverified session logout")
     clearSession()
     safe.put("signedOut",true)
    }
   }
   return JSONObject().put("ok",true).put("data",safe)
  } finally { connection.disconnect() }
 }


 /**
  * Delivers the private RTMPS destination ONLY to native Android.
  * This method never uses the IMGNativeChannel JS reply and never persists
  * any ingest key, URL, Google access token or IMG session cookie in WebView.
  */
 fun preparePrivateIngest(draftId:String, callback:(String?,String?)->Unit) {
  executor.execute {
   val answer=try { fetchPrivateIngest(draftId) }
   catch (_:Exception) { Pair<String?,String?>(null,"Private video setup unavailable") }
   callback(answer.first,answer.second)
  }
 }

 private fun fetchPrivateIngest(draftId:String):Pair<String?,String?> {
  if(!Regex("^[a-f0-9]{32}$").matches(draftId))return Pair(null,"Invalid private draft")
  val connection=(URL(baseUrl.trimEnd('/')+"/api/connections/youtube/private-ingest")
   .openConnection() as HttpURLConnection)
  try {
   connection.instanceFollowRedirects=false
   connection.connectTimeout=15000
   connection.readTimeout=15000
   connection.useCaches=false
   connection.setRequestProperty("Origin",appOrigin)
   connection.setRequestProperty("Accept","application/json")
   connection.setRequestProperty("Content-Type","application/json")
   connection.setRequestProperty("Cache-Control","no-store")
   if(sessionCookie==null)sessionCookie=loadSession()
   val session=sessionCookie?:return Pair(null,"Connect YouTube in V2 first")
   connection.setRequestProperty("Cookie","__Host-img_session=$session")
   connection.requestMethod="POST"
   connection.doOutput=true
   val payload=JSONObject().put("draftRequestId",draftId).put("operatorConfirmed",true)
    .put("clientProtocolVersion",2)
   connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
   val code=connection.responseCode
   if(code !in 200..299) {
    // A missing Worker route and a missing session-owned draft can both
    // return HTTP 404. Read only a bounded, allowlisted error code; never
    // display arbitrary server text, tokens or streaming destinations.
    val upstream=try {
     val raw=connection.errorStream?.bufferedReader()?.use{it.readText().take(256)}?:"{}"
     JSONObject(raw).optString("error").take(80)
    }catch(_:Exception){""}
    val explanation=when {
     code==404&&upstream=="not_found" ->
      "IMG private video backend is not deployed yet. The YouTube draft is not missing."
     code==404&&upstream=="private_draft_not_owned" ->
      "This private YouTube draft belongs to a different or expired V2 login session."
     code==503&&upstream=="youtube_private_ingest_disabled" ->
      "Private video uploading is disabled on the IMG test server."
     code==401 -> "Your YouTube session needs authorization."
     code==409&&upstream=="private_ingest_already_attempted" ->
      "Private video preparation was already attempted for this draft."
     code==409 -> "This private feed cannot be prepared in its current state."
     code==503 -> "IMG private video service is unavailable."
     else -> "Private YouTube video preparation failed. No video was uploaded."
    }
    return Pair(null,explanation)
   }
   val body=connection.inputStream.bufferedReader().use { it.readText().take(2048) }
   val data=JSONObject(body)
   if(data.opt("prepared")!==true || data.optString("privacy")!="private" ||
      data.opt("autoStart") !is Boolean)return Pair(null,"Unverified private feed response")
   val secret=data.optString("rtmpsUrl")
   if(!isPrivateYouTubeRtmps(secret))return Pair(null,"Unsafe video destination rejected")
   return Pair(secret,null)
  }finally{connection.disconnect()}
 }

 private fun isPrivateYouTubeRtmps(input:String):Boolean=try {
  if(input.length !in 35..700 || input.any { it.isWhitespace() })false
  else {
   val uri=java.net.URI(input)
   uri.scheme?.lowercase()=="rtmps"&&
    uri.host?.lowercase() in setOf("a.rtmps.youtube.com","b.rtmps.youtube.com")&&
    (uri.port==-1||uri.port==443)&&uri.rawUserInfo==null&&
    uri.rawQuery==null&&uri.rawFragment==null&&
    uri.path?.startsWith("/live2/")==true
  }
 }catch(_:Exception){false}

 fun close() { executor.shutdownNow() }
}
