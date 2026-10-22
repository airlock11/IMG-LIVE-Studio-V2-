import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {runInNewContext,Script} from 'node:vm';
import {webcrypto} from 'node:crypto';

const source=await readFile(new URL('./account-connections.js',import.meta.url),'utf8');
function controller(handler) {
 const outbound=[];
 const native={
  onmessage:null,
  postMessage(raw) {
   outbound.push(raw);
   if(raw.startsWith('oauth-open:'))return;
   const cmd=JSON.parse(raw);
   Promise.resolve(handler(cmd)).then(outcome=>{
    queueMicrotask(()=>native.onmessage?.({data:JSON.stringify({
     requestId:cmd.requestId,ok:outcome.ok!==false,
     data:outcome.data||{},error:outcome.error
    })}));
   });
  }
 };
 const window={
  IMGNativeChannel:native,
  dispatchEvent(){},
  addEventListener(){}
 };
 const document={addEventListener(){},hidden:false};
 const context={
  window,document,crypto:webcrypto,URL,CustomEvent:class {constructor(type,options){this.type=type;this.detail=options?.detail;}},
  setTimeout,clearTimeout,
  fetch(){throw Error('WebView fetch must never handle OAuth sessions');}
 };
 runInNewContext(source,context);
 return {accounts:window.IMGAccounts,outbound};
}

test('login remains unavailable until native transport is enabled',async()=>{
 const {accounts}=controller(()=>{throw Error('unexpected native request')});
 await assert.rejects(accounts.connect('facebook'),/not configured/i);
 assert.equal(accounts.getStatus().facebook,'disconnected');
});
test('refresh only trusts native account verification response',async()=>{
 const {accounts,outbound}=controller(cmd=>{
  assert.equal(cmd.action,'refresh');
  return {data:{facebook:{connected:true},youtube:{connected:false}}};
 });
 accounts.enableNative();
 const statuses=await accounts.refresh();
 assert.equal(statuses.facebook,'connected');
 assert.equal(statuses.youtube,'disconnected');
 assert.equal(outbound.length,1);
});
test('a successful browser return cannot mark connected without native refresh',async()=>{
 const pairing='A'.repeat(43);
 const {accounts,outbound}=controller(cmd=>{
  if(cmd.action==='connect')return {data:{pairingCode:pairing,authorizationUrl:'https://www.facebook.com/v24.0/dialog/oauth?client_id=x'}};
  if(cmd.action==='verifyPairing')return {data:{status:'complete'}};
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:false}}};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 await accounts.connect('facebook');
 assert.equal(accounts.getStatus().facebook,'authorization-pending');
 assert.ok(outbound.some(x=>x.startsWith('oauth-open:https://www.facebook.com/')));
 await assert.rejects(accounts.verifyPairing('facebook'),/verification failed/i);
 assert.notEqual(accounts.getStatus().facebook,'connected');
});
test('native-confirmed pairing transitions to connected',async()=>{
 const pairing='B'.repeat(43);
 const {accounts}=controller(cmd=>{
  if(cmd.action==='connect')return {data:{pairingCode:pairing,authorizationUrl:'https://accounts.google.com/o/oauth2/v2/auth?client_id=x'}};
  if(cmd.action==='verifyPairing')return {data:{status:'complete'}};
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:true}}};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 await accounts.connect('youtube');
 assert.equal(await accounts.verifyPairing('youtube'),'complete');
 assert.equal(accounts.getStatus().youtube,'connected');
});
test('verification errors clear previous connected status',async()=>{
 let fail=false;
 const {accounts}=controller(cmd=>{
  if(cmd.action==='refresh'&&fail)return {ok:false,error:'upstream_unavailable'};
  return {data:{facebook:{connected:true},youtube:{connected:false}}};
 });
 accounts.enableNative();
 await accounts.refresh();
 assert.equal(accounts.getStatus().facebook,'connected');
 fail=true;
 await assert.rejects(accounts.refresh(),/upstream_unavailable/);
 assert.equal(accounts.getStatus().facebook,'disconnected');
});

test('disabled staging OAuth reports a clear message without allowing login',async()=>{
 const {accounts}=controller(cmd=>({ok:false,error:'oauth_not_enabled'}));
 accounts.enableNative();
 await assert.rejects(accounts.connect('facebook'),/login is not available yet/i);
 assert.equal(accounts.getStatus().facebook,'disconnected');
});

test('native session sign-out resets all account badges without WebView cookies',async()=>{
 const {accounts,outbound}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:true},youtube:{connected:true}}};
  if(cmd.action==='signOut')return {data:{signedOut:true}};
  throw Error('unexpected native action '+cmd.action);
 });
 accounts.enableNative();
 await accounts.refresh();
 assert.equal(accounts.getStatus().facebook,'connected');
 assert.equal(accounts.getStatus().youtube,'connected');
 assert.equal(await accounts.signOut(),true);
 assert.equal(accounts.getStatus().facebook,'disconnected');
 assert.equal(accounts.getStatus().youtube,'disconnected');
 assert.ok(outbound.some(raw=>raw.includes('"action":"signOut"')));
});
test('failed server logout cannot be represented as a successful sign-out',async()=>{
 const {accounts}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:true},youtube:{connected:false}}};
  if(cmd.action==='signOut')return {ok:false,error:'upstream_failure'};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 await accounts.refresh();
 await assert.rejects(accounts.signOut(),/upstream_failure/);
 assert.equal(accounts.getStatus().facebook,'connected');
});

test('global logout prevents late OAuth authorization responses from reviving a pending session',async()=>{
 let finishAuthorization;
 const held=new Promise(resolve=>{finishAuthorization=resolve});
 const {accounts,outbound}=controller(cmd=>{
  if(cmd.action==='connect')return held;
  if(cmd.action==='signOut')return {data:{signedOut:true}};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 const pendingConnect=accounts.connect('facebook');
 assert.equal(accounts.getStatus().facebook,'connecting');
 await accounts.signOut();
 assert.equal(accounts.getStatus().facebook,'disconnected');
 finishAuthorization({data:{pairingCode:'C'.repeat(43),authorizationUrl:'https://www.facebook.com/v24.0/dialog/oauth?client_id=x'}});
 await assert.rejects(pendingConnect,/session changed/i);
 assert.equal(accounts.getStatus().facebook,'disconnected');
 assert.ok(!outbound.some(x=>x.startsWith('oauth-open:')));
});
test('global logout ignores a stale account status response after the session is revoked',async()=>{
 let finishRefresh;
 const held=new Promise(resolve=>{finishRefresh=resolve});
 const {accounts}=controller(cmd=>{
  if(cmd.action==='refresh')return held;
  if(cmd.action==='signOut')return {data:{signedOut:true}};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 const pendingRefresh=accounts.refresh();
 await accounts.signOut();
 finishRefresh({data:{facebook:{connected:true},youtube:{connected:true}}});
 await pendingRefresh;
 assert.equal(accounts.getStatus().facebook,'disconnected');
 assert.equal(accounts.getStatus().youtube,'disconnected');
});

test('YouTube staging screen script parses and Facebook is disabled',async()=>{
 const html=await readFile(new URL('./connections.html',import.meta.url),'utf8');
 const scriptStart=html.indexOf('<script>');
 const scriptEnd=html.indexOf('</script>',scriptStart);
 assert.ok(scriptStart>0 && scriptEnd>scriptStart,'Expected embedded account page controller');
 assert.doesNotThrow(()=>new Script(html.slice(scriptStart+8,scriptEnd)));
 assert.ok(html.includes('<button id="facebook-button" type="button" disabled>Coming soon</button>'));
 assert.ok(html.includes('<button id="youtube-verify-button" type="button" hidden>Verify</button>'));
 assert.ok(html.includes('img-native-resume'));
 for(const id of ['facebook-button','youtube-button','youtube-verify-button','signout-button']){
  assert.equal(html.split('id="'+id+'"').length-1,1);
 }
});

test('read-only live check runs only after verified YouTube login and returns sanitized information',async()=>{
 const {accounts,outbound}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:true}}};
  if(cmd.action==='youtubeLiveReadiness')return {data:{
   liveApiAccessible:true,liveEnabled:true,reason:'live_api_accessible',
   publishingConfigured:false,
   broadcasts:[{title:'Practice Game',status:'ready',privacy:'private',scheduledStartTime:'2026-10-23T09:00:00Z',hasStream:true}]
  }};
  throw Error('unexpected command '+cmd.action);
 });
 accounts.enableNative();
 await assert.rejects(accounts.checkYoutubeLiveReadiness(),/Connect your YouTube/);
 await accounts.refresh();
 const result=await accounts.checkYoutubeLiveReadiness();
 assert.equal(result.liveApiAccessible,true);
 assert.equal(result.publishingConfigured,false);
 assert.equal(result.broadcasts.length,1);
 assert.equal(result.broadcasts[0].title,'Practice Game');
 assert.ok(outbound.some(raw=>JSON.parse(raw).action==='youtubeLiveReadiness'));
 assert.ok(!outbound.some(raw=>raw.includes('access_token')||raw.includes('streamName')));
});
test('live readiness cannot be used after confirmed native logout',async()=>{
 const {accounts}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:true}}};
  if(cmd.action==='signOut')return {data:{signedOut:true}};
  throw Error('unexpected command '+cmd.action);
 });
 accounts.enableNative();
 await accounts.refresh();
 await accounts.signOut();
 await assert.rejects(accounts.checkYoutubeLiveReadiness(),/Connect your YouTube/);
});
test('read-only live panel is gated by verified YouTube login and does not display a go-live button',async()=>{
 const html=await readFile(new URL('./connections.html',import.meta.url),'utf8');
 assert.ok(html.includes('id="youtube-readiness"'));
 assert.ok(html.includes('id="youtube-readiness-button"'));
 assert.ok(html.includes('checkYoutubeLiveReadiness()'));
 assert.ok(!html.includes('youtube-start-live-button'));
});
