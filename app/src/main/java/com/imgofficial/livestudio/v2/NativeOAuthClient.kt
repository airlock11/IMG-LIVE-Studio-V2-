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
   if (action != "refresh") {
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    val payload = if (action in setOf("verifyPairing","cancelPending"))
     JSONObject().put("pairingCode", code).toString() else "{}"
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

 fun close() { executor.shutdownNow() }
}
