package com.imgofficial.livestudio.v2

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * IMG-owned persistent metadata. The MP4 files themselves live in Android's
 * public Movies collection, never in cache. One game session owns its replays.
 */
object V2RecordingLibrary {
 private const val PREFS="img-v2-native-clips"
 private var serial=0L
 @Synchronized fun nextId():String {
  serial=(serial+1L)%100L
  return (System.currentTimeMillis()*100L+serial).toString()
 }
 private fun array(prefs:android.content.SharedPreferences,key:String):JSONArray =
  try {JSONArray(prefs.getString(key,"[]")?:"[]")}catch(_:Exception){JSONArray()}

 @Synchronized fun addRecording(context:Context,clip:JSONObject) {
  val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val list=array(prefs,"clips")
  val result=JSONArray()
  for(i in 0 until list.length()){
   val old=list.optJSONObject(i)?:continue
   if(old.optString("id")!=clip.optString("id"))result.put(old)
  }
  result.put(clip)
  prefs.edit().putString("clips",result.toString()).commit()
 }
 @Synchronized fun addReplay(context:Context,session:String,uri:Uri,durationMs:Long,sport:String,home:String,away:String):Boolean {
  if(session.isBlank())return false
  val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val replays=array(prefs,"replays")
  val item=JSONObject().put("id",nextId()).put("sessionId",session)
   .put("uri",uri.toString()).put("createdAt",System.currentTimeMillis())
   .put("duration",durationMs.coerceAtLeast(0))
   .put("name","IMG-Replay-${session}-${replays.length()+1}.mp4")
   .put("sport",sport).put("home",home).put("away",away)
  replays.put(item)
  return prefs.edit().putString("replays",replays.toString()).commit()
 }
 @Synchronized fun snapshot(context:Context):JSONObject {
  val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val records=array(prefs,"clips")
  val replay=array(prefs,"replays")
  // A session without a successfully saved full recording still displays if
  // it has an automatically saved replay (no fake playable recording).
  val sessions=mutableSetOf<String>()
  for(i in 0 until records.length()){
   val rec=records.optJSONObject(i)?:continue
   sessions.add(rec.optString("sessionId",rec.optString("id")))
  }
  val seen=mutableSetOf<String>()
  for(i in 0 until replay.length()){
   val clip=replay.optJSONObject(i)?:continue
   val sid=clip.optString("sessionId")
   if(sid.isNotBlank()&&!sessions.contains(sid)&&seen.add(sid)){
    // A session-only card has no video and must not show a Play action.
    records.put(JSONObject().put("id",sid).put("sessionId",sid)
     .put("createdAt",clip.optLong("createdAt")).put("platform","Replay")
     .put("kind","replay_session").put("sport",clip.optString("sport","basketball"))
     .put("home",clip.optString("home","HOME"))
     .put("away",clip.optString("away","AWAY")))
   }
  }
  return JSONObject().put("clips",records).put("replays",replay)
 }
 @Synchronized fun getReplay(context:Context,id:String):JSONObject? {
  val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val arr=array(p,"replays")
  for(i in 0 until arr.length())arr.optJSONObject(i)?.let {
   if(it.optString("id")==id)return JSONObject(it.toString())
  }
  return null
 }
 @Synchronized fun replaysForSession(context:Context,session:String):List<JSONObject> {
  val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val arr=array(p,"replays")
  return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)}
   .filter{it.optString("sessionId")==session}.map{JSONObject(it.toString())}
 }
 @Synchronized fun removeSession(context:Context,session:String):Boolean {
  val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  val clips=array(p,"clips");val replays=array(p,"replays")
  val owned=mutableListOf<String>()
  for(arr in listOf(clips,replays))for(i in 0 until arr.length()){
   val item=arr.optJSONObject(i)?:continue
   if(item.optString("sessionId",item.optString("id"))==session)
    item.optString("uri").takeIf{it.isNotBlank()}?.let{owned.add(it)}
  }
  // Never silently report success while an original file remains undeleted.
  for(raw in owned){
   try{
    val uri=Uri.parse(raw)
    if(uri.scheme!="content"||uri.authority!="media")return false
    context.contentResolver.delete(uri,null,null)
   }catch(_:Exception){return false}
  }
  fun remaining(arr:JSONArray)=JSONArray().apply{
   for(i in 0 until arr.length())arr.optJSONObject(i)?.let{
    if(it.optString("sessionId",it.optString("id"))!=session)put(it)
   }
  }
  return p.edit().putString("clips",remaining(clips).toString())
   .putString("replays",remaining(replays).toString()).commit()
 }
}
