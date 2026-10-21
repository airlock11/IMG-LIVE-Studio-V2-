/* IMG LIVE Studio — secure cloud scoring client.
   Only score/game state is transmitted. Video and microphone stay on broadcaster. */
(()=>{'use strict';
const API='https://img-live-scoring-sync.magsipocarnie.workers.dev';
// This public HTTPS page is opened by the phone's built-in Camera app, including when
// the broadcaster later runs in a native Android application.
const REMOTE_WEB_URL='https://airlock11.github.io/IMG-LIVE-Studio/remote.html';
const STORAGE='img-live-remote-session-v1';
const APPLIED='img-live-remote-applied-v1';
const MAX_OUTBOX=80;
const MAX_BACKOFF=30000;
let session=null,ws=null,connected=false,approved=false,retry=0,retryTimer=0,pingTimer=0,watchdog=0,pingSent=new Map();
let rtt=null,lastPongAt=Date.now(),serverRevision=0,serverState={},onHostAction=()=>false,getHostState=()=>({}),paint=null;
let outbox=[],hostQueue=[],isDraining=false,seen=new Set(),socketEpoch=0,scanner=null;
const rand=()=>Array.from(crypto.getRandomValues(new Uint8Array(12)),b=>b.toString(16).padStart(2,'0')).join('');
const notify=()=>{try{paint?.(status())}catch(e){console.warn('Remote scoring UI:',e)}};
const storage=()=>{try{localStorage.setItem(STORAGE,JSON.stringify({session,outbox}))}catch(e){}};
function load(){
 try{
  const v=JSON.parse(localStorage.getItem(STORAGE)||'null');
  if(v?.session&&Date.now()<Number(v.session.expires)){
   session=v.session;outbox=Array.isArray(v.outbox)?v.outbox.slice(-MAX_OUTBOX):[];
   approved=session.role==='host';
   if(session.role==='host'){
    const stored=JSON.parse(localStorage.getItem(APPLIED)||'null');
    if(stored?.sessionId===session.id&&Array.isArray(stored.ids))seen=new Set(stored.ids.slice(-512));
   }
  }else localStorage.removeItem(STORAGE);
 }catch(e){}
}
function saveSeen(){
 if(session?.role!=='host')return;
 try{localStorage.setItem(APPLIED,JSON.stringify({sessionId:session.id,ids:[...seen].slice(-512)}))}catch(e){}
}
function status(){return {role:session?.role||'none',sessionId:session?.id||'',code:session?.code||'',connected,approved,waiting:session?.role==='scorer'&&!approved,revision:serverRevision,state:serverState,rtt,outbox:outbox.length,expires:session?.expires||0,attempt:retry,pendingId:session?.pendingId||'',lastError:session?.lastError||''};}
async function request(path,method='GET',data=null,token=null){
 const resp=await fetch(API+path,{method,headers:{...(data?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{})},body:data?JSON.stringify(data):undefined,cache:'no-store'});
 let payload={};try{payload=await resp.json()}catch{}
 if(!resp.ok)throw Error(payload.error?.replaceAll('_',' ')||'Remote scoring request failed ('+resp.status+')');
 return payload;
}
function clearTimers(){clearTimeout(retryTimer);clearInterval(pingTimer);clearTimeout(watchdog);retryTimer=0;pingTimer=0;watchdog=0;pingSent.clear();}
function disconnect(){socketEpoch++;clearTimers();if(ws){const old=ws;ws=null;old.onclose=null;old.onerror=null;old.onmessage=null;try{old.close()}catch{}}connected=false;notify();}
function scheduleRetry(){
 if(!session||Date.now()>=session.expires){connected=false;if(session)session.lastError='Session expired. Start or join a new session.';notify();return;}
 const delay=Math.min(MAX_BACKOFF,750*Math.pow(2,Math.min(retry,6)))+Math.floor(Math.random()*400);
 retry++;clearTimeout(retryTimer);retryTimer=setTimeout(connect,delay);notify();
}
function send(obj){if(ws?.readyState!==WebSocket.OPEN||!connected)return false;try{ws.send(JSON.stringify(obj));return true}catch{return false}}
function flush(){
 if(session?.role==='scorer'&&approved){outbox.forEach(x=>send({type:'score_action',...x}));}
 if(session?.role==='host')drainHost();
}
function beginPings(){
 clearInterval(pingTimer);
 pingTimer=setInterval(()=>{if(!connected)return;if(Date.now()-lastPongAt>18000){try{ws?.close(4000,'Heartbeat timed out')}catch{}return}const id=rand();pingSent.set(id,performance.now());if(pingSent.size>6)pingSent.delete(pingSent.keys().next().value);send({type:'ping',id});},4000);
}
function snapshotForHost(){const s=getHostState();return s&&typeof s==='object'?s:{sport:'basketball'};}
function commitHostState(ackIds=[]){
 if(session?.role!=='host'||!connected)return false;
 const next=serverRevision+1;
 const data={type:'state_commit',revision:next,state:snapshotForHost(),ackIds};
 if(send(data)){serverRevision=next;serverState=data.state;notify();return true}
 return false;
}
async function drainHost(){
 if(isDraining||!connected||session?.role!=='host')return;
 isDraining=true;
 try{
  while(hostQueue.length){
   const command=hostQueue.shift();
   if(!command)continue;
   // A host may restart after applying a command but before its acknowledgment
   // reached Cloudflare. Never count that command twice.
   if(seen.has(command.id)){if(!commitHostState([command.id])){hostQueue.unshift(command);break}continue;}
   let applied=false;
   try{applied=(await onHostAction(command.action))!==false}catch(e){console.error('Remote action rejected',e)}
   seen.add(command.id);
   if(seen.size>1024)seen=new Set([...seen].slice(-512));
   saveSeen();
   // Even a rejected command must be acknowledged to avoid infinite replay.
   if(!commitHostState([command.id])){hostQueue.unshift(command);break;}
  }
 }finally{isDraining=false}
}
function onMessage(event,epoch){
 if(epoch!==socketEpoch)return;
 let msg;try{msg=JSON.parse(event.data)}catch{return}
 if(msg.type==='snapshot'){
  connected=true;retry=0;lastPongAt=Date.now();session.lastError='';
  serverRevision=Math.max(0,Number(msg.revision)||0);
  serverState=msg.state||{};
  approved=msg.role==='host'||msg.role==='scorer';
  if(session.role==='scorer'&&msg.role==='pending')approved=false;
  if(session.role==='host'){
   if(msg.pendingId)session.pendingId=msg.pendingId;
   hostQueue.push(...(msg.pendingCommands||[]));
   // Always send latest broadcaster state after reconnect.
   commitHostState();
  }
  storage();flush();notify();return;
 }
 if(msg.type==='pong'){
  const started=pingSent.get(msg.id);
  if(started!=null){lastPongAt=Date.now();rtt=Math.round(performance.now()-started);pingSent.delete(msg.id);notify()}
  return;
 }
 if(msg.type==='pair_request'&&session?.role==='host'){
  session.pendingId=msg.pendingId;storage();notify();return;
 }
 if(msg.type==='pair_approved'){
  if(session?.role==='scorer'){approved=true;disconnect();connect();}
  else notify();
  return;
 }
 if(msg.type==='scorer_revoked'){
  if(session?.role==='scorer'){approved=false;session.lastError='Access revoked by broadcaster';disconnect();storage();}
  else notify();
  return;
 }
 if(msg.type==='score_action'&&session?.role==='host'){
  hostQueue.push(msg);drainHost();return;
 }
 if(msg.type==='state'){
  serverRevision=Math.max(serverRevision,Number(msg.revision)||0);
  serverState=msg.state||serverState;
  if(Array.isArray(msg.ackIds)){const done=new Set(msg.ackIds);outbox=outbox.filter(x=>!done.has(x.id));storage()}
  notify();return;
 }
 if(msg.type==='action_accepted'){
  if(msg.committed){outbox=outbox.filter(x=>x.id!==msg.id);storage();notify()}return;
 }
 if(msg.type==='error'){
  if(session)session.lastError=String(msg.error||'Server error');
  if(msg.error==='revision_conflict'&&session?.role==='host'){
   send({type:'sync'});
  }
  notify();
 }
}
function connect(){
 if(!session||Date.now()>=session.expires)return;
 clearTimeout(retryTimer);
 const epoch=++socketEpoch,token=session.token;
 if(ws&&ws.readyState<2)try{ws.close()}catch{}
 const socket=new WebSocket(API.replace(/^https:/,'wss:')+'/v1/sessions/'+session.id+'/ws');
 ws=socket;connected=false;notify();
 socket.onopen=()=>{if(epoch!==socketEpoch)return;lastPongAt=Date.now();socket.send(JSON.stringify({type:'auth',token}));beginPings();};
 socket.onmessage=e=>onMessage(e,epoch);
 socket.onerror=()=>{if(epoch===socketEpoch)session.lastError='Connection interrupted'};
 socket.onclose=()=>{if(epoch!==socketEpoch)return;ws=null;connected=false;clearInterval(pingTimer);notify();scheduleRetry()};
}
async function startHost(sport='basketball'){
 stop();const v=await request('/v1/sessions','POST',{sport});
 session={id:v.sessionId,token:v.hostToken,code:v.joinCode,role:'host',expires:Date.parse(v.expiresAt),pendingId:'',lastError:''};outbox=[];approved=true;storage();connect();notify();return status();
}
async function join(code){
 const clean=String(code||'').trim().toUpperCase().replace(/[\s-]/g,'');
 if(!/^[A-HJ-NP-Z2-9]{10}$/.test(clean))throw Error('Enter the 10-character pairing code');
 stop();
 const v=await request('/v1/join','POST',{code:clean});
 session={id:v.sessionId,token:v.scorerToken,code:clean,role:'scorer',expires:Date.parse(v.expiresAt),pendingId:v.pendingId,lastError:''};outbox=[];approved=false;storage();connect();notify();return status();
}
async function approve(){
 if(session?.role!=='host'||!session.pendingId)throw Error('No scorer is waiting for approval');
 const v=await request('/v1/sessions/'+session.id+'/approve','POST',{pendingId:session.pendingId},session.token);
 session.pendingId='';storage();notify();return v;
}
async function revoke(){
 if(session?.role!=='host')return;
 await request('/v1/sessions/'+session.id+'/revoke','POST',{},session.token);
 session.pendingId='';storage();notify();
}
function score(action){
 if(session?.role!=='scorer'||!approved)throw Error('Wait for broadcaster approval before scoring');
 if(action?.kind==='control'&&action.name==='end-live'){
  if(!connected)throw Error('Reconnect before ending the live broadcast.');
  if(!session?.id||!serverState?.live||!serverState?.liveId||action.liveId!==serverState.liveId)
   throw Error('The broadcaster is not in this live game. Reconnect and try again.');
 }
 if(!action||typeof action!=='object')return;
 if(outbox.length>=MAX_OUTBOX)throw Error('Offline scoring queue is full. Reconnect first.');
 if(!serverState?.gameId)throw Error('Waiting for broadcaster to open a new game.');
 const msg={id:rand(),action:{...action,gameId:serverState.gameId}};
 outbox.push(msg);storage();send({type:'score_action',...msg});notify();return msg.id;
}
function stop(){disconnect();session=null;approved=false;retry=0;serverRevision=0;serverState={};outbox=[];seen.clear();hostQueue=[];rtt=null;try{localStorage.removeItem(APPLIED)}catch(e){}storage();notify();}
function restore(){if(session){connect();notify()}}
function configure({applyAction,snapshot,update}){if(typeof applyAction==='function')onHostAction=applyAction;if(typeof snapshot==='function')getHostState=snapshot;if(typeof update==='function')paint=update;notify();}
function renderQr(node,url){
 if(!node||!url||typeof QRCode==='undefined')return false;
 node.replaceChildren();
 new QRCode(node,{text:url,width:176,height:176,colorDark:'#101f1a',colorLight:'#ffffff',correctLevel:QRCode.CorrectLevel.M});
 return true;
}
function pairingUrl(){return session?.role==='host'?REMOTE_WEB_URL+'?code='+encodeURIComponent(session.code):''}
let lastScan=0;
async function scan(node,onCode){
 if(!node)throw Error('Scanner preview is not available');
 if(!('BarcodeDetector' in window))throw Error('QR camera scanning is not supported in this browser. Enter the session code instead.');
 await stopScan();
 const detector=new BarcodeDetector({formats:['qr_code']});
 const stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'}},audio:false});
 scanner={stream,node,detector,active:true};
 node.srcObject=stream;node.playsInline=true;node.muted=true;
 await node.play();
 const run=async()=>{
  const ref=scanner;if(!ref||!ref.active||ref.node!==node)return;
  if(performance.now()-lastScan>=170){
   lastScan=performance.now();
   try{for(const q of await detector.detect(node)){
    const code=String(q.rawValue||'').match(/[?&#]code=([A-HJ-NP-Z2-9]{10})/i)?.[1]||String(q.rawValue||'').trim().match(/^[A-HJ-NP-Z2-9]{10}$/i)?.[0];
    if(code){stopScan();onCode(code);return}
   }}catch{}
  }
  if(scanner?.active)scanner.frame=requestAnimationFrame(run);
 };
 scanner.frame=requestAnimationFrame(run);
}
function stopScan(){
 if(!scanner)return;
 const s=scanner;scanner=null;s.active=false;cancelAnimationFrame(s.frame);
 for(const track of s.stream.getTracks())track.stop();
 if(s.node){s.node.srcObject=null;}
}
load();if(session)restore();
window.addEventListener('online',()=>{if(session&&!connected){clearTimeout(retryTimer);retry=0;connect()}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&session&&!connected){clearTimeout(retryTimer);retry=0;connect()}});
window.IMGRemote={status,startHost,join,approve,revoke,score,stop,restore,configure,commitHostState,pairingUrl,renderQr,scan,stopScan,API};
})();