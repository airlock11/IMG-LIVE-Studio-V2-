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
 await assert.rejects(accounts.connect('facebook'),/expired or is disabled/i);
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
test('Collapsing YouTube dropdown preserves account and stream settings',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const start=studio.indexOf("if(name==='destination-yt')");
 const end=studio.indexOf("if(name==='youtube-connect')",start);
 assert.ok(start>0&&end>start,'Destination controller missing');
 const handler=studio.slice(start,end);
 assert.ok(handler.includes('youtubeExpanded=!youtubeExpanded'),'Header must only fold connection controls');
 assert.ok(!handler.includes('o.yt=!o.yt'),'Collapsing must not deselect YouTube');
 assert.ok(!handler.includes("accounts.disconnect("),'Collapsing must not revoke OAuth');
 assert.ok(studio.includes("if(name==='youtube-disconnect')"),'Explicit disconnect must remain available');
 assert.ok(studio.includes("window.IMGMedia?.stopCamera?.()"));
 assert.ok(studio.includes("native-private-video-start:"));
});
test('main private YouTube flow requires operator audience choice and stays private',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const native=await readFile(new URL('../java/com/imgofficial/livestudio/v2/MainActivity.kt',import.meta.url),'utf8');
 const wizard=studio.slice(studio.indexOf('function setupWizard(){'),studio.indexOf('function updateWizardStickyScoreboard(){'));
 assert.ok(wizard.includes('data-youtube-audience'),'Step 4 Stream Details must contain audience choices');
 assert.ok(wizard.includes('Not made for kids')&&wizard.includes('Made for kids'));
 assert.ok(studio.includes("if(o.yt&&!youtubeAudience)return toast("),'Audience must be required before Go Live');
 assert.ok(studio.includes("if(!youtubeAudience)throw Error("),'Server draft creation must reject missing audience');
 assert.ok(!studio.includes("dialog={type:'youtube-classification'}"),'No audience popup may interrupt Go Live');
 assert.ok(!studio.includes("if(d.type==='youtube-classification')"),'Remove the obsolete audience modal');
 assert.ok(studio.includes('img-score-pinned'));
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

test('IMG V2 Issues 1–3: scoreboard selection and size do not re-render or jump scroll',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const css=await readFile(new URL('./app.css',import.meta.url),'utf8');
 const setOption=studio.slice(studio.indexOf('function setOption(input)'),studio.indexOf('let orientationRequestId=',studio.indexOf('function setOption(input)')));
 assert.ok(setOption.includes("if(k==='overlay'&&screen==='setup'&&setupStep===1)"),'Artwork selection needs a stable in-place branch');
 assert.ok(setOption.includes("button.classList.toggle('active',active)")&&setOption.includes("paintSetupScoreboard?.()"));
 assert.ok(setOption.indexOf("if(k==='overlay'&&screen==='setup'")<setOption.indexOf("render(true)"),'Artwork selection must not force full-page render');
 const change=studio.slice(studio.indexOf("if(t.dataset.graphicsRange){persist();return}"),studio.indexOf("if(t.dataset.quick){data.options.broadcast[t.dataset.quick]=t.value;persist();return}"));
 assert.ok(change.includes("if(t.dataset.scoreRange||t.dataset.scoreColor){persist();window.IMGMedia?.paintSetupScoreboard?.();return}"));
 assert.ok(!change.includes("render(true)"),'Releasing size slider must not rebuild the page');
 assert.ok(css.includes('.img-wizard-page .img-wizard-scroll{overflow-anchor:none!important;scroll-behavior:auto!important}'));
 assert.ok(css.includes('.img-wizard-page .img-wizard-score-origin{background:transparent!important}'));
 assert.ok(studio.includes("img-score-pinned"),'Pin the original preview, not a second docking canvas');
});
test('IMG V2 Issues 5–6: native controls and clean text-only End Stream confirmation',async()=>{
 const native=await readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8');
 const confirm=native.slice(native.indexOf('private fun confirmStopUpload()'),native.indexOf('private var operatorSheet'));
 assert.ok(native.includes('liveNow->"STOP STREAMING"'));
 assert.ok(native.includes('Color.rgb(181,34,44)'),'Live button should be red');
 assert.ok(native.includes('start.setCompoundDrawablesWithIntrinsicBounds(null,null,null,null)'),'No stop square');
 assert.ok(native.includes('resetClockButton=iconButton(darkButton(""){resetGameClock()},"reset")'));
 assert.ok(native.includes('clockHeader.addView(resetClockButton'));
 assert.ok(native.includes('timeoutButton=iconButton(darkButton("TIMEOUT")'));
 assert.ok(native.includes('showTimeoutPreview(timeoutButton)'));
 assert.ok(native.includes('CLOCKS PAUSED')&&native.includes('timeoutButton.text=if(timeoutActive)"RESUME"'),'Timeout pauses clocks and converts action into Resume');
 assert.ok(native.includes('broadcast?.setSponsorSlideshow(timeoutSlideshowEnabled)'),'Only opt-in timeout slideshow pushes sponsor art into encoded video');
 assert.ok(confirm.includes('customSheet("END STREAM?")'));
 assert.ok(confirm.includes('dangerButton("END STREAM")'));
 assert.ok(confirm.includes('greenButton("KEEP STREAMING")'));
 assert.ok(!confirm.includes('iconButton(')&&!confirm.includes('END UPLOAD')&&!confirm.includes('PRIVATE'));
});

test('phone issues: one scoreboard canvas, no lineup, no animation switch, compact tools and main shot switch',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const native=await readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8');
 const renderer=await readFile(new URL('../java/com/imgofficial/livestudio/v2/V2BroadcastGraphicsRenderer.kt',import.meta.url),'utf8');
 const wizard=studio.slice(studio.indexOf('function setupWizard(){'),studio.indexOf('function updateWizardStickyScoreboard(){'));
 const settings=native.slice(native.indexOf('private fun openOperatorSettings()'),native.indexOf('private fun unavailableFeature('));
 assert.ok(!wizard.includes('Lineup setup (optional)'));
 assert.ok(!wizard.includes('graphics-switch-row'));
 assert.ok(!wizard.includes('Score animation'));
 assert.ok(!wizard.includes('img-wizard-sticky-preview'),'No duplicate sticky preview container');
 assert.equal((wizard.match(/data-setup-scoreboard/g)||[]).length,1);
 assert.ok(studio.includes("preview.classList.add('img-score-pinned')"));
 assert.ok(studio.includes("preview.classList.remove('img-score-pinned')"));
 assert.ok(!studio.includes("dock.appendChild(preview)"));
 assert.ok(!studio.includes("Save or update team for next matches"));
 assert.ok(studio.includes("if(!scoreboardSizeFrame)scoreboardSizeFrame=requestAnimationFrame"));
 assert.ok(studio.includes("marker.style.display='none'"));
 assert.ok(native.includes('android.widget.Switch(this).apply')&&native.includes('setShotClockVisible(checked)'));
 assert.ok(native.includes('shotClockDisplay=ink('));
 assert.ok(native.includes('intent.getBooleanExtra("img_autostart",false)'));
 assert.ok(native.includes('maybeStartPrivateUpload()'));
 assert.ok(settings.includes('SCOREBOARD SIZE'));
 for(const old of ['heading("Graphics")','styleButtons','Score animation','Undo score','Game clock','Shot clock','heading("Fouls")'])
  assert.ok(!settings.includes(old),'Old tools panel content still present: '+old);
 assert.ok(renderer.includes('shotVisible'),'The actual native artwork should respect visibility');
});

test('phone recheck: expired OAuth is never presented as both platforms permanently unavailable',async()=>{
 const accounts=await readFile(new URL('./account-connections.js',import.meta.url),'utf8');
 assert.ok(!accounts.includes('Facebook and YouTube login is not available yet.'));
 assert.ok(accounts.includes('Secure YouTube testing has expired or is disabled'));
});

test('phone recheck: Step 2 slider travels under the same sticky scoreboard without percent',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const css=await readFile(new URL('./app.css',import.meta.url),'utf8');
 const wizard=studio.slice(studio.indexOf('function setupWizard(){'),studio.indexOf('function updateWizardStickyScoreboard(){'));
 assert.ok(wizard.includes('img-wizard-score-sticky-unit'));
 assert.ok(wizard.includes('img-wizard-score-size-control'));
 assert.ok(wizard.includes('data-score-range="size"'));
 assert.ok(wizard.includes('scoreSettings(false,true)'));
 assert.equal((wizard.match(/data-score-range="size"/g)||[]).length,1,'Exactly one Step 2 Size slider');
 assert.equal((wizard.match(/data-setup-scoreboard/g)||[]).length,1,'Exactly one preview canvas');
 assert.ok(!wizard.includes('data-score-range-output="size"'),'Remove preview slider percent output');
 assert.ok(studio.includes("const preview=root?.querySelector('.img-wizard-score-sticky-unit')"),'Pin the whole original preview and slider');
 assert.ok(css.includes('.img-wizard-score-sticky-unit.img-score-pinned'));
 assert.ok(css.includes('.img-wizard-score-sticky-unit .img-wizard-score-size-control'));
});

test('Step 4 provider panels stay independent of connection dropdown collapse',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const wizard=studio.slice(studio.indexOf('function setupWizard(){'),studio.indexOf('function updateWizardStickyScoreboard(){'));
 const yt=wizard.slice(wizard.indexOf('  (ytConnected?'),wizard.indexOf('  (fbConnected?'));
 const fb=wizard.slice(wizard.indexOf('  (fbConnected?'),wizard.indexOf('  card(String(2+Number'));
 assert.ok(yt.startsWith('  (ytConnected?'),'Connected YouTube keeps settings visible');
 assert.ok(!yt.includes('youtubeExpanded'),'YouTube settings cannot depend on dropdown expansion');
 assert.ok(yt.includes('img-wizard-provider-title')&&yt.includes('YouTube</span>'));
 assert.ok(yt.includes('data-youtube-audience'));
 assert.ok(fb.startsWith('  (fbConnected?'),'Facebook settings use own connected-provider card');
 assert.ok(!fb.includes('fbExpanded'),'Facebook settings cannot depend on dropdown expansion');
 assert.ok(fb.includes('Facebook</span>'));
 assert.ok(fb.includes('facebookLiveTitle')&&fb.includes('facebookLiveDescription'));
 assert.ok(wizard.indexOf('  (ytConnected?')<wizard.indexOf('  (fbConnected?'),'Facebook settings follow YouTube');
 assert.ok(!wizard.includes("card('2','Stream Details'"),'Generic heading must be removed');
});
test('Step 3 sponsor row has per-sponsor image upload, thumbnail, replace and remove',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const block=studio.slice(studio.indexOf('function sponsorSettings(){'),studio.indexOf('// Shared public graphic'));
 assert.ok(block.includes("data-upload='sponsor-"),'Each row has separate image input');
 assert.ok(block.includes("s.img?")&&block.includes("<img src="),'Image thumbnail appears when set');
 assert.ok(block.includes('Change image')&&block.includes('Add image'));
 assert.ok(block.includes("data-action='sponsor-clear-img-"),'Remove image independently');
 assert.ok(studio.includes("data.sponsors[i].img=''"));
 assert.ok(studio.includes("preparedLogoData(file,t.dataset.upload.startsWith('sponsor-')?512:256)"));
 assert.ok(studio.includes('data.sponsors[i].img=value;try{'));
 assert.ok(studio.includes('saveOriginalSponsor(file)'),'Original image is stored separately without resampling');
 assert.ok(studio.includes('sendNativeSponsorArtwork(namedSponsors)'),'Native bridge receives image-backed sponsors');
 assert.ok(studio.includes('native-sponsor-chunk:'),'Native image transport is chunked and bounded');
 assert.ok(!studio.includes('namedSponsors.slice(0,3)'),'Later sponsor images must not be omitted');
});

test('Edit Sponsor Name dialog only edits name; Step 3 retains separate image uploads',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const css=await readFile(new URL('./app.css',import.meta.url),'utf8');
 const start=studio.indexOf("if(d.type==='sponsor-edit')body=");
 const end=studio.indexOf("if(d.type==='team-edit')body=",start);
 assert.ok(start>=0&&end>start,'Sponsor name edit dialog found');
 const modal=studio.slice(start,end);
 assert.ok(modal.includes('Edit Sponsor Name'));
 assert.ok(modal.includes('Sponsor name'));
 assert.ok(modal.includes('id="sponsor-title"'));
 assert.ok(modal.includes('maxlength="50"'));
 assert.ok(modal.includes('data-action="save-sponsor"'));
 assert.ok(modal.includes('Save Name'));
 assert.ok(modal.includes('data-action="close-modal"'));
 for(const excluded of ['Add sponsor image','optional local logo','data-upload','accept="image/*"','Add sponsor name'])
  assert.ok(!modal.includes(excluded),'Remove extraneous popup content: '+excluded);
 assert.ok(studio.includes("data.sponsors[dialog.i].name=n"),'Only name should be saved by edit');
 assert.ok(studio.includes("data-upload='sponsor-"),'Sponsor images remain in separate Step 3 rows');
 assert.ok(css.includes('.img-sponsor-name-editor-actions'));
});

test('native offline mode reuses real operator, pauses for Resume or End and never requires OAuth',async()=>{
 const activity=await readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8');
 const service=await readFile(new URL('../java/com/imgofficial/livestudio/v2/V2BroadcastService.kt',import.meta.url),'utf8');
 const main=await readFile(new URL('../java/com/imgofficial/livestudio/v2/MainActivity.kt',import.meta.url),'utf8');
 const app=await readFile(new URL('./app.js',import.meta.url),'utf8');
 assert.ok(main.includes('native-offline-controls:')&&main.includes('.putExtra("img_offline_recording",true)'));
 assert.ok(app.includes("if(name==='setup-record-offline')return openNativeOfflineControls()"));
 assert.ok(activity.includes('start=greenButton(if(offlineMode)"RECORD" else "GO LIVE")'));
 assert.ok(activity.includes('offlineMode&&offlineNow->"STOP"'));
 assert.ok(activity.includes('confirmStopOfflineRecording()')&&activity.includes('greenButton("RESUME")')&&activity.includes('dangerButton("END")'));
 assert.ok(activity.includes('if(!offlineMode&&(!Regex('),'Offline entry cannot demand YouTube draft');
 assert.ok(service.includes('fun startOfflineRecording():Boolean')&&service.includes('fun pauseOfflineRecording():Boolean')&&service.includes('fun resumeOfflineRecording():Boolean'));
 assert.ok(service.includes('stream?.pauseRecord()')&&service.includes('stream?.resumeRecord()'));
 assert.ok(app.includes("window.IMGNativeChannel.postMessage('native-offline-controls:'"));
});
test('Timeout suspends game clock and native GL sponsor slides reach real recording/stream video',async()=>{
 const a=await readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8');
 const svc=await readFile(new URL('../java/com/imgofficial/livestudio/v2/V2BroadcastService.kt',import.meta.url),'utf8');
 const timeout=a.slice(a.indexOf('private fun setTimeoutMode('),a.indexOf('private fun confirmStopUpload()'));
 assert.ok(timeout.includes('gameRunning=false;lastTick=0L'));
 assert.ok(timeout.includes('broadcast?.setSponsorSlideshow(timeoutSlideshowEnabled)'));
 assert.ok(timeout.includes('broadcast?.setSponsorSlideshow(false)'));
 assert.ok(timeout.includes('gameRunning=gameWasRunningBeforeTimeout&&gameSeconds>0'));
 assert.ok(svc.includes('ImageFilterRender().also{gl.addFilter(it)}'));
 assert.ok(svc.includes('sponsorFilter?.setImage(frame)'));
 assert.ok(svc.includes('ui.postDelayed(sponsorAdvance,sponsorIntervalMs)'));
 assert.ok(svc.includes('ui.removeCallbacks(sponsorAdvance)'));
 assert.ok(a.includes('android.widget.Switch(this)'),'Timeout offers operator slideshow switch');
 assert.ok(a.includes('Show Slideshow')&&a.includes('nextSponsor.isEnabled=timeoutSlideshowEnabled'));
 assert.ok(!timeout.includes('Sponsor slideshow preview'),'No slideshow preview dialog');
 assert.ok(svc.includes('scoreVisibleBeforeSponsor=scoreVisible'),'Remember scoreboard visibility');
 assert.ok(svc.includes('setScorecardVisibleInstant(false)'),'Immediately hide scoreboard while slides run');
 assert.ok(svc.includes('if(previous!=null)setScorecardVisibleInstant(previous)'),'Immediately restore original scoreboard visibility');
 assert.ok(timeout.includes('gameWasRunningBeforeTimeout=gameRunning'),'Capture pre-timeout run state');
});

test('V2 Timeout sponsor artwork is unframed, image only, original quality and compact operator controls',async()=>{
 const [native,svc,app,bridge]=await Promise.all([
  readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8'),
  readFile(new URL('../java/com/imgofficial/livestudio/v2/V2BroadcastService.kt',import.meta.url),'utf8'),
  readFile(new URL('./app.js',import.meta.url),'utf8'),
  readFile(new URL('../java/com/imgofficial/livestudio/v2/MainActivity.kt',import.meta.url),'utf8')
 ]);
 const sponsor=svc.slice(svc.indexOf('private var sponsorSlides:'),svc.indexOf('fun configureLocalRecording('));
 assert.ok(sponsor.includes('drawSponsorOnCamera')||sponsor.includes('private fun drawSponsor('));
 assert.ok(sponsor.includes('android.graphics.RectF('),'Sponsor image centered on transparent camera output');
 assert.ok(!sponsor.includes('canvas.drawRoundRect'),'No decorative rectangle/frame');
 assert.ok(!sponsor.includes('canvas.drawText('),'No sponsor name or IMG title text');
 assert.ok(sponsor.includes('minOf(w*.92f/bitmap.width,h*.90f/bitmap.height,1f)'),'Aspect ratio preserved, vertical margin and no crop');
 assert.ok(sponsor.includes('sponsorTransitionMs'),'Crossfade duration adjustable');
 assert.ok(sponsor.includes('sponsorIntervalMs'),'Auto timing adjustable');
 assert.ok(sponsor.includes('ui.removeCallbacks(sponsorAdvance)'),'NEXT resets pending auto advance');
 assert.ok(native.includes('dp(230)'),'Timeout popover remains compact');
 assert.ok(native.includes('Mode')&&native.includes('Slide every')&&native.includes('Transition'));
 assert.ok(native.includes('timeoutButton.text=if(timeoutActive)"RESUME"'));
 assert.ok(native.includes('timeoutPreview?.dismiss()')&&native.includes('setTimeoutMode(false)'));
 assert.ok(app.includes("indexedDB.open('img-live-sponsor-artwork-v2'"));
 assert.ok(app.includes('originalKey=await saveOriginalSponsor(file)'));
 assert.ok(app.includes('sendNativeSponsorArtwork(namedSponsors)')&&app.includes('sendNativeSponsorArtwork(sponsors)'));
 assert.ok(bridge.includes('receiveSponsorTransfer(action)')&&bridge.includes('imgsponsor_'));
 assert.ok(native.includes('decodeSponsorFile(token,i)'));
});

test('V2 home offline recording cards, truthful platform marks and single-tap timeout fade',async()=>{
 const [app,service,native,css]=await Promise.all([
  readFile(new URL('./app.js',import.meta.url),'utf8'),
  readFile(new URL('../java/com/imgofficial/livestudio/v2/V2BroadcastService.kt',import.meta.url),'utf8'),
  readFile(new URL('../java/com/imgofficial/livestudio/v2/PrivateYoutubeCameraActivity.kt',import.meta.url),'utf8'),
  readFile(new URL('./app.css',import.meta.url),'utf8')
 ]);
 assert.ok(!app.includes('function recordings(){'),'Remove dedicated recordings page');
 assert.ok(!app.includes("case'recordings':body="),'Remove recordings route');
 assert.ok(app.includes('id="home-offline-recordings"'),'Offline records live on Home');
 assert.ok(app.includes('const streams=clips.filter(x=>recordedPlatforms(x).length>0)'),'My Streams excludes offline MP4');
 assert.ok(app.includes('const offlineRecordings=clips.filter(x=>!recordedPlatforms(x).length)'));
 assert.ok(app.includes("action=(clip.native?'open-native-recording-':'play-recording-')"),'Saved clips remain playable');
 assert.ok(app.includes('function platformMarks(values)')&&app.includes('recording-platform-logos'));
 assert.ok(app.includes('youtube:')&&app.includes('facebook:'),'Both real destination logos supported');
 assert.ok(app.includes('clip.native?[]:'),'Legacy forced YouTube metadata is not trusted');
 assert.ok(service.includes('.put("platform",if(wasOffline)"Offline" else "YouTube")'));
 assert.ok(service.includes('.put("platforms",if(wasOffline)org.json.JSONArray()'),'Offline recordings have zero platform marks');
 assert.ok(native.includes('android.media.ExifInterface'),'Portrait source EXIF respected');
 assert.ok(service.includes('val factor=minOf(w*.92f/bitmap.width,h*.90f/bitmap.height,1f)'),'Original aspect ratio maintained without crop');
 assert.ok(native.includes('broadcast?.setScorecardVisible(false)'),'Timeout fades scoreboard out');
 assert.ok(native.includes('broadcast?.setScorecardVisible(scoreWasVisibleBeforeTimeout)'),'Resume fades scoreboard back');
 assert.ok(native.includes('timeoutPreview?.dismiss();timeoutPreview=null'),'Resume immediately closes settings');
 assert.ok(native.includes('PopupWindow(content,dp(230),-2,false)'),'Popup must not capture the first tap on Resume');
 assert.ok(css.includes('.img-offline-recording-card')&&css.includes('.record-platform-icon'));
});

test('Home hides the entire Offline Recordings section until a saved clip exists',async()=>{
 const studio=await readFile(new URL('./app.js',import.meta.url),'utf8');
 const home=studio.slice(studio.indexOf('function home(){'),studio.indexOf('function eventRow('));
 assert.ok(home.includes("offlineRecordings.length?"),'Offline section is conditional on saved offline videos');
 assert.ok(home.includes('id="home-offline-recordings"'),'Offline recordings remain accessible on Home when present');
 assert.ok(home.includes("offlineRecordings.map(x=>savedRecordingCard(x,true))"),'Saved offline clips still render as playable cards');
 assert.ok(!home.includes('data-action="refresh-recordings"'),'Do not show manual Refresh');
 assert.ok(!home.includes('Your offline recordings will appear here after recording.'),'No empty recordings placeholder');
 assert.ok(!home.includes('Choose a connected destination in Broadcast Settings to start a stream.'),'No connected destination helper on Home');
 assert.ok(studio.includes("window.addEventListener('img-native-recordings'"),'Home continues to receive automatic saved native clip updates');
});

test('Native V2 replay: rolling encoded ring, on-air 3-mode GPU compositor and offline controls',async()=>{
 const root=new URL('../java/com/imgofficial/livestudio/v2/',import.meta.url);
 const files=Object.fromEntries(await Promise.all([
  'V2ReplayRing.kt','V2ReplayFilter.java','V2ReplayPlayback.kt',
  'V2ReplayPanel.kt','V2ReplayManager.kt','V2BroadcastService.kt',
  'PrivateYoutubeCameraActivity.kt'
 ].map(async name=>[name,await readFile(new URL(name,root),'utf8')])));
 const ring=files['V2ReplayRing.kt'],render=files['V2ReplayFilter.java'],
  decoder=files['V2ReplayPlayback.kt'],panel=files['V2ReplayPanel.kt'],
  manager=files['V2ReplayManager.kt'],service=files['V2BroadcastService.kt'],
  activity=files['PrivateYoutubeCameraActivity.kt'];
 assert.ok(ring.includes('RecordController by delegate'),'Retain approved original recording lifecycle');
 assert.ok(ring.includes('13_000_000L')&&ring.includes('48L * 1024L * 1024L'),
  'Rolling buffer bounded by duration and memory');
 assert.ok(ring.includes('lastPts - 10_000_000L'),'Capture last ten seconds');
 assert.ok(ring.includes('it.key')&&ring.includes('MediaMuxer('),'Saved clip starts with keyframe and is MP4');
 assert.ok(service.includes('replay.setCapturing(true)'),'Capture active in both live and offline modes');
 assert.ok(service.includes('replay.setCapturing(false)'),'Pause/stop clears unavailable footage');
 assert.ok(service.includes('replay.installGl(engine)'),'Decoder overlay runs in GPU production compositor');
 assert.ok(render.includes('DIAGONAL=2')&&render.includes('CORNER=3'));
 assert.ok(render.includes('samplerExternalOES'),'Replay source decoded onto separate OES texture');
 assert.ok(render.includes('vec4(.025,.525,.475,.975)'),'Diagonal has top-left window');
 assert.ok(render.includes('vec4(.525,.025,.975,.475)'),'Diagonal has separate bottom-right window');
 assert.ok(render.includes('float elapsed=')&&render.includes('/500f'),'500ms transitions');
 assert.ok(render.includes('Math.min(4f,amount)'),'Full replay zoom max 4x');
 assert.ok(decoder.includes('MediaExtractor')&&decoder.includes('MediaCodec.createDecoderByType'));
 assert.ok(decoder.includes('rate=speed().coerceIn(.25f,1f)'));
 assert.ok(panel.includes('listOf("Full","Diagonal","Corner")'),'Three equal display buttons');
 assert.ok(panel.includes('listOf(.25f,.5f,1f)'),'Three speed choices');
 assert.ok(panel.includes('button("Save")')&&panel.includes('button("Back to Live")'));
 assert.ok(panel.includes('instantReplayIsFull()!=true'),'Touch gestures only while Full replay active');
 assert.ok(activity.includes('replayUi.show(replayButton)'),'Operator REPLAY button');
 assert.ok(manager.includes('indexSavedClip(destination!!,source.name)'),
  'Exported gallery clips accessible in IMG recordings');
 assert.ok(manager.includes('context.contentResolver.openOutputStream(destination!!)'));
 assert.ok(!panel.includes('INSTANT REPLAY')&&!panel.includes('10s"'),'No panel heading/countdown');
});

test('V2 Instant Replay update: four compact rows, fixed OFF AIR snapshot, bidirectional drag and autoplay',async()=>{
 const root=new URL('../java/com/imgofficial/livestudio/v2/',import.meta.url);
 const files=Object.fromEntries(await Promise.all([
  'V2ReplayManager.kt','V2ReplayPlayback.kt','V2ReplayPanel.kt',
  'PrivateYoutubeCameraActivity.kt','V2BroadcastService.kt'
 ].map(async name=>[name,await readFile(new URL(name,root),'utf8')])));
 const m=files['V2ReplayManager.kt'],player=files['V2ReplayPlayback.kt'],
 panel=files['V2ReplayPanel.kt'],activity=files['PrivateYoutubeCameraActivity.kt'],
 service=files['V2BroadcastService.kt'];
 assert.ok(activity.includes('darkButton("INSTANT REPLAY")'));
 assert.ok(activity.includes('val keys=listOf(start,timeoutButton,replayButton,'));
 assert.ok(activity.includes('iconButton(darkButton("TOOLS")'),'Tool control unchanged');
 assert.ok(activity.includes('TextureView(this)')&&activity.includes('prepareInstantReplay'));
 assert.ok(activity.includes('privateReplayView.visibility=View.VISIBLE'),'Private footage is operator-only');
 assert.ok(panel.includes('listOf("Full","Diagonal","Corner")'),'Display modes row');
 assert.ok(panel.includes('val timeline=line()')&&panel.includes('SeekBar(activity)'),'Seek slider second row');
 assert.ok(panel.includes('listOf(.25f,.5f,1f)'),'Speed third row');
 assert.ok(panel.includes('button("Save")')&&panel.includes('button("Back to Live")'),'Actions fourth row');
 assert.ok(!panel.includes('button("Play")'),'Never require an extra Play button');
 assert.ok(panel.includes('seekInstantReplay(progress.toLong(),true)'),'Dragging requests frame target');
 assert.ok(panel.includes('seekInstantReplay(seekBar.progress.toLong(),false)'),'Release auto-plays forward');
 assert.ok(m.includes('val sample=ring.snapshot()'),'OFF AIR preparation freezes snapshot');
 assert.ok(m.includes('ring.export(sample,output)'),'Original fixed clip is exported privately');
 assert.ok(m.includes('fun attachPrivatePreview(surface:android.view.Surface)'),'Separate private decoder surface');
 assert.ok(m.includes('fun putOnAir(mode:Int)'),'Broadcast only on mode tap');
 assert.ok(m.includes('if(active)onAirDecoder?.seekTo(safe,dragging)'),'ON AIR scrubbing moves actual compositor');
 assert.ok(m.includes('else operatorDecoder?.seekTo(safe,dragging)'),'OFF AIR scrubbing stays private');
 assert.ok(player.includes('MediaExtractor.SEEK_TO_PREVIOUS_SYNC'),'Seek starts from decode keyframe');
 assert.ok(player.includes('if(pts+frameDurationUs/2<requestedUs)'),'Decode dependent frames to requested time');
 assert.ok(player.includes('val rate=speed().coerceIn(.25f,1f)'),'Auto-play honors selected rate');
 assert.ok(player.includes('private val returnToLiveAtEnd:Boolean'),'Only ON AIR completion returns to LIVE');
 assert.ok(service.includes('fun seekInstantReplay(positionMs:Long,dragging:Boolean)'),'Both broadcast modes share seek');
});
