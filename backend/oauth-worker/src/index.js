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
const appRequest=req=>req.headers.get("origin")==="https://appassets.androidplatform.net";
const isJson=req=>req.headers.get("content-type")?.split(";")[0]?.trim()==="application/json";
const codeValid=s=>typeof s==="string"&&/^[A-Za-z0-9_-]{43}$/.test(s);
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
   const ready=!!(env.OAUTH_ENABLED==="true"&&env.OAUTH_DB&&validOrigin&&validKey&&env.GOOGLE_CLIENT_ID&&env.GOOGLE_CLIENT_SECRET&&env.FACEBOOK_APP_ID&&env.FACEBOOK_APP_SECRET);
   return json({ok:ready,service:"img-oauth",configured:ready},ready?200:503);
  }
  if(!path.startsWith("/api/connections"))return json({error:"not_found"},404);
  if(env.OAUTH_ENABLED!=="true")return json({error:"oauth_not_enabled"},503);
  if(!env.OAUTH_DB||!env.TOKEN_ENCRYPTION_KEY)return json({error:"service_not_configured"},503);
  try{origin(env);}catch(_){return json({error:"invalid_service_origin"},503);}
  const session=await readSession(env,req);
  if(path==="/api/connections"&&method==="GET")return withCors(json(await status(env,session)),req);
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
   if(provider==="youtube"&&!token.token_type)return failPairing("invalid_provider_token_response",502);
   const lifetime=tokenLifetime(token.expires_in);
   if(!lifetime)return failPairing("invalid_provider_token_lifetime",502);
   const accessExpiresAt=now()+lifetime;
   const canRefresh=provider==="youtube"&&typeof token.refresh_token==="string"&&!!token.refresh_token;
   const recordExpiresAt=canRefresh?now()+sessionLife:Math.min(now()+sessionLife,accessExpiresAt);
   const encrypted=JSON.stringify(await encryptTokens(env,{access_token:token.access_token,refresh_token:token.refresh_token||null,expires_at:accessExpiresAt}));
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
}};
