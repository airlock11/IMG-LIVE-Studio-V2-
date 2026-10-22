/* IMG LIVE Studio native account controller.
 * No OAuth cookies, access tokens, or backend URLs are present in page JavaScript.
 * Only the trusted, origin-scoped Android WebMessage channel performs API calls.
 */
(function () {
 "use strict";
 const providers = Object.freeze({facebook:"www.facebook.com",youtube:"accounts.google.com"});
 const statuses = {facebook:"disconnected",youtube:"disconnected"};
 const listeners = new Set();
 const pairingCodes = Object.create(null);
 const pending = new Map();
 let ready = false;
 let refreshPending = null;
 let sessionRevision = 0;
 let privateDraftAvailable = false;
 const hasProvider = name => Object.prototype.hasOwnProperty.call(providers,name);
 function publish() {
  const snapshot=Object.freeze({...statuses});
  listeners.forEach(fn=>{try{fn(snapshot);}catch(_){}});
  window.dispatchEvent(new CustomEvent("img-account-status",{detail:snapshot}));
 }
 function enableNative() {
  if (!window.IMGNativeChannel?.postMessage) throw Error("Native Android login is unavailable");
  if (ready) return;
  ready=true;
  window.IMGNativeChannel.onmessage=(event)=>{
   let response;
   try {response=JSON.parse(event.data);}catch(_){return;}
   const item=pending.get(response?.requestId);
   if(!item) return;
   clearTimeout(item.timeout);
   pending.delete(response.requestId);
   if(response.ok!==true){
    const code=String(response.error||"request failed");
    const message=["oauth_not_enabled","service_not_configured","provider_not_configured"].includes(code)
      ?"Facebook and YouTube login is not available yet. IMG is completing secure setup."
      :"IMG login service: "+code;
    item.reject(Error(message));
    return;
   }
   item.resolve(response.data);
  };
 }
 function nativeRequest(action,provider,pairingCode,details) {
  if(!ready||!window.IMGNativeChannel?.postMessage)
   return Promise.reject(Error("Account login is not configured. IMG must deploy its secure Android login service first."));
  const bytes=new Uint8Array(12);
  crypto.getRandomValues(bytes);
  const requestId=Array.from(bytes,b=>b.toString(16).padStart(2,"0")).join("");
  return new Promise((resolve,reject)=>{
   const timeout=setTimeout(()=>{
    pending.delete(requestId);
    reject(Error("Secure account service timed out"));
   },25000);
   pending.set(requestId,{resolve,reject,timeout});
   try {window.IMGNativeChannel.postMessage(JSON.stringify({type:"oauth-api",requestId,action,provider,pairingCode,...(details||{})}));}
   catch(e){clearTimeout(timeout);pending.delete(requestId);reject(e);}
  });
 }
 function failClosed(error) {
  for(const p of Object.keys(providers)) statuses[p]="disconnected";
  privateDraftAvailable=false;
  publish();
  throw error;
 }
 async function refresh(){
  if(refreshPending)return refreshPending;
  const revision=sessionRevision;
  refreshPending=(async()=>{
   let data;
   try {data=await nativeRequest("refresh");}
   catch(e){if(revision===sessionRevision)return failClosed(e);throw e;}
   if(revision!==sessionRevision)return {...statuses};
   if(!data||Object.keys(providers).some(p=>typeof data[p]?.connected!=="boolean"))
    return failClosed(Error("Invalid secure account verification response"));
   for(const p of Object.keys(providers)) {
    statuses[p]=data[p].connected?"connected":
     statuses[p]==="authorization-pending"?"authorization-pending":"disconnected";
   }
   publish();
   return {...statuses};
  })();
  try{return await refreshPending;}finally{refreshPending=null;}
 }
 async function checkYoutubeLiveReadiness(){
  if(statuses.youtube!=="connected")throw Error("Connect your YouTube account first");
  const revision=sessionRevision;
  const data=await nativeRequest("youtubeLiveReadiness");
  if(revision!==sessionRevision||statuses.youtube!=="connected")
   throw Error("Account connection changed during readiness check");
  if(!data||typeof data.liveApiAccessible!=="boolean"||
     !(typeof data.liveEnabled==="boolean"||data.liveEnabled===null)||
     data.publishingConfigured!==false||!Array.isArray(data.broadcasts)||
     data.broadcasts.length>12||!Array.isArray(data.privateDrafts||[])||
   (data.privateDrafts||[]).length>3)
    throw Error("Invalid live readiness response");
  privateDraftAvailable=data.canCreatePrivateDraft===true && data.liveApiAccessible===true;
  return {
   liveApiAccessible:data.liveApiAccessible,
   liveEnabled:data.liveEnabled,
   reason:String(data.reason||""),
   publishingConfigured:false,
   canCreatePrivateDraft:data.canCreatePrivateDraft===true,
   privateDrafts:(data.privateDrafts||[]).filter(item=>/^[a-f0-9]{32}$/.test(item?.requestId)&&
      typeof item?.title==="string").map(item=>({requestId:item.requestId,title:item.title.slice(0,100)})),
   broadcasts:data.broadcasts.map(item=>({
    title:String(item.title||"Untitled broadcast").slice(0,150),
    status:String(item.status||""),
    privacy:String(item.privacy||""),
    scheduledStartTime:String(item.scheduledStartTime||""),
    hasStream:item.hasStream===true
   }))
  };
 }
 // Only available after a successful native/YouTube readiness check.
 // The UI must explicitly confirm the private event; no live stream starts.
 async function createYoutubePrivateDraft({title,scheduledStartTime,madeForKids,draftRequestId}){
  if(statuses.youtube!=="connected"||!privateDraftAvailable)
   throw Error("Check YouTube Live readiness before creating a private draft");
  if(typeof title!=="string"||title.trim().length<5||title.trim().length>100)
   throw Error("Enter a broadcast title between 5 and 100 characters");
  if(typeof scheduledStartTime!=="string"||!/^[0-9]{4}-[0-9]{2}-[0-9]{2}T/.test(scheduledStartTime)||
     !Number.isFinite(Date.parse(scheduledStartTime)))
   throw Error("Select a valid scheduled start");
  if(typeof madeForKids!=="boolean"||typeof draftRequestId!=="string"||
     !/^[a-f0-9]{32}$/.test(draftRequestId))
   throw Error("Invalid private draft request");
  const revision=sessionRevision;
  const result=await nativeRequest("createYoutubePrivateDraft",undefined,undefined,{
   title:title.trim(),scheduledStartTime,madeForKids,draftRequestId,operatorConfirmed:true
  });
  if(revision!==sessionRevision||statuses.youtube!=="connected")
   throw Error("Account changed while creating the private draft");
  if(result?.created!==true||result?.privacy!=="private"||result?.publishingConfigured!==false||
     typeof result.broadcast?.title!=="string")
   throw Error("Private draft was not confirmed by YouTube");
  return {created:true,alreadyCreated:result.alreadyCreated===true,privacy:"private",
   title:result.broadcast.title,scheduledStartTime:result.broadcast.scheduledStartTime,
   publishingConfigured:false};
 }
 async function connect(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  const revision=sessionRevision;
  statuses[provider]="connecting";publish();
  try{
   const data=await nativeRequest("connect",provider);
   if(revision!==sessionRevision)throw Error("Account session changed during authorization");
   if(!data||typeof data.pairingCode!=="string"||!/^[A-Za-z0-9_-]{43}$/.test(data.pairingCode))
    throw Error("Missing secure pairing code");
   const auth=new URL(data.authorizationUrl);
   if(auth.protocol!=="https:"||auth.hostname!==providers[provider]||auth.username||auth.password||
    auth.port||auth.hash||auth.pathname==="/")
    throw Error("Invalid authorization destination");
   pairingCodes[provider]=data.pairingCode;
   window.IMGNativeChannel.postMessage("oauth-open:"+auth.href);
   statuses[provider]="authorization-pending";publish();
   return true;
  }catch(e){if(revision===sessionRevision){statuses[provider]="disconnected";publish();}throw e;}
 }
 async function verifyPairing(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  const code=pairingCodes[provider],revision=sessionRevision;
  if(!code)return "not_started";
  const result=await nativeRequest("verifyPairing",provider,code);
  if(revision!==sessionRevision)return "cancelled";
  if(result?.status==="complete"){
   await refresh();
   if(statuses[provider]!=="connected")throw Error("Login completed, but account verification failed");
   delete pairingCodes[provider];
  }else if(["expired","declined","cancelled"].includes(result?.status)){
   delete pairingCodes[provider];statuses[provider]="disconnected";publish();
  }else if(result?.status!=="pending")throw Error("Invalid pairing status");
  return result.status;
 }
 async function cancelPending(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  if(statuses[provider]!=="authorization-pending")return false;
  const code=pairingCodes[provider];
  if(!code)throw Error("Missing authorization pairing code");
  const revision=sessionRevision;
  const result=await nativeRequest("cancelPending",provider,code);
  if(revision!==sessionRevision)return false;
  if(result?.cancelled!==true)throw Error("Could not cancel server authorization");
  delete pairingCodes[provider];statuses[provider]="disconnected";publish();
  return true;
 }
 // Server revokes the entire native session; Android then erases its
 // Keystore-encrypted local copy before returning success to JavaScript.
 async function signOut(){
  const result=await nativeRequest("signOut");
  if(result?.signedOut!==true)throw Error("Unable to verify secure sign out");
  sessionRevision++;
  privateDraftAvailable=false;
  for(const p of Object.keys(providers)){
   delete pairingCodes[p];
   statuses[p]="disconnected";
  }
  publish();
  return true;
 }
 async function disconnect(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  const revision=sessionRevision;
  const result=await nativeRequest("disconnect",provider);
  if(revision!==sessionRevision)return false;
  if(result?.disconnected!==true||result.provider!==provider)
   throw Error("Unverified disconnect");
  delete pairingCodes[provider];statuses[provider]="disconnected";if(provider==="youtube")privateDraftAvailable=false;publish();
  return true;
 }
 document.addEventListener("visibilitychange",()=>{
  if(!document.hidden&&ready)refresh().catch(()=>{});
 });
 window.addEventListener("pageshow",()=>{if(ready)refresh().catch(()=>{});});
 window.IMGAccounts=Object.freeze({
  enableNative,refresh,connect,disconnect,signOut,verifyPairing,cancelPending,checkYoutubeLiveReadiness,createYoutubePrivateDraft,
  getStatus:()=>({...statuses}),
  subscribe(fn){listeners.add(fn);fn({...statuses});return()=>listeners.delete(fn);}
 });
})();
