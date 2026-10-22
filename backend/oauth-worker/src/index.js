// IMG LIVE Studio OAuth Worker foundation.
// Required bindings: OAUTH_STATE (KV), ACCOUNT_SESSIONS (KV), GOOGLE_CLIENT_ID,
// GOOGLE_CLIENT_SECRET, FACEBOOK_APP_ID, FACEBOOK_APP_SECRET, PUBLIC_ORIGIN.
// Never expose OAuth client secrets or provider access tokens to the Android WebView.
const json = (body,status=200,headers={}) => new Response(JSON.stringify(body),{status,headers:{"content-type":"application/json; charset=utf-8","cache-control":"no-store",...headers}});
const providers = {
  youtube:{auth:"https://accounts.google.com/o/oauth2/v2/auth",scope:"https://www.googleapis.com/auth/youtube"},
  facebook:{auth:"https://www.facebook.com/v24.0/dialog/oauth",scope:"public_profile"}
};
const bytes = n => crypto.getRandomValues(new Uint8Array(n));
const b64url = a => btoa(String.fromCharCode(...a)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
const digest = async s => b64url(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(s))));
// AES-GCM envelope for OAuth credentials. TOKEN_ENCRYPTION_KEY is a 32-byte
// random secret encoded as unpadded base64url. Never expose it to clients.
const encryptionKey = async env => {
 const encoded=env.TOKEN_ENCRYPTION_KEY;
 if(!encoded || !/^[A-Za-z0-9_-]{43}$/.test(encoded))throw Error("Token encryption key is not configured");
 const raw=Uint8Array.from(atob(encoded.replace(/-/g,"+").replace(/_/g,"/")+"="),c=>c.charCodeAt(0));
 if(raw.length!==32)throw Error("Invalid token encryption key");
 return crypto.subtle.importKey("raw",raw,"AES-GCM",false,["encrypt","decrypt"]);
};
const encryptTokens=async (env,token)=>{
 const iv=bytes(12),key=await encryptionKey(env);
 const payload=new TextEncoder().encode(JSON.stringify(token));
 const ciphertext=new Uint8Array(await crypto.subtle.encrypt({name:"AES-GCM",iv},key,payload));
 return {version:1,iv:b64url(iv),ciphertext:b64url(ciphertext)};
};
const cookie = req => Object.fromEntries((req.headers.get("cookie")||"").split(";").map(x=>x.trim().split("=")).filter(x=>x.length===2));
const cookieAttrs = "HttpOnly; Secure; SameSite=None; Path=/";
const origin = (env) => {
 const u=new URL(env.PUBLIC_ORIGIN);
 if(u.protocol!=="https:"||u.pathname!=="/"||u.search||u.hash)throw Error("Invalid PUBLIC_ORIGIN");
 return u.origin;
};
const redirectUri=(env,provider)=>origin(env)+"/api/connections/"+provider+"/callback";
const cors=(req,env)=> {
 const requested=req.headers.get("origin");
 // WebViewAssetLoader is a fixed, trusted application origin.
 if(requested!=="https://appassets.androidplatform.net")return {};
 return {"access-control-allow-origin":requested,"access-control-allow-credentials":"true","vary":"Origin"};
};
const withCors=(res,req,env)=>{const headers=new Headers(res.headers);for(const [k,v] of Object.entries(cors(req,env)))headers.set(k,v);return new Response(res.body,{status:res.status,headers})};
export default {async fetch(req,env){
 try {
  const u=new URL(req.url),path=u.pathname,method=req.method;
  if(method==="OPTIONS")return withCors(new Response(null,{status:204,headers:{"access-control-allow-methods":"GET,POST,OPTIONS","access-control-allow-headers":"content-type","access-control-max-age":"600"}}),req,env);
  if(path==="/health"&&method==="GET")return json({ok:true,service:"img-oauth"});
  if(!path.startsWith("/api/connections"))return json({error:"not_found"},404);
  if(!env.OAUTH_STATE||!env.ACCOUNT_SESSIONS||!env.TOKEN_ENCRYPTION_KEY)return json({error:"service_not_configured"},503);
  const cookies=cookie(req);
  // Sessions are created server-side; no client-supplied user identifiers.
  let session=cookies.img_session;
  if(!session||!/^[A-Za-z0-9_-]{43}$/.test(session)||!await env.ACCOUNT_SESSIONS.get("session:"+session))session=null;
  if(path==="/api/connections"&&method==="GET"){
   const record=session?JSON.parse(await env.ACCOUNT_SESSIONS.get("session:"+session)):null;
   return withCors(json({facebook:{connected:!!record?.facebook},youtube:{connected:!!record?.youtube}}),req,env);
  }
  const pairing=path.match(/^\/api\/connections\/(facebook|youtube)\/pairing$/);
  if(pairing&&method==="POST"){
   if(req.headers.get("origin")!=="https://appassets.androidplatform.net")return json({error:"forbidden_origin"},403);
   if(req.headers.get("content-type")?.split(";")[0]?.trim()!=="application/json")return json({error:"unsupported_media_type"},415);
   const body=await req.json().catch(()=>null);
   if(!body||typeof body.pairingCode!=="string"||!/^[-_A-Za-z0-9]{43}$/.test(body.pairingCode))
    return json({error:"invalid_pairing_code"},400);
   const key="pair:"+await digest(body.pairingCode);
   const raw=await env.OAUTH_STATE.get(key);
   if(!raw)return withCors(json({status:"expired"}),req,env);
   const attempt=JSON.parse(raw);
   if(attempt.provider!==pairing[1]||attempt.session!==session)return json({error:"pairing_session_mismatch"},403);
   return withCors(json({status:["complete","cancelled","declined"].includes(attempt.status)?attempt.status:"pending"}),req,env);
  }
  const cancel=path.match(/^\/api\/connections\/(facebook|youtube)\/cancel$/);
  if(cancel&&method==="POST"){
   if(req.headers.get("origin")!=="https://appassets.androidplatform.net")return json({error:"forbidden_origin"},403);
   if(req.headers.get("content-type")?.split(";")[0]?.trim()!=="application/json")return json({error:"unsupported_media_type"},415);
   if(!session)return withCors(json({error:"not_authenticated"},401),req,env);
   const body=await req.json().catch(()=>null);
   if(!body||typeof body.pairingCode!=="string"||!/^[-_A-Za-z0-9]{43}$/.test(body.pairingCode))
    return json({error:"invalid_pairing_code"},400);
   const key="pair:"+await digest(body.pairingCode);
   const raw=await env.OAUTH_STATE.get(key);
   if(!raw)return withCors(json({cancelled:true}),req,env);
   const attempt=JSON.parse(raw);
   if(attempt.session!==session||attempt.provider!==cancel[1])return withCors(json({error:"pairing_session_mismatch"},403),req,env);
   if(attempt.status==="complete")return withCors(json({error:"authorization_already_completed"},409),req,env);
   if(attempt.status==="declined")return withCors(json({error:"authorization_already_declined"},409),req,env);
   if(attempt.status==="cancelled")return withCors(json({cancelled:true}),req,env);
   if(attempt.status!=="pending")return withCors(json({error:"invalid_pairing_status"},409),req,env);
   await env.OAUTH_STATE.put(key,JSON.stringify({...attempt,status:"cancelled"}),{expirationTtl:600});
   return withCors(json({cancelled:true}),req,env);
  }
  const disconnect=path.match(/^\/api\/connections\/(facebook|youtube)\/disconnect$/);
  if(disconnect&&method==="POST"){
   if(req.headers.get("content-type")?.split(";")[0]?.trim()!=="application/json")return json({error:"unsupported_media_type"},415);
   if(req.headers.get("origin")!=="https://appassets.androidplatform.net")
    return json({error:"forbidden_origin"},403);
   if(!session)return withCors(json({error:"not_authenticated"},401),req,env);
   const key="session:"+session;
   const record=JSON.parse(await env.ACCOUNT_SESSIONS.get(key)||"{}");
   delete record[disconnect[1]];
   await env.ACCOUNT_SESSIONS.put(key,JSON.stringify(record),{expirationTtl:86400*30});
   return withCors(json({disconnected:true,provider:disconnect[1]}),req,env);
  }
  const start=path.match(/^\/api\/connections\/(facebook|youtube)\/authorize$/);
  if(start&&method==="POST"){
   if(req.headers.get("origin")!=="https://appassets.androidplatform.net")return json({error:"forbidden_origin"},403);
   if(req.headers.get("content-type")?.split(";")[0]?.trim()!=="application/json")return withCors(json({error:"unsupported_media_type"},415),req,env);
   const provider=start[1];
   const id=provider==="youtube"?env.GOOGLE_CLIENT_ID:env.FACEBOOK_APP_ID;
   if(!id)return withCors(json({error:"provider_not_configured"},503),req,env);
   if(!session){session=b64url(bytes(32));await env.ACCOUNT_SESSIONS.put("session:"+session,JSON.stringify({}),{expirationTtl:86400*30})}
   const pairingCode=b64url(bytes(32));
   await env.OAUTH_STATE.put("pair:"+await digest(pairingCode),JSON.stringify({session,provider,status:"pending"}),{expirationTtl:600});
   const state=b64url(bytes(32)),verifier=b64url(bytes(32));
   await env.OAUTH_STATE.put("state:"+state,JSON.stringify({provider,session,verifier,pairingHash:await digest(pairingCode)}),{expirationTtl:600});
   const p=new URLSearchParams({client_id:id,redirect_uri:redirectUri(env,provider),response_type:"code",state,scope:providers[provider].scope});
   if(provider==="youtube"){p.set("access_type","offline");p.set("code_challenge_method","S256");p.set("code_challenge",await digest(verifier));p.set("prompt","consent")}
   const headers={"set-cookie":"img_session="+session+"; "+cookieAttrs+"; Max-Age=2592000"};
   return withCors(json({authorizationUrl:providers[provider].auth+"?"+p,pairingCode},200,headers),req,env);
  }
  const callback=path.match(/^\/api\/connections\/(facebook|youtube)\/callback$/);
  if(callback&&method==="GET"){
   const provider=callback[1],state=u.searchParams.get("state"),code=u.searchParams.get("code");
   if(!state||!/^[-_A-Za-z0-9]{43}$/.test(state))return json({error:"invalid_callback"},400);
   const key="state:"+state,raw=await env.OAUTH_STATE.get(key);
   if(!raw)return json({error:"expired_or_replayed_authorization"},400);
   await env.OAUTH_STATE.delete(key);
   const attempt=JSON.parse(raw);
   if(attempt.provider!==provider)return json({error:"provider_mismatch"},400);
   if(!attempt.pairingHash)return json({error:"missing_pairing_state"},400);
   const pairingRecord=await env.OAUTH_STATE.get("pair:"+attempt.pairingHash);
   if(!pairingRecord)return json({error:"pairing_expired"},400);
   const pair=JSON.parse(pairingRecord);
   if(pair.session!==attempt.session||pair.provider!==provider)return json({error:"pairing_mismatch"},403);
   if(pair.status==="cancelled")return json({error:"authorization_cancelled"},400);
   if(pair.status!=="pending")return json({error:"invalid_pairing_status"},400);
   if(u.searchParams.has("error")){
    await env.OAUTH_STATE.put("pair:"+attempt.pairingHash,JSON.stringify({...pair,status:"declined"}),{expirationTtl:600});
    return json({error:"authorization_declined"},400);
   }
   if(!code)return json({error:"missing_authorization_code"},400);
   if(!/^[A-Za-z0-9_-]{43}$/.test(attempt.session||""))return json({error:"invalid_session"},400);
   if(!await env.ACCOUNT_SESSIONS.get("session:"+attempt.session))return json({error:"session_expired"},400);
   if(u.searchParams.get("error"))return json({error:"authorization_declined"},400);
   // Exchange codes on the server only. Never return the token to the browser.
   let token;
   if(provider==="youtube"){
    if(!env.GOOGLE_CLIENT_SECRET)return json({error:"provider_not_configured"},503);
    const form=new URLSearchParams({code,client_id:env.GOOGLE_CLIENT_ID,client_secret:env.GOOGLE_CLIENT_SECRET,redirect_uri:redirectUri(env,provider),grant_type:"authorization_code",code_verifier:attempt.verifier});
    const result=await fetch("https://oauth2.googleapis.com/token",{method:"POST",headers:{"content-type":"application/x-www-form-urlencoded"},body:form});
    if(!result.ok)return json({error:"token_exchange_failed"},502);
    token=await result.json();
   } else {
    if(!env.FACEBOOK_APP_SECRET)return json({error:"provider_not_configured"},503);
    const endpoint=new URL("https://graph.facebook.com/v24.0/oauth/access_token");
    endpoint.search=new URLSearchParams({client_id:env.FACEBOOK_APP_ID,client_secret:env.FACEBOOK_APP_SECRET,redirect_uri:redirectUri(env,provider),code}).toString();
    const result=await fetch(endpoint);
    if(!result.ok)return json({error:"token_exchange_failed"},502);
    token=await result.json();
   }
   if(!token.access_token)return json({error:"missing_provider_token"},502);
   if(!token.token_type && provider==="youtube")return json({error:"invalid_provider_token_response"},502);
   // Tokens remain in server-side KV; production should use encryption at rest with managed key.
   const existing=JSON.parse(await env.ACCOUNT_SESSIONS.get("session:"+attempt.session)||"{}");
   existing[provider]=await encryptTokens(env,{access_token:token.access_token,refresh_token:token.refresh_token||null,expires_at:token.expires_in?Date.now()+token.expires_in*1000:null});
   await env.ACCOUNT_SESSIONS.put("session:"+attempt.session,JSON.stringify(existing),{expirationTtl:86400*30});
   if(attempt.pairingHash)await env.OAUTH_STATE.put("pair:"+attempt.pairingHash,JSON.stringify({session:attempt.session,provider,status:"complete"}),{expirationTtl:600});
   return new Response("<!doctype html><title>IMG LIVE Studio</title><p>Account authorization completed. Return to IMG LIVE Studio and refresh connections.</p>",{headers:{"content-type":"text/html; charset=utf-8","cache-control":"no-store"}});
  }
  return json({error:"not_found"},404);
 }catch(e){return json({error:"server_error"},500)}
}};
