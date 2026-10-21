/* IMG LIVE Studio — secure automatic Facebook streaming via hosted relay.
 * The only user input is a Facebook stream key. A deployment-provided public
 * service address is configured in stream-config.js; no relay secret is shipped.
 * This browser is never considered connected until the relay confirms output.
 */
(()=>{'use strict';
let socket=null,capture=null,phase='idle',detail='',stopRequested=false,sendChain=Promise.resolve(),lastSent=0;
let notify=()=>{},abort=null,pendingReject=null;
function update(next,message=''){
 phase=next;detail=String(message||'').slice(0,200);
 try{notify({phase,detail})}catch(_){}
}
function status(){return {phase,detail,lastSent}}
function serviceUrl(){
 const raw=String(window.IMG_STREAMING_CONFIG?.apiBaseUrl||'').trim();
 if(!raw)throw Error('IMG video streaming server is not deployed yet. Facebook cannot connect.');
 let url;try{url=new URL(raw)}catch{throw Error('Streaming service configuration is invalid.')}
 if(url.username||url.password||url.search||url.hash||url.pathname!=='/'||
 (url.protocol!=='https:'&&!(url.protocol==='http:'&&['127.0.0.1','localhost'].includes(url.hostname))))
  throw Error('IMG video streaming service must use HTTPS.');
 return url.href.replace(/\/$/,'');
}
function validate({serverUrl,streamKey}){
 let u;try{u=new URL(String(serverUrl||''))}catch{throw Error('Facebook Server URL is not configured.')}
 if(u.protocol!=='rtmps:'||
  !['live-api-s.facebook.com','live-api.facebook.com','rtmp-api.facebook.com'].includes(u.hostname.toLowerCase())||
  !/^\/rtmp\/?$/.test(u.pathname)||u.username||u.password||u.search||u.hash)
  throw Error('Facebook Server URL is not supported.');
 const key=String(streamKey||'').trim();
 if(key.length<10||key.length>512||/[^\x21-\x7e]/.test(key))
  throw Error('Enter the complete Facebook Stream Key.');
 return {serverUrl:u.href,streamKey:key};
}
function stop(){
 stopRequested=true;
 abort?.abort();abort=null;
 const reject=pendingReject;pendingReject=null;
 if(reject)reject(Error('Facebook connection cancelled.'));
 try{capture?.stop()}catch(_){}
 capture=null;
 if(socket){
  try{if(socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'stop'}))}catch(_){}
  try{socket.close(1000,'Broadcast ended')}catch(_){}
 }
 socket=null;sendChain=Promise.resolve();update('idle','');
}
function fail(error){
 if(stopRequested)return;
 const reject=pendingReject;pendingReject=null;
 stopRequested=true;abort?.abort();abort=null;
 try{capture?.stop()}catch(_){}
 capture=null;
 try{socket?.close(1011,'Streaming interrupted')}catch(_){}
 socket=null;
 update('error',String(error||'Facebook video connection failed.'));
 reject?.(Error(String(error||'Facebook video connection failed.')));
}
async function start(input,readState,mediaOptions={}){
 if(phase==='connecting'||phase==='uploading'||phase==='sending')throw Error('Facebook is already connecting.');
 if(typeof readState!=='function')throw Error('Camera overlay state is unavailable.');
 const key=validate(input),base=serviceUrl();
 stopRequested=false;lastSent=0;sendChain=Promise.resolve();abort=new AbortController();
 update('connecting','Connecting to IMG streaming service…');
 let setup;
 try{
  const resp=await fetch(base+'/v1/facebook/sessions',{
   method:'POST',mode:'cors',credentials:'omit',cache:'no-store',
   headers:{'Content-Type':'application/json'},
   body:JSON.stringify(key),signal:abort.signal
  });
  if(!resp.ok){
   const message=(await resp.json().catch(()=>null))?.error;
   throw Error(typeof message==='string'?message:'Video server unavailable ('+resp.status+').');
  }
  setup=await resp.json();
 }catch(e){
  if(stopRequested)throw Error('Facebook connection cancelled.');
  const message=e?.name==='TypeError'?'Cannot reach IMG streaming server; check deployment and network.':e?.message||'Video session could not be created.';
  fail(message);throw Error(message);
 }finally{abort=null}
 if(stopRequested)throw Error('Facebook connection cancelled.');
 const {wsUrl,ticket}=setup||{};
 let url;
 try{
  url=new URL(wsUrl);
  if(url.username||url.password||url.search||url.hash||url.pathname!=='/live'||
    (url.protocol!=='wss:'&&!(url.protocol==='ws:'&&['localhost','127.0.0.1'].includes(url.hostname)))||
    !ticket||typeof ticket!=='string'||ticket.length<30)throw Error('Invalid session');
 }catch(_){fail('IMG video server returned an invalid session.');throw Error('IMG video server returned an invalid session.')}
 return new Promise((resolve,reject)=>{
  let settled=false,ws,handshakeTimer;
  const rejectPending=e=>{if(settled)return;settled=true;clearTimeout(handshakeTimer);pendingReject=null;reject(e)};
  pendingReject=rejectPending;
  try{ws=new WebSocket(url.href)}catch(e){fail('Unable to open video relay connection.');return}
  socket=ws;ws.binaryType='arraybuffer';
  handshakeTimer=setTimeout(()=>fail('Video connection timed out; check streaming server.'),30000);
  ws.onopen=()=>{if(!stopRequested)ws.send(JSON.stringify({type:'start',ticket,hasAudio:mediaOptions.microphone!==false}))};
  ws.onmessage=async event=>{
   let msg;try{msg=JSON.parse(event.data)}catch{return}
   if(msg.type==='error'){fail(String(msg.message||'The streaming service rejected video.').slice(0,180));return}
   if(msg.type==='ready'&&!settled){
    try{
     update('uploading','Starting camera upload…');
     const prepared=await window.IMGMedia.startLiveCapture(readState,
      {...mediaOptions,onError:e=>fail('Live video encoder stopped: '+String(e?.message||'Unknown error'))},chunk=>{
       sendChain=sendChain.then(async()=>{
        if(stopRequested||socket!==ws||ws.readyState!==WebSocket.OPEN)return;
        const bytes=await chunk.arrayBuffer();
        if(stopRequested||ws.readyState!==WebSocket.OPEN)return;
        if(ws.bufferedAmount>4_000_000){fail('Network upload is too slow.');return}
        ws.send(bytes);lastSent=Date.now();
       }).catch(e=>fail(e?.message||'Live upload stopped.'));
      });
     if(stopRequested||ws.readyState!==WebSocket.OPEN){prepared.stop();return}
     capture=prepared;
    }catch(e){fail(e?.message||'Camera encoding failed.')}
   }
   if(msg.type==='publishing'&&!settled){
    if(!capture){fail('Video server reported output before camera started.');return}
    settled=true;pendingReject=null;clearTimeout(handshakeTimer);
    update('sending','Video upload connected. Check Facebook Live Producer preview before publishing.');
    resolve(true);
   }
  };
  ws.onerror=()=>{if(!settled)fail('Unable to connect to the secure IMG video server.')};
  ws.onclose=()=>{if(!stopRequested)fail('IMG video relay disconnected. Facebook video upload stopped.')};
 });
}
function setListener(fn){notify=typeof fn==='function'?fn:()=>{}}
function available(){try{serviceUrl();return {ready:true}}catch(e){return {ready:false,reason:e.message}}}
window.IMGFacebookLive={start,stop,status,setListener,validate,available};
})();
