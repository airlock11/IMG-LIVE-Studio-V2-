// IMG LIVE Studio V2 OAuth Worker.
// OAuth state, pairing, sessions and provider records are stored in D1.
// Conditional UPDATE statements atomically claim OAuth state and pairing.
// Tokens are encrypted at rest with TOKEN_ENCRYPTION_KEY (AES-256-GCM).
// OAuth is disabled unless OAUTH_ENABLED="true"; not safe for real users until approved.
const json=(body,status=200,headers={})=>new Response(JSON.stringify(body),{status,headers:{"content-type":"application/json; charset=utf-8","cache-control":"no-store",...headers}});
const providers={
 youtube:{auth:"https://accounts.google.com/o/oauth2/v2/auth",scope:"https://www.googleapis.com/auth/youtube"},
 facebook:{auth:"https://www.facebook.com/v24.0/dialog/oauth",scope:"public_profile"}
};
const bytes=n=>crypto.getRandomValues(new Uint8Array(n));
const b64url=a=>btoa(String.fromCharCode(...a)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
const digest=async s=>b64url(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(s))));
const now=()=>Date.now();
const sessionLife=30*86400*1000;
const pendingLife=600*1000;
const cookie=req=>Object.fromEntries((req.headers.get("cookie")||"").split(";").map(x=>x.trim().split("=")).filter(x=>x.length===2));
const cookieAttrs="HttpOnly; Secure; SameSite=None; Path=/";
const origin=env=>{
 const u=new URL(env.PUBLIC_ORIGIN);
 if(u.protocol!=="https:"||u.pathname!=="/"||u.search||u.hash||u.username||u.password||u.port)throw Error("Invalid PUBLIC_ORIGIN");
 return u.origin;
};
const redirectUri=(env,provider)=>origin(env)+"/api/connections/"+provider+"/callback";
const cors=req=>{
 const requested=req.headers.get("origin");
 return requested==="https://appassets.androidplatform.net"?
 {"access-control-allow-origin":requested,"access-control-allow-credentials":"true","vary":"Origin"}:{};
};
const withCors=(response,req)=>{
 const h=new Headers(response.headers);
 for(const [key,value] of Object.entries(cors(req)))h.set(key,value);
 return new Response(response.body,{status:response.status,headers:h});
};

// Return only allowlisted YouTube Live diagnostics. Raw Google response bodies,
// messages, channels and bearer tokens must never be forwarded to Android.
const liveUnavailable=(req,reason,enabled=null,status=200)=>withCors(json({
 liveApiAccessible:false,liveEnabled:enabled,reason,broadcasts:[],
 publishingConfigured:false,canCreatePrivateDraft:false
},status),req);
const classifyLiveFailure=(status,upstream)=>{
 const reasons=Array.isArray(upstream?.error?.errors)?
  upstream.error.errors.map(x=>x?.reason).filter(x=>typeof x==="string"):[];
 const has=key=>reasons.includes(key);
 if(status===403){
  if(has("liveStreamingNotEnabled"))return ["live_streaming_not_enabled",false];
  if(has("livePermissionBlocked"))return ["live_permission_blocked",false];
  if(has("insufficientLivePermissions")||has("insufficientPermissions"))return ["insufficient_live_permissions",null];
  if(has("quotaExceeded")||has("dailyLimitExceeded"))return ["youtube_api_quota_exceeded",null];
  if(has("rateLimitExceeded")||has("userRequestsExceedRateLimit"))return ["youtube_api_rate_limited",null];
  if(has("accessNotConfigured")||has("accessNotConfiguredInProject"))
   return ["youtube_api_not_enabled",null];
  return ["youtube_api_forbidden",null];
 }
 if(status===400)return ["youtube_api_bad_request",null];
 if(status===404)return ["youtube_api_not_found",null];
 if(status===429)return ["youtube_api_rate_limited",null];
 if(status>=500)return ["youtube_api_temporarily_unavailable",null];
 return ["youtube_api_unexpected_response",null];
};

const appRequest=req=>req.headers.get("origin")==="https://appassets.androidplatform.net";
const isJson=req=>req.headers.get("content-type")?.split(";")[0]?.trim()==="application/json";
const codeValid=s=>typeof s==="string"&&/^[A-Za-z0-9_-]{43}$/.test(s);

const youtubeTestActive=env=>{
 // The testing switch is separately scoped and time-bounded; the full
 // production OAUTH_ENABLED switch stays OFF until Facebook and hardening land.
 const deadline=Date.parse(env.YOUTUBE_TEST_EXPIRES_AT||"");
 return env.YOUTUBE_TEST_ENABLED==="true"&&env.OAUTH_ENABLED!=="true"&&
   Number.isFinite(deadline)&&deadline>now()&&deadline<=now()+48*3600*1000;
};
const authEnabled=(env,provider)=>env.OAUTH_ENABLED==="true"||
 (provider==="youtube"&&youtubeTestActive(env));
// Never permit private draft creation in general production mode.
const privateDraftsEnabled=env=>env.YOUTUBE_DRAFT_ENABLED==="true"&&youtubeTestActive(env);
// Publishing preparation is a separate, default-off switch during private testing.
const privateIngestEnabled=env=>env.YOUTUBE_INGEST_TEST_ENABLED==="true"&&privateDraftsEnabled(env);
const safeRtmps=(base,name)=>{
 if(typeof base!=="string"||typeof name!=="string"||!/^[A-Za-z0-9_-]{8,512}$/.test(name))return null;
 try {
  const url=new URL(base);
  if(url.protocol!=="rtmps:"||url.username||url.password||url.search||url.hash||
     (url.port&&url.port!=="443")||url.pathname!=="/live2"||
     !["a.rtmps.youtube.com","b.rtmps.youtube.com"].includes(url.hostname))return null;
  return (base.endsWith("/")?base.slice(0,-1):base)+"/"+name;
 }catch(_){return null;}
};

const encryptionKey=async env=>{
 const encoded=env.TOKEN_ENCRYPTION_KEY;
 if(!encoded||!/^[A-Za-z0-9_-]{43}$/.test(encoded))throw Error("Invalid token encryption key");
 const raw=Uint8Array.from(atob(encoded.replace(/-/g,"+").replace(/_/g,"/")+"="),c=>c.charCodeAt(0));
 if(raw.length!==32)throw Error("Invalid token encryption key");
 return crypto.subtle.importKey("raw",raw,"AES-GCM",false,["encrypt","decrypt"]);
};
const encryptTokens=async(env,token)=>{
 const iv=bytes(12),key=await encryptionKey(env);
 const ciphertext=new Uint8Array(await crypto.subtle.encrypt({name:"AES-GCM",iv},key,new TextEncoder().encode(JSON.stringify(token))));
 return {version:1,iv:b64url(iv),ciphertext:b64url(ciphertext)};
};
// Access-token expiry is not the IMG session expiry. Unknown token lifetimes
// fail closed rather than falsely implying a provider can still publish.
const tokenLifetime=seconds=>{
 const n=Number(seconds);
 return Number.isFinite(n)&&n>0?Math.min(Math.floor(n),30*86400)*1000:null;
};
const decode64=s=>Uint8Array.from(atob(s.replace(/-/g,"+").replace(/_/g,"/")+"=".repeat((4-s.length%4)%4)),x=>x.charCodeAt(0));
const decryptTokens=async(env,serialized)=>{
 const item=JSON.parse(serialized);
 if(item.version!==1||typeof item.iv!=="string"||typeof item.ciphertext!=="string")
  throw Error("Invalid encrypted token");
 const iv=decode64(item.iv);
 if(iv.length!==12)throw Error("Invalid encryption nonce");
 const raw=await crypto.subtle.decrypt({name:"AES-GCM",iv},await encryptionKey(env),decode64(item.ciphertext));
 return JSON.parse(new TextDecoder().decode(raw));
};
// Conditional D1 update is a short cross-instance refresh lease; failed
// renewals remain disconnected and are throttled until the lease expires.
const refreshYoutube=async(env,session,record)=>{
 const time=now(),lease=time+60000;
 const claimed=await env.OAUTH_DB.prepare("UPDATE oauth_accounts SET refresh_retry_after=? WHERE session_id=? AND provider='youtube' AND encrypted_token=? AND access_expires_at<=? AND refresh_retry_after<=? AND expires_at>? RETURNING session_id")
  .bind(lease,session,record.encrypted_token,time+30000,time,time).first();
 if(!claimed){
  const latest=await env.OAUTH_DB.prepare("SELECT access_expires_at FROM oauth_accounts WHERE session_id=? AND provider='youtube' AND expires_at>?").bind(session,now()).first();
  return !!latest&&latest.access_expires_at>now()+30000;
 }
 try{
  const stored=await decryptTokens(env,record.encrypted_token);
  if(typeof stored.refresh_token!=="string"||!stored.refresh_token||!env.GOOGLE_CLIENT_ID||!env.GOOGLE_CLIENT_SECRET)
   return false;
  const form=new URLSearchParams({client_id:env.GOOGLE_CLIENT_ID,client_secret:env.GOOGLE_CLIENT_SECRET,grant_type:"refresh_token",refresh_token:stored.refresh_token});
  const response=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:form});
  if(!response.ok)return false;
  const updated=await response.json();
  const lifetime=tokenLifetime(updated?.expires_in);
  if(typeof updated?.access_token!=="string"||!updated.access_token.trim()||!lifetime)return false;
  const accessExpiresAt=now()+lifetime;
  const newTokens=JSON.stringify(await encryptTokens(env,{
   access_token:updated.access_token,
   refresh_token:typeof updated.refresh_token==="string"&&updated.refresh_token?updated.refresh_token:stored.refresh_token,
   channel_id:stored.channel_id||null,
   expires_at:accessExpiresAt
  }));
  const saved=await env.OAUTH_DB.prepare("UPDATE oauth_accounts SET encrypted_token=?,access_expires_at=?,refresh_retry_after=0 WHERE session_id=? AND provider='youtube' AND encrypted_token=? AND refresh_retry_after=? AND expires_at>?")
   .bind(newTokens,accessExpiresAt,session,record.encrypted_token,lease,now()).run();
  return saved.meta?.changes===1;
 }catch(_){return false;}
};
const readSession=async(env,req)=>{
 const candidate=cookie(req)["__Host-img_session"];
 if(!codeValid(candidate))return null;
 const row=await env.OAUTH_DB.prepare("SELECT session_id FROM oauth_sessions WHERE session_id=? AND expires_at>?")
  .bind(candidate,now()).first();
 return row?candidate:null;
};
const status=async(env,session)=>{
 const state={facebook:{connected:false},youtube:{connected:false}};
 if(!session)return state;
 const rows=await env.OAUTH_DB.prepare("SELECT a.provider,a.encrypted_token,a.access_expires_at FROM oauth_accounts a JOIN oauth_sessions s ON s.session_id=a.session_id WHERE a.session_id=? AND s.expires_at>? AND a.expires_at>?")
  .bind(session,now(),now()).all();
 for(const account of (rows.results||[])){
  if(!(account.provider in state))continue;
  if(!authEnabled(env,account.provider))continue;
  if(account.access_expires_at>now()+30000){state[account.provider].connected=true;continue;}
  if(account.provider==="youtube")
   state.youtube.connected=await refreshYoutube(env,session,account);
 }
 return state;
};
const pairingStatus=async(env,hash)=>{
 return env.OAUTH_DB.prepare("SELECT session_id,provider,status FROM oauth_pairings WHERE pairing_hash=? AND expires_at>?")
  .bind(hash,now()).first();
};
const declinePair=async(env,hash)=>{
 await env.OAUTH_DB.prepare("UPDATE oauth_pairings SET status='declined' WHERE pairing_hash=? AND status='processing'")
  .bind(hash).run();
};
export default {async fetch(req,env){
 try{
  const url=new URL(req.url),path=url.pathname,method=req.method;
  if(method==="OPTIONS"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   return withCors(new Response(null,{status:204,headers:{"access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type","access-control-max-age":"600"}}),req);
  }
  if(path==="/health"&&method==="GET"){
   let validOrigin=false;
   try{validOrigin=!!origin(env);}catch(_){}
   const validKey=typeof env.TOKEN_ENCRYPTION_KEY==="string"&&/^[A-Za-z0-9_-]{43}$/.test(env.TOKEN_ENCRYPTION_KEY);
   const youtubeReady=!!(env.OAUTH_DB&&validOrigin&&validKey&&env.GOOGLE_CLIENT_ID&&env.GOOGLE_CLIENT_SECRET);
   const fullReady=!!(env.OAUTH_ENABLED==="true"&&youtubeReady&&env.FACEBOOK_APP_ID&&env.FACEBOOK_APP_SECRET);
   const testingReady=!!(youtubeReady&&youtubeTestActive(env));
   const ready=fullReady||testingReady;
   return json({ok:ready,service:"img-oauth",configured:ready,
    mode:fullReady?"full":testingReady?"youtube_testing":"disabled",
    providers:{youtube:ready,facebook:fullReady}
   },ready?200:503);
  }
  if(!path.startsWith("/api/connections"))return json({error:"not_found"},404);
  if(env.OAUTH_ENABLED!=="true"&&!youtubeTestActive(env))
   return json({error:"oauth_not_enabled"},503);
  if(!env.OAUTH_DB||!env.TOKEN_ENCRYPTION_KEY)return json({error:"service_not_configured"},503);
  // In test mode every Facebook operation, including OAuth callbacks, is
  // disabled before any state reads, token exchanges, or database mutation.
  const requestedProvider=path.match(/^\/api\/connections\/(facebook|youtube)(?:\/|$)/)?.[1];
  if(requestedProvider&&!authEnabled(env,requestedProvider))
   return json({error:"provider_not_enabled"},503);
  try{origin(env);}catch(_){return json({error:"invalid_service_origin"},503);}
  const session=await readSession(env,req);
  if(path==="/api/connections"&&method==="GET")return withCors(json(await status(env,session)),req);

  // Read-only YouTube live readiness: the Google access token is decrypted
  // server-side only. Never retrieve CDN ingestionInfo or streamName, and
  // never return tokens, stream keys, or untrusted Google response bodies.
  if(path==="/api/connections/youtube/live-readiness"&&method==="GET"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   const current=await status(env,session);
   if(!current.youtube.connected)return withCors(json({error:"youtube_not_connected"},409),req);
   const record=await env.OAUTH_DB.prepare("SELECT a.encrypted_token,a.access_expires_at FROM oauth_accounts a JOIN oauth_sessions s ON a.session_id=s.session_id WHERE a.session_id=? AND a.provider='youtube' AND a.expires_at>? AND s.expires_at>?")
    .bind(session,now(),now()).first();
   if(!record||record.access_expires_at<=now()+10000)
    return withCors(json({error:"youtube_not_connected"},409),req);
   const token=await decryptTokens(env,record.encrypted_token);
   if(typeof token.access_token!=="string"||!token.access_token||
      typeof token.channel_id!=="string"||!/^[A-Za-z0-9_-]{16,64}$/.test(token.channel_id))
    return withCors(json({error:"youtube_channel_not_verified"},409),req);
   let response;
   try{
    response=await fetch("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=id%2Csnippet%2Cstatus%2CcontentDetails&mine=true&broadcastType=all&maxResults=20",{
     method:"GET",
     headers:{Authorization:"Bearer "+token.access_token,Accept:"application/json"},
     // workerd does not support redirect:"error"; manual denies redirects
     // without risking an automatic Authorization-header forward.
     redirect:"manual"
    });
   }catch(_){return liveUnavailable(req,"youtube_api_network_unavailable");}
   if(response.status>=300&&response.status<400)
    return liveUnavailable(req,"youtube_api_redirect_rejected");
   if(!response.ok){
    if(response.status===401)return withCors(json({error:"youtube_reauthorization_required"},401),req);
    const upstream=await response.json().catch(()=>null);
    const [reason,enabled]=classifyLiveFailure(response.status,upstream);
    return liveUnavailable(req,reason,enabled);
   }
   const live=await response.json().catch(()=>null);
   if(!live||!Array.isArray(live.items))return withCors(json({error:"invalid_youtube_live_response"},502),req);
   // Limit display to currently relevant broadcasts. Ignore arbitrary
   // Google fields including contentDetails ingestion or security metadata.
   const broadcasts=live.items.filter(item=>["created","ready","testing","live"].includes(item?.status?.lifeCycleStatus))
    .slice(0,12).map(item=>({
     id:typeof item.id==="string"?item.id.slice(0,100):"",
     title:typeof item.snippet?.title==="string"?item.snippet.title.slice(0,150):"Untitled broadcast",
     status:item.status.lifeCycleStatus,
     privacy:["public","private","unlisted"].includes(item.status?.privacyStatus)?item.status.privacyStatus:"unknown",
     scheduledStartTime:typeof item.snippet?.scheduledStartTime==="string"?item.snippet.scheduledStartTime.slice(0,40):null,
     hasStream:!!item.contentDetails?.boundStreamId
    }));
   const owned=await env.OAUTH_DB.prepare(
    "SELECT d.request_id,d.title,d.scheduled_start FROM youtube_private_draft_requests d "+
    "LEFT JOIN youtube_private_ingest_attempts i ON i.session_id=d.session_id AND i.draft_request_id=d.request_id "+
    "WHERE d.session_id=? AND d.state='complete' AND i.session_id IS NULL ORDER BY d.created_at DESC LIMIT 3")
    .bind(session).all();
   const privateDrafts=(owned.results||[]).filter(x=>/^[a-f0-9]{32}$/.test(x.request_id)).map(x=>({
    requestId:x.request_id,title:String(x.title).slice(0,100),
    scheduledStartTime:String(x.scheduled_start).slice(0,40)
   }));
   return withCors(json({
    privateDrafts,
    liveApiAccessible:true,
    liveEnabled:true,
    reason:"live_api_accessible",
    broadcasts,
    // Stream creation, RTMP handoff, go-live and transitions are NOT implemented.
    publishingConfigured:false,
    canCreatePrivateDraft:privateDraftsEnabled(env)
   }),req);
  }

  // Explicit, rate-limited private draft creation. No stream/RTMP ingestion
  // credentials are requested or sent to the Android app. Never go live here.
  if(path==="/api/connections/youtube/private-draft"&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return withCors(json({error:"unsupported_media_type"},415),req);
   if(!privateDraftsEnabled(env))return withCors(json({error:"private_drafts_disabled"},503),req);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   if(Number(req.headers.get("content-length")||0)>4096)return withCors(json({error:"request_too_large"},413),req);
   const body=await req.json().catch(()=>null);
   const title=typeof body?.title==="string"?body.title.trim():"";
   const schedule=body?.scheduledStartTime;
   const started=typeof schedule==="string"?Date.parse(schedule):NaN;
   const requestId=body?.requestId;
   if(body?.operatorConfirmed!==true||typeof body?.madeForKids!=="boolean"||
      typeof requestId!=="string"||!/^[a-f0-9]{32}$/.test(requestId)||
      title.length<5||title.length>100||/[\u0000-\u001f\u007f]/.test(title)||
      typeof schedule!=="string"||!/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/.test(schedule)||
      !Number.isFinite(started)||new Date(started).toISOString()!==schedule||
      started<now()+10*60000||started>now()+7*86400000)
    return withCors(json({error:"invalid_private_draft_request"},400),req);
   const inputHash=await digest(JSON.stringify({title,scheduledStartTime:schedule,madeForKids:body.madeForKids}));
   // Atomic idempotency claim plus small per-session quota. An unknown result
   // from YouTube cannot be replayed under the same request ID.
   const claimed=await env.OAUTH_DB.prepare(
    "INSERT INTO youtube_private_draft_requests(session_id,request_id,payload_hash,state,title,scheduled_start,created_at) "+
    "SELECT ?,?,?,'processing',?,?,? WHERE (SELECT COUNT(*) FROM youtube_private_draft_requests WHERE session_id=? AND created_at>?)<3 "+
    "ON CONFLICT(session_id,request_id) DO NOTHING"
   ).bind(session,requestId,inputHash,title,schedule,now(),session,now()-86400000).run();
   if(claimed.meta?.changes!==1){
    const existing=await env.OAUTH_DB.prepare("SELECT payload_hash,state,broadcast_id,title,scheduled_start FROM youtube_private_draft_requests WHERE session_id=? AND request_id=?")
     .bind(session,requestId).first();
    if(!existing)return withCors(json({error:"private_draft_limit_reached"},429),req);
    if(existing.payload_hash!==inputHash)return withCors(json({error:"request_id_conflict"},409),req);
    if(existing.state==="complete"&&existing.broadcast_id)
     return withCors(json({created:true,alreadyCreated:true,privacy:"private",broadcast:{id:existing.broadcast_id,title:existing.title,scheduledStartTime:existing.scheduled_start},publishingConfigured:false}),req);
    return withCors(json({error:"draft_request_"+existing.state},409),req);
   }
   const fail=async(error,status)=>{
    await env.OAUTH_DB.prepare("UPDATE youtube_private_draft_requests SET state='failed' WHERE session_id=? AND request_id=? AND state='processing'")
     .bind(session,requestId).run();
    return withCors(json({error},status),req);
   };
   const connected=await status(env,session);
   if(!connected.youtube.connected)return fail("youtube_not_connected",409);
   const account=await env.OAUTH_DB.prepare("SELECT encrypted_token,access_expires_at FROM oauth_accounts WHERE session_id=? AND provider='youtube' AND expires_at>?")
    .bind(session,now()).first();
   if(!account||account.access_expires_at<now()+30000)return fail("youtube_token_expired",409);
   let token;
   try{token=await decryptTokens(env,account.encrypted_token);}catch(_){return fail("youtube_token_invalid",503);}
   if(typeof token.access_token!=="string"||!token.access_token||
      typeof token.channel_id!=="string"||!/^[A-Za-z0-9_-]{16,64}$/.test(token.channel_id))
    return fail("youtube_channel_not_verified",409);
   let response;
   try{
    response=await fetch("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet%2Cstatus%2CcontentDetails",{
     method:"POST",redirect:"manual",
     headers:{Authorization:"Bearer "+token.access_token,"Content-Type":"application/json",Accept:"application/json"},
     body:JSON.stringify({
      snippet:{title,scheduledStartTime:schedule},
      status:{privacyStatus:"private",selfDeclaredMadeForKids:body.madeForKids},
      contentDetails:{enableAutoStart:false,enableAutoStop:false}
     })
    });
   }catch(_){return fail("youtube_draft_creation_unavailable",502);}
   // Reject 3xx without following, logging, or exposing the Location header.
   if(response.status>=300&&response.status<400)
    return fail("youtube_draft_redirect_rejected",502);
   if(!response.ok){
    if(response.status===401)return fail("youtube_reauthorization_required",401);
    if(response.status===403)return fail("youtube_live_permission_denied",403);
    if(response.status===429)return fail("youtube_rate_limited",429);
    return fail("youtube_draft_creation_failed",502);
   }
   const result=await response.json().catch(()=>null);
   const id=result?.id;
   if(typeof id!=="string"||!/^[A-Za-z0-9_-]{8,100}$/.test(id)||
      result?.status?.privacyStatus!=="private"||
      result?.snippet?.channelId!==token.channel_id)
    return fail("youtube_draft_confirmation_invalid",502);
   const saved=await env.OAUTH_DB.prepare("UPDATE youtube_private_draft_requests SET state='complete',broadcast_id=? WHERE session_id=? AND request_id=? AND state='processing'")
    .bind(id,session,requestId).run();
   if(saved.meta?.changes!==1)return withCors(json({error:"draft_session_revoked"},409),req);
   return withCors(json({created:true,alreadyCreated:false,privacy:"private",broadcast:{id,title,scheduledStartTime:schedule},publishingConfigured:false},201),req);
  }

  // V2: only the Keystore-backed native client consumes this secret-bearing
  // response. The HTML/JS message bridge never exposes the ingest URL.
  // The test flag is OFF by default, separate from OAuth and draft creation.
  if(path==="/api/connections/youtube/private-ingest"&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return withCors(json({error:"unsupported_media_type"},415),req);
   if(!privateIngestEnabled(env))return withCors(json({error:"youtube_private_ingest_disabled"},503),req);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   if(Number(req.headers.get("content-length")||0)>1024)return withCors(json({error:"request_too_large"},413),req);
   const body=await req.json().catch(()=>null);
   const draftId=body?.draftRequestId;
   if(body?.operatorConfirmed!==true||typeof draftId!=="string"||!/^[a-f0-9]{32}$/.test(draftId))
    return withCors(json({error:"invalid_private_ingest_request"},400),req);
   const draft=await env.OAUTH_DB.prepare(
    "SELECT broadcast_id,title FROM youtube_private_draft_requests WHERE session_id=? AND request_id=? AND state='complete'")
    .bind(session,draftId).first();
   if(!draft||typeof draft.broadcast_id!=="string"||!/^[A-Za-z0-9_-]{8,100}$/.test(draft.broadcast_id))
    return withCors(json({error:"private_draft_not_owned"},404),req);
   if(!(await status(env,session)).youtube.connected)
    return withCors(json({error:"youtube_not_connected"},409),req);
   const account=await env.OAUTH_DB.prepare(
    "SELECT encrypted_token,access_expires_at FROM oauth_accounts WHERE session_id=? AND provider='youtube' AND expires_at>?")
    .bind(session,now()).first();
   if(!account||account.access_expires_at<now()+30000)
    return withCors(json({error:"youtube_token_expired"},409),req);
   let token;
   try{token=await decryptTokens(env,account.encrypted_token);}catch(_){
    return withCors(json({error:"youtube_token_invalid"},503),req);
   }
   if(typeof token.access_token!=="string"||!token.access_token||
      typeof token.channel_id!=="string"||!/^[A-Za-z0-9_-]{16,64}$/.test(token.channel_id))
    return withCors(json({error:"youtube_channel_not_verified"},409),req);
   // D1 claim precedes external writes: failed/ambiguous attempts cannot be
   // replayed into accidental duplicate Google streams.
   const claim=await env.OAUTH_DB.prepare(
    "INSERT INTO youtube_private_ingest_attempts(session_id,draft_request_id,broadcast_id,state,created_at) "+
    "VALUES(?,?,?,'processing',?) ON CONFLICT(session_id,draft_request_id) DO NOTHING")
    .bind(session,draftId,draft.broadcast_id,now()).run();
   if(claim.meta?.changes!==1)return withCors(json({error:"private_ingest_already_attempted"},409),req);
   const fail=async(error,http)=>{
    // Store ONLY a fixed, non-secret stage code. Never store Google error
    // bodies, access tokens, RTMPS URLs or streaming keys.
    await env.OAUTH_DB.prepare(
     "UPDATE youtube_private_ingest_attempts SET state='failed',failure_code=? "+
     "WHERE session_id=? AND draft_request_id=? AND state='processing'")
     .bind(error,session,draftId).run();
    return withCors(json({error},http),req);
   };
   const headers={Authorization:"Bearer "+token.access_token,Accept:"application/json"};
   let verify;
   try{
    verify=await fetch("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=id%2Cstatus%2Csnippet%2CcontentDetails&id="+encodeURIComponent(draft.broadcast_id),
     {method:"GET",headers,redirect:"manual"});
   }catch(_){return fail("youtube_private_broadcast_check_unavailable",502);}
   if(!verify.ok)return fail(verify.status>=300&&verify.status<400?"youtube_api_redirect_rejected":"youtube_private_broadcast_check_failed",502);
   const events=(await verify.json().catch(()=>null))?.items;
   const event=Array.isArray(events)&&events.length===1?events[0]:null;
   if(event?.id!==draft.broadcast_id||event?.snippet?.channelId!==token.channel_id||
      event?.status?.privacyStatus!=="private"||!["created","ready"].includes(event?.status?.lifeCycleStatus)||
      event?.contentDetails?.boundStreamId)
    return fail("private_draft_not_ready_for_ingest",409);
   let created;
   try{
    created=await fetch("https://www.googleapis.com/youtube/v3/liveStreams?part=id%2Ccdn%2Csnippet%2CcontentDetails",{
     method:"POST",redirect:"manual",headers:{...headers,"content-type":"application/json"},
     body:JSON.stringify({snippet:{title:"IMG LIVE Studio private test feed"},
      cdn:{frameRate:"30fps",ingestionType:"rtmp",resolution:"720p"},
      contentDetails:{isReusable:false}})
    });
   }catch(_){return fail("youtube_ingest_creation_unavailable",502);}
   if(!created.ok)return fail(created.status>=300&&created.status<400?"youtube_api_redirect_rejected":"youtube_ingest_creation_failed",502);
   const feed=await created.json().catch(()=>null);
   const streamId=feed?.id,info=feed?.cdn?.ingestionInfo;
   const rtmpsUrl=safeRtmps(info?.rtmpsIngestionAddress,info?.streamName);
   if(typeof streamId!=="string"||!/^[A-Za-z0-9_-]{8,100}$/.test(streamId)||!rtmpsUrl)
    return fail("invalid_youtube_ingest_destination",502);
   let bound;
   try{
    bound=await fetch("https://www.googleapis.com/youtube/v3/liveBroadcasts/bind?part=id%2CcontentDetails&id="+
      encodeURIComponent(draft.broadcast_id)+"&streamId="+encodeURIComponent(streamId),
      {method:"POST",redirect:"manual",headers});
   }catch(_){return fail("youtube_stream_binding_unavailable",502);}
   if(!bound.ok)return fail(bound.status>=300&&bound.status<400?"youtube_api_redirect_rejected":"youtube_stream_binding_failed",502);
   const verified=await bound.json().catch(()=>null);
   if(verified?.id!==draft.broadcast_id||verified?.contentDetails?.boundStreamId!==streamId)
    return fail("youtube_stream_binding_unverified",502);
   const saved=await env.OAUTH_DB.prepare(
    "UPDATE youtube_private_ingest_attempts SET state='complete',stream_id=? WHERE session_id=? AND draft_request_id=? AND state='processing'")
    .bind(streamId,session,draftId).run();
   if(saved.meta?.changes!==1)return withCors(json({error:"private_ingest_session_revoked"},409),req);
   return withCors(json({prepared:true,privacy:"private",broadcastTitle:draft.title,
    rtmpsUrl,streamId,autoStart:false}),req);
  }

  // Native-only global logout revokes the D1 session and cascades all linked
  // provider accounts, pending authorizations, and pairing codes atomically.
  if(path==="/api/connections/logout"&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return json({error:"unsupported_media_type"},415);
   if(session)await env.OAUTH_DB.prepare("DELETE FROM oauth_sessions WHERE session_id=?").bind(session).run();
   const headers={"set-cookie":"__Host-img_session=; "+cookieAttrs+"; Max-Age=0"};
   return withCors(json({signedOut:true},200,headers),req);
  }

  const matchPair=path.match(/^\/api\/connections\/(facebook|youtube)\/pairing$/);
  if(matchPair&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return json({error:"unsupported_media_type"},415);
   const body=await req.json().catch(()=>null);
   if(!codeValid(body?.pairingCode))return json({error:"invalid_pairing_code"},400);
   const hash=await digest(body.pairingCode),attempt=await pairingStatus(env,hash);
   if(!attempt)return withCors(json({status:"expired"}),req);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   if(attempt.provider!==matchPair[1]||attempt.session_id!==session)return withCors(json({error:"pairing_session_mismatch"},403),req);
   const state=["complete","cancelled","declined"].includes(attempt.status)?attempt.status:"pending";
   return withCors(json({status:state}),req);
  }

  const matchCancel=path.match(/^\/api\/connections\/(facebook|youtube)\/cancel$/);
  if(matchCancel&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return json({error:"unsupported_media_type"},415);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   const body=await req.json().catch(()=>null);
   if(!codeValid(body?.pairingCode))return json({error:"invalid_pairing_code"},400);
   const hash=await digest(body.pairingCode),provider=matchCancel[1];
   // CAS: cancellation wins only while pending, never during a token exchange.
   const changed=await env.OAUTH_DB.prepare("UPDATE oauth_pairings SET status='cancelled' WHERE pairing_hash=? AND session_id=? AND provider=? AND status='pending' AND expires_at>?")
    .bind(hash,session,provider,now()).run();
   if(changed.meta?.changes===1)return withCors(json({cancelled:true}),req);
   const attempt=await pairingStatus(env,hash);
   if(!attempt)return withCors(json({cancelled:true}),req);
   if(attempt.session_id!==session||attempt.provider!==provider)return withCors(json({error:"pairing_session_mismatch"},403),req);
   if(attempt.status==="cancelled")return withCors(json({cancelled:true}),req);
   return withCors(json({error:"authorization_already_"+attempt.status},409),req);
  }

  const matchDisconnect=path.match(/^\/api\/connections\/(facebook|youtube)\/disconnect$/);
  if(matchDisconnect&&method==="POST"){
   if(!isJson(req))return json({error:"unsupported_media_type"},415);
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!session)return withCors(json({error:"not_authenticated"},401),req);
   await env.OAUTH_DB.prepare("DELETE FROM oauth_accounts WHERE session_id=? AND provider=?")
    .bind(session,matchDisconnect[1]).run();
   return withCors(json({disconnected:true,provider:matchDisconnect[1]}),req);
  }

  const matchStart=path.match(/^\/api\/connections\/(facebook|youtube)\/authorize$/);
  if(matchStart&&method==="POST"){
   if(!appRequest(req))return json({error:"forbidden_origin"},403);
   if(!isJson(req))return withCors(json({error:"unsupported_media_type"},415),req);
   const provider=matchStart[1],id=provider==="youtube"?env.GOOGLE_CLIENT_ID:env.FACEBOOK_APP_ID;
   if(!id)return withCors(json({error:"provider_not_configured"},503),req);
   let current=session;
   if(!current){
    current=b64url(bytes(32));
    await env.OAUTH_DB.prepare("INSERT INTO oauth_sessions(session_id,expires_at) VALUES(?,?)")
     .bind(current,now()+sessionLife).run();
   }
   const pairingCode=b64url(bytes(32)),pairingHash=await digest(pairingCode);
   const state=b64url(bytes(32)),stateHash=await digest(state),verifier=b64url(bytes(32));
   const expires=now()+pendingLife;
   // The two records are created in one atomic DB transaction.
   await env.OAUTH_DB.batch([
    env.OAUTH_DB.prepare("INSERT INTO oauth_pairings(pairing_hash,session_id,provider,status,expires_at) VALUES(?,?,?,'pending',?)")
     .bind(pairingHash,current,provider,expires),
    env.OAUTH_DB.prepare("INSERT INTO oauth_states(state_hash,session_id,provider,verifier,pairing_hash,expires_at) VALUES(?,?,?,?,?,?)")
     .bind(stateHash,current,provider,verifier,pairingHash,expires)
   ]);
   const params=new URLSearchParams({client_id:id,redirect_uri:redirectUri(env,provider),response_type:"code",state,scope:providers[provider].scope});
   if(provider==="youtube"){
    params.set("access_type","offline");
    params.set("code_challenge_method","S256");
    params.set("code_challenge",await digest(verifier));
    params.set("prompt","consent");
   }
   const headers={"set-cookie":"__Host-img_session="+current+"; "+cookieAttrs+"; Max-Age=2592000"};
   return withCors(json({authorizationUrl:providers[provider].auth+"?"+params,pairingCode},200,headers),req);
  }

  const matchCallback=path.match(/^\/api\/connections\/(facebook|youtube)\/callback$/);
  if(matchCallback&&method==="GET"){
   const provider=matchCallback[1],state=url.searchParams.get("state"),authCode=url.searchParams.get("code");
   if(!codeValid(state))return json({error:"invalid_callback"},400);
   // Atomic one-time claim (not KV get/delete): two simultaneous callbacks cannot win.
   const attempt=await env.OAUTH_DB.prepare("UPDATE oauth_states SET used_at=? WHERE state_hash=? AND used_at IS NULL AND expires_at>? RETURNING session_id,provider,verifier,pairing_hash")
    .bind(now(),await digest(state),now()).first();
   if(!attempt)return json({error:"expired_or_replayed_authorization"},400);
   if(attempt.provider!==provider)return json({error:"provider_mismatch"},400);
   // Atomically claim the pairing only if cancellation did not already win.
   const claimed=await env.OAUTH_DB.prepare("UPDATE oauth_pairings SET status='processing' WHERE pairing_hash=? AND session_id=? AND provider=? AND status='pending' AND expires_at>? RETURNING pairing_hash")
    .bind(attempt.pairing_hash,attempt.session_id,provider,now()).first();
   if(!claimed){
    const record=await pairingStatus(env,attempt.pairing_hash);
    return json({error:record?.status==="cancelled"?"authorization_cancelled":"invalid_pairing_status"},400);
   }
   if(url.searchParams.has("error")||!authCode){
    await declinePair(env,attempt.pairing_hash);
    return json({error:url.searchParams.has("error")?"authorization_declined":"missing_authorization_code"},400);
   }
   const alive=await env.OAUTH_DB.prepare("SELECT session_id FROM oauth_sessions WHERE session_id=? AND expires_at>?")
    .bind(attempt.session_id,now()).first();
   if(!alive){await declinePair(env,attempt.pairing_hash);return json({error:"session_expired"},400);}
   const failPairing=async(error,status)=>{
    await declinePair(env,attempt.pairing_hash);
    return json({error},status);
   };
   // Provider client secrets are never sent in URL query parameters.
   let token;
   if(provider==="youtube"){
    if(!env.GOOGLE_CLIENT_SECRET)return failPairing("provider_not_configured",503);
    const form=new URLSearchParams({code:authCode,client_id:env.GOOGLE_CLIENT_ID,client_secret:env.GOOGLE_CLIENT_SECRET,redirect_uri:redirectUri(env,provider),grant_type:"authorization_code",code_verifier:attempt.verifier});
    const result=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:form});
    if(!result.ok)return failPairing("token_exchange_failed",502);
    token=await result.json();
   }else{
    if(!env.FACEBOOK_APP_SECRET)return failPairing("provider_not_configured",503);
    const form=new URLSearchParams({client_id:env.FACEBOOK_APP_ID,client_secret:env.FACEBOOK_APP_SECRET,redirect_uri:redirectUri(env,provider),code:authCode});
    const result=await fetch("https://graph.facebook.com/v24.0/oauth/access_token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:form});
    if(!result.ok)return failPairing("token_exchange_failed",502);
    token=await result.json();
   }
   if(!token||typeof token.access_token!=="string"||!token.access_token.trim())return failPairing("missing_provider_token",502);
   if(provider==="youtube"){
    if(typeof token.token_type!=="string"||token.token_type.toLowerCase()!=="bearer")
     return failPairing("invalid_provider_token_response",502);
    // A successful token exchange alone is not proof that this account has a
    // YouTube channel. Validate directly with the authenticated YouTube API.
    let channelResponse;
    try{
     channelResponse=await fetch("https://www.googleapis.com/youtube/v3/channels?part=id&mine=true",{
      headers:{Authorization:"Bearer "+token.access_token,Accept:"application/json"}
     });
    }catch(_){return failPairing("youtube_channel_verification_failed",502);}
    if(!channelResponse.ok)return failPairing("youtube_channel_verification_failed",502);
    const channelData=await channelResponse.json().catch(()=>null);
    const channelId=channelData?.items?.[0]?.id;
    if(typeof channelId!=="string"||!/^[A-Za-z0-9_-]{16,64}$/.test(channelId))
     return failPairing("youtube_channel_not_found",403);
    token.verified_channel_id=channelId;
   }
   const lifetime=tokenLifetime(token.expires_in);
   if(!lifetime)return failPairing("invalid_provider_token_lifetime",502);
   const accessExpiresAt=now()+lifetime;
   const canRefresh=provider==="youtube"&&typeof token.refresh_token==="string"&&!!token.refresh_token;
   const recordExpiresAt=canRefresh?now()+sessionLife:Math.min(now()+sessionLife,accessExpiresAt);
   const encrypted=JSON.stringify(await encryptTokens(env,{access_token:token.access_token,refresh_token:token.refresh_token||null,channel_id:token.verified_channel_id||null,expires_at:accessExpiresAt}));
   // Store each provider independently: simultaneous Facebook/YouTube callbacks
   // cannot overwrite each other. Complete pairing in the same D1 transaction.
   const completed=await env.OAUTH_DB.batch([
    env.OAUTH_DB.prepare("INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at,refresh_retry_after) SELECT s.session_id,?,?,?,?,0 FROM oauth_sessions s WHERE s.session_id=? AND s.expires_at>? AND EXISTS(SELECT 1 FROM oauth_pairings p WHERE p.pairing_hash=? AND p.status='processing' AND p.expires_at>?) ON CONFLICT(session_id,provider) DO UPDATE SET encrypted_token=excluded.encrypted_token,expires_at=excluded.expires_at,access_expires_at=excluded.access_expires_at,refresh_retry_after=0")
     .bind(provider,encrypted,recordExpiresAt,accessExpiresAt,attempt.session_id,now(),attempt.pairing_hash,now()),
    env.OAUTH_DB.prepare("UPDATE oauth_pairings SET status='complete' WHERE pairing_hash=? AND status='processing' AND expires_at>?")
     .bind(attempt.pairing_hash,now())
   ]);
   if(completed[0]?.meta?.changes!==1||completed[1]?.meta?.changes!==1)
    return json({error:"authorization_expired_during_exchange"},409);
   return new Response("<!doctype html><title>IMG LIVE Studio</title><p>Authorization completed. Return to IMG LIVE Studio to verify your connection.</p>",{headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}});
  }
  return json({error:"not_found"},404);
 }catch(_){return json({error:"server_error"},500);}
 },
 // Daily Cloudflare Cron cleanup prevents expired sessions and obsolete pairing
 // material from living indefinitely in D1. Session deletion cascades provider
 // records and unredeemed OAuth states. No provider API calls are made here.
 async scheduled(controller,env,ctx){
  if(!env.OAUTH_DB)return;
  const cutoff=now();
  const jobs=[
   "DELETE FROM oauth_states WHERE expires_at<=?",
   "DELETE FROM oauth_pairings WHERE expires_at<=?",
   "DELETE FROM oauth_accounts WHERE expires_at<=?",
   "DELETE FROM oauth_sessions WHERE expires_at<=?"
  ].map(sql=>env.OAUTH_DB.prepare(sql).bind(cutoff));
  await env.OAUTH_DB.batch(jobs);
  await env.OAUTH_DB.prepare("DELETE FROM youtube_private_draft_requests WHERE created_at<=?").bind(cutoff-30*86400000).run();
 }
};
