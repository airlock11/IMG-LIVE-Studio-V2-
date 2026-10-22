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
   if(response.ok!==true){item.reject(Error("IMG login service: "+String(response.error||"request failed")));return;}
   item.resolve(response.data);
  };
 }
 function nativeRequest(action,provider,pairingCode) {
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
   try {window.IMGNativeChannel.postMessage(JSON.stringify({type:"oauth-api",requestId,action,provider,pairingCode}));}
   catch(e){clearTimeout(timeout);pending.delete(requestId);reject(e);}
  });
 }
 function failClosed(error) {
  for(const p of Object.keys(providers)) statuses[p]="disconnected";
  publish();
  throw error;
 }
 async function refresh(){
  if(refreshPending)return refreshPending;
  refreshPending=(async()=>{
   let data;
   try {data=await nativeRequest("refresh");}
   catch(e){return failClosed(e);}
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
 async function connect(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  statuses[provider]="connecting";publish();
  try{
   const data=await nativeRequest("connect",provider);
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
  }catch(e){statuses[provider]="disconnected";publish();throw e;}
 }
 async function verifyPairing(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  const code=pairingCodes[provider];
  if(!code)return "not_started";
  const result=await nativeRequest("verifyPairing",provider,code);
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
  const result=await nativeRequest("cancelPending",provider,code);
  if(result?.cancelled!==true)throw Error("Could not cancel server authorization");
  delete pairingCodes[provider];statuses[provider]="disconnected";publish();
  return true;
 }
 async function disconnect(provider){
  if(!hasProvider(provider))throw Error("Unsupported platform");
  const result=await nativeRequest("disconnect",provider);
  if(result?.disconnected!==true||result.provider!==provider)
   throw Error("Unverified disconnect");
  delete pairingCodes[provider];statuses[provider]="disconnected";publish();
  return true;
 }
 document.addEventListener("visibilitychange",()=>{
  if(!document.hidden&&ready)refresh().catch(()=>{});
 });
 window.addEventListener("pageshow",()=>{if(ready)refresh().catch(()=>{});});
 window.IMGAccounts=Object.freeze({
  enableNative,refresh,connect,disconnect,verifyPairing,cancelPending,
  getStatus:()=>({...statuses}),
  subscribe(fn){listeners.add(fn);fn({...statuses});return()=>listeners.delete(fn);}
 });
})();
