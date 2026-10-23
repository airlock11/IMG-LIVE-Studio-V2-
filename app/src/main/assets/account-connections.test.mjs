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
test('V2 checks saved YouTube session without displaying a false sign-out',async()=>{
 const {accounts}=controller(cmd=>{
  assert.equal(cmd.action,'refresh');
  return {data:{facebook:{connected:false},youtube:{connected:true}}};
 });
 assert.equal(accounts.getStatus().youtube,'verifying');
 assert.notEqual(accounts.getStatus().youtube,'connected');
 accounts.enableNative();
 const result=await accounts.refresh();
 assert.equal(result.youtube,'connected');
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
test('verification failures retain session as unverified rather than falsely claiming logout',async()=>{
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
 assert.equal(accounts.getStatus().facebook,'verification-unavailable');
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

test('main V2 broadcast destinations replace standalone readiness and do not fake Android system bars',async()=>{
 const html=await readFile(new URL('./connections.html',import.meta.url),'utf8');
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const css=await readFile(new URL('./app.css',import.meta.url),'utf8');
 const main=await readFile(new URL('../java/com/imgofficial/livestudio/v2/MainActivity.kt',import.meta.url),'utf8');
 assert.doesNotThrow(()=>new Script(studio));
 assert.ok(!html.includes('youtube-readiness-button'));
 assert.ok(!html.includes('youtube-draft-form'));
 assert.ok(studio.includes('function youtubeDestination()'));
 assert.ok(studio.includes('async function startPrivateYouTubeFromStudio()'));
 assert.ok(studio.includes('Broadcast Settings'));
 assert.ok(!studio.includes('9:41'));
 assert.ok(!studio.includes('START DEMO'));
 assert.ok(css.includes('html.img-native-app .app{'));
 assert.ok(main.includes('window.statusBarColor'));
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
test('YouTube readiness is automatic and never exposed as an operator button',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const html=await readFile(new URL('./connections.html',import.meta.url),'utf8');
 assert.ok(studio.includes('await accounts.checkYoutubeLiveReadiness()'));
 assert.ok(studio.includes('await accounts.createYoutubePrivateDraft({'));
 assert.ok(studio.includes('startNow:true'));
 assert.ok(!studio.includes('check.privateDrafts||[]'));
 assert.ok(!html.includes('Check live readiness'));
 assert.ok(!studio.includes('youtube-start-live-button'));
 assert.ok(studio.includes('native-private-video-start:'));
});
test('private YouTube draft requires a verified connected channel and live-readiness gate',async()=>{
 const {accounts,outbound}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:true}}};
  if(cmd.action==='youtubeLiveReadiness')return {data:{liveApiAccessible:true,liveEnabled:true,reason:'live_api_accessible',publishingConfigured:false,canCreatePrivateDraft:true,broadcasts:[]}};
  if(cmd.action==='createYoutubePrivateDraft')return {data:{created:true,alreadyCreated:false,privacy:'private',publishingConfigured:false,broadcast:{title:cmd.title,scheduledStartTime:cmd.scheduledStartTime}}};
  throw Error('unexpected action '+cmd.action);
 });
 accounts.enableNative();
 const details={title:'IMG Private Practice',scheduledStartTime:new Date(Date.now()+3600000).toISOString(),madeForKids:false,draftRequestId:'a'.repeat(32)};
 await assert.rejects(accounts.createYoutubePrivateDraft(details),/Check YouTube Live readiness/);
 await accounts.refresh();
 await assert.rejects(accounts.createYoutubePrivateDraft(details),/Check YouTube Live readiness/);
 const check=await accounts.checkYoutubeLiveReadiness();
 assert.equal(check.canCreatePrivateDraft,true);
 const draft=await accounts.createYoutubePrivateDraft(details);
 assert.equal(draft.privacy,'private');
 assert.equal(draft.publishingConfigured,false);
 const encoded=outbound.filter(s=>s.startsWith('{')).map(s=>JSON.parse(s));
 const command=encoded.find(x=>x.action==='createYoutubePrivateDraft');
 assert.equal(command.operatorConfirmed,true);
 assert.equal(command.madeForKids,false);
 assert.equal(command.draftRequestId,'a'.repeat(32));
 assert.ok(!JSON.stringify(command).includes('access_token'));
 assert.ok(!JSON.stringify(command).includes('streamName'));
});
test('private draft cannot be created after signed-out session even with late readiness',async()=>{
 const {accounts}=controller(cmd=>{
  if(cmd.action==='refresh')return {data:{facebook:{connected:false},youtube:{connected:true}}};
  if(cmd.action==='youtubeLiveReadiness')return {data:{liveApiAccessible:true,liveEnabled:true,reason:'live_api_accessible',publishingConfigured:false,canCreatePrivateDraft:true,broadcasts:[]}};
  if(cmd.action==='signOut')return {data:{signedOut:true}};
  throw Error('unexpected action');
 });
 accounts.enableNative();
 await accounts.refresh();
 await accounts.checkYoutubeLiveReadiness();
 await accounts.signOut();
 await assert.rejects(accounts.createYoutubePrivateDraft({title:'IMG Private Practice',scheduledStartTime:new Date(Date.now()+3600000).toISOString(),madeForKids:false,draftRequestId:'b'.repeat(32)}),/Check YouTube Live readiness/);
});
test('collapsing YouTube settings does not unselect the active destination',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const start=studio.indexOf("if(name==='destination-yt')");
 const end=studio.indexOf("if(name==='youtube-connect')",start);
 assert.ok(start>0&&end>start,'Destination controller missing');
 const handler=studio.slice(start,end);
 assert.ok(handler.includes('if(!o.yt){o.yt=true;o.fb=false;o.tiktok=false;youtubeExpanded=true'));
 assert.ok(handler.includes('else youtubeExpanded=!youtubeExpanded'));
 assert.ok(!handler.includes('o.yt=youtubeExpanded'));
 assert.ok(handler.includes("if(name==='youtube-remove')"));
 assert.ok(studio.includes("window.IMGMedia?.stopCamera?.()"));
 assert.ok(studio.includes("native-private-video-start:"));
});
test('main private YouTube flow requires operator audience choice and stays private',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const native=await readFile(new URL('../java/com/imgofficial/livestudio/v2/MainActivity.kt',import.meta.url),'utf8');
 assert.ok(!studio.slice(studio.indexOf('function setupWizard(){'),studio.indexOf('function updateWizardStickyScoreboard(){')).includes('data-youtube-audience'));
 assert.ok(studio.includes("dialog={type:'youtube-classification'}"));
 assert.ok(studio.includes('youtube-classification-yes'));
 assert.ok(studio.includes('youtube-classification-no'));
 assert.ok(studio.includes('Made for kids?'));
 assert.ok(studio.includes('img-wizard-sticky-preview'));
 assert.ok(studio.includes('updateWizardStickyScoreboard'));
 assert.ok(studio.includes('accounts.createYoutubePrivateDraft'));
 assert.ok(studio.includes("madeForKids:youtubeAudience==='yes'"));
 assert.ok(studio.includes("check.canCreatePrivateDraft"));
 assert.ok(studio.includes("native-private-video-start:"));
 assert.ok(native.includes('Regex("^[a-f0-9]{32}$")'));
 assert.ok(native.includes('img_autostart'));
 assert.ok(!studio.includes('startPublicBroadcast'));
 assert.doesNotThrow(()=>new Script(studio));
});
