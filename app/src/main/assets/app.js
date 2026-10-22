/* IMG LIVE Studio — native Android sports broadcast operator interface. */
(() => {
'use strict';
const KEY='img-live-ui-v7', LEGACY_KEY='img-live-ui-v6';
const $=s=>document.querySelector(s);
/* Android keyboards can shrink the CSS viewport until (orientation:landscape)
 * matches even though the actual device is still portrait. Use the hardware
 * orientation for layout decisions, not keyboard-resized viewport dimensions.
 */
function isPhysicalLandscape(){
 const orientation=window.screen?.orientation?.type;
 if(typeof orientation==='string'&&orientation.startsWith('landscape'))return true;
 if(typeof orientation==='string'&&orientation.startsWith('portrait'))return false;
 const sw=Number(window.screen?.width),sh=Number(window.screen?.height);
 if(sw>0&&sh>0&&sw!==sh)return sw>sh;
 return window.matchMedia('(orientation: landscape)').matches;
}
function useLandscapeSetup(){
 return isPhysicalLandscape()&&window.innerWidth>=570&&window.innerWidth>window.innerHeight;
}

const svgPaths={
 home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
 calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
 square:'<rect x="5" y="5" width="14" height="14" rx="2"/>',sliders:'<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="2.5" fill="currentColor" stroke="#ffffff"/><circle cx="15" cy="17" r="2.5" fill="currentColor" stroke="#ffffff"/>',
 trophy:'<path d="M8 21h8M12 17v4M7 4h10v6a5 5 0 0 1-10 0zM7 6H4v3a4 4 0 0 0 4 4M17 6h3v3a4 4 0 0 1-4 4"/>',
 settings:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a2 2 0 0 0 .4 2.2l-2.2 2.2a2 2 0 0 0-2.2-.4l-.8.3-.6 2h-3l-.6-2-.8-.3a2 2 0 0 0-2.2.4l-2.2-2.2a2 2 0 0 0 .4-2.2l-.3-.8-2-.6v-3l2-.6.3-.8a2 2 0 0 0-.4-2.2l2.2-2.2a2 2 0 0 0 2.2.4l.8-.3.6-2h3l.6 2 .8.3a2 2 0 0 0 2.2-.4l2.2 2.2a2 2 0 0 0-.4 2.2l.3.8 2 .6v3l-2 .6z"/>',
 plus:'<path d="M12 5v14M5 12h14"/>',minus:'<path d="M5 12h14"/>',arrow:'<path d="M5 12h14m-6-6 6 6-6 6"/>',back:'<path d="m15 18-6-6 6-6"/>',chevron:'<path d="m6 9 6 6 6-6"/>',up:'<path d="m6 15 6-6 6 6"/>',more:'<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
 radio:'<circle cx="12" cy="12" r="3"/><path d="M5.6 5.6a9 9 0 0 0 0 12.8m12.8-12.8a9 9 0 0 1 0 12.8M2.7 2.7a13 13 0 0 0 0 18.6m18.6-18.6a13 13 0 0 1 0 18.6"/>',
 camera:'<rect x="2" y="5" width="20" height="15" rx="3"/><circle cx="12" cy="12.5" r="4"/><path d="m7 5 2-3h6l2 3"/>',video:'<rect x="3" y="5" width="18" height="15" rx="2"/><path d="m10 9 6 4-6 4z"/>',
 check:'<path d="m5 12 5 5 9-10"/>',
 'check-circle':'<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
 'alert-circle':'<circle cx="12" cy="12" r="9"/><path d="M12 7v6"/><circle cx="12" cy="17" r=".8" fill="currentColor" stroke="none"/>',
 circle:'<circle cx="12" cy="12" r="9"/>',x:'<path d="M18 6 6 18M6 6l12 12"/>',play:'<path d="m8 5 11 7-11 7z"/>',pause:'<path d="M7 5v14M17 5v14"/>',repeat:'<path d="M20 7V3l-4 4h4a8 8 0 1 1-7-3"/>',undo:'<path d="M9 14 4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3"/>',
 image:'<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/>',upload:'<path d="M12 16V3m-5 5 5-5 5 5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4"/>',
 clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',users:'<circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2M17 5a3 3 0 0 1 0 6M17 15a5 5 0 0 1 4 5"/>',
 sound:'<path d="M3 9v6h4l5 4V5L7 9zM16 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12"/>',
 shield:'<path d="m12 2 9 4v5c0 6-4 10-9 11-5-1-9-5-9-11V6z"/><path d="m8 12 3 3 5-6"/>',
 fire:'<path d="M12 22c-5 0-8-3-8-8 0-4 3-8 5-10 0 4 2 5 3 6 2-2 3-5 3-8 3 3 5 7 5 12 0 5-3 8-8 8z"/>',
 edit:'<path d="m15 5 4 4M4 20l4.8-1.1L20 7.6 16.4 4 5.1 15.2z"/>',globe:'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c4 5 4 13 0 18M12 3c-4 5-4 13 0 18"/>',
 eye:'<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
 list:'<path d="M9 6h12M9 12h12M9 18h12M3 6h2M3 12h2M3 18h2"/>',
 bolt:'<path d="m14 2-9 12h6l-1 8 9-12h-6z"/>',
 trash:'<path d="M4 7h16m-14 0 1 14h10l1-14M9 7V4h6v3M10 11v7M14 11v7"/>',
 sync:'<path d="M20 10a8 8 0 0 0-14-5L3 8m0-5v5h5M4 14a8 8 0 0 0 14 5l3-3m0 5v-5h-5"/>',
 mobile:'<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/>',
 landscape:'<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M11 14h2"/>',
 sports:'<circle cx="6.8" cy="6.8" r="3.3"/><path d="M4.5 4.5c1.6 1.2 2.9 2.6 4.6 4.6M4.6 9l4.3-4.3M14 10l7-7M13 11l-2.6 2.6a4.5 4.5 0 0 0 6.4 6.4l2.5-2.5a4.5 4.5 0 0 0-6.3-6.5Z"/>',
 basketball:'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3v18M5.65 5.65c2.9 2.7 5.1 6.5 5.6 12.5M18.35 5.65c-2.9 2.7-5.1 6.5-5.6 12.5"/>',
 user:'<circle cx="12" cy="8" r="4"/><path d="M4.5 21v-2.5c0-3.7 3.2-6 7.5-6s7.5 2.3 7.5 6V21"/>',
 pickleball:'<ellipse cx="8.8" cy="8.8" rx="5.9" ry="6.8" transform="rotate(-30 8.8 8.8)"/><path d="m12.8 13.8 6.4 7.1a1.3 1.3 0 0 0 1.8-1.8l-6.4-6.4"/><circle cx="7" cy="6.3" r=".8" fill="currentColor" stroke="none"/><circle cx="10.7" cy="7.1" r=".8" fill="currentColor" stroke="none"/><circle cx="7.5" cy="10.6" r=".8" fill="currentColor" stroke="none"/><circle cx="11.7" cy="11.3" r=".8" fill="currentColor" stroke="none"/><circle cx="19" cy="4.6" r="2.25"/>'};
function ic(name,size=20){return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${svgPaths[name]||svgPaths.more}</svg>`}
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const uid=()=>String(Date.now())+Math.random().toString(36).slice(2,6);
const SPORTS=[{id:'basketball',name:'Basketball',icon:'basketball',description:'Basketball broadcasts and leagues'},{id:'pickleball',name:'Pickleball',icon:'pickleball',description:'Pickleball match broadcasting'}];
const sportName=()=>SPORTS.find(x=>x.id===data.options.sport)?.name||'Basketball';
const activeSport=()=>data.options.sport==='pickleball'?'pickleball':'basketball';
const sportLeagues=()=>data.leagues.filter(l=>(l.sport||'basketball')===activeSport());
const defaults=()=>({version:8,leagues:[],events:[],sponsors:[{name:'',img:''}],options:{sport:'basketball',basketballMode:'standard',streetFormat:'1v1',streetShowTeams:false,streetOverlaySize:45,streetOverlayOpacity:100,streetOverlayColor:'#E66D48',streetWinTarget:21,streetPositions:[{x:.14,y:.52},{x:.86,y:.52}],regularOrientation:'landscape',streetPlayers:[[{name:'',photo:''},{name:'',photo:''}],[{name:'',photo:''},{name:'',photo:''}]],pickleballTarget:11,pickleballType:'doubles',pickleballScoring:'sideout',pickleballMatch:'best3',orientation:'landscape',appOrientation:'landscape',appearance:'dark',overlay:'prime-tv',overlaySize:60,overlayOpacity:100,overlayColor:'#2FE0B0',overlayPosition:'bottom-center',overlayOffsetX:0,overlayOffsetY:0,overlayWidth:100,overlayHeight:100,overlaySafe:3,overlayAnimated:true,quality:'720p',fps:30,shotVisible:true,mic:true,micInputs:[{id:'device',name:'Device microphone',enabled:true,type:'device'}],broadcast:{homeName:'',homeLogo:'',awayName:'',awayLogo:'',leagueName:'',leagueLogo:'',showLeagueName:false},yt:false,fb:false,tiktok:false,penalty:5,quarterMins:10,slideSecs:5,slideTransition:'fade'},live:{active:false,lockedOrientation:null,timeout:false,slides:false,playing:true,slideIndex:0,recordOnly:false,seconds:600,shot:24,clockRunning:false,quarter:1,scores:[0,0],fouls:[0,0],possession:0,pickleballGames:[0,0],pickleballGame:1,pickleballServer:0,pickleballServeNumber:2,pickleballWinner:-1,pickleballMatchWinner:-1,pickleballRallies:0,pickleballRallyMessage:'',undo:[]},selectedEventId:null,selectedLeagueId:null})
function load(){const d=defaults();try{const fromKey=localStorage.getItem(KEY),raw=fromKey||localStorage.getItem(LEGACY_KEY),saved=JSON.parse(raw||'null');if(saved&&([6,7,8].includes(saved.version))){const o={...d.options,...saved.options};o.fb=false;o.broadcast={...d.options.broadcast,...saved.options?.broadcast};
// Convert original prefilled examples to placeholders without erasing custom names.
if(o.broadcast.homeName==='Home Team')o.broadcast.homeName='';
if(o.broadcast.awayName==='Away Team')o.broadcast.awayName='';o.basketballMode=o.basketballMode==='streetball'?'streetball':'standard';if(!['1v1','2v2','3v3'].includes(o.streetFormat))o.streetFormat='1v1';o.streetOverlaySize=Math.max(15,Math.min(115,Number(o.streetOverlaySize)||45));
o.streetOverlayOpacity=Math.max(30,Math.min(100,o.streetOverlayOpacity==null?100:Number(o.streetOverlayOpacity)));
if(!/^#[0-9a-fA-F]{6}$/.test(o.streetOverlayColor||''))o.streetOverlayColor='#E66D48';
 o.streetWinTarget=Math.max(1,Math.min(999,Math.round(Number(o.streetWinTarget)||21)));
 if(!['sideout','rally'].includes(o.pickleballScoring))o.pickleballScoring='sideout';
 if(!['light','dark'].includes(o.appearance))o.appearance='dark';
 if(!['single','best3','best5'].includes(o.pickleballMatch))o.pickleballMatch='best3';
const oldPositions=Array.isArray(saved.options?.streetPositions)?saved.options.streetPositions:[];
 const originalY=Number(oldPositions[0]?.y);
 const sharedY=oldPositions[0]?.y!=null&&Number.isFinite(originalY)?Math.max(.23,Math.min(.70,originalY)):.52;
 const oldLeft=Number(oldPositions[0]?.x),oldRight=Number(oldPositions[1]?.x);
 const hasLeft=oldPositions[0]?.x!=null&&Number.isFinite(oldLeft);
 const hasRight=oldPositions[1]?.x!=null&&Number.isFinite(oldRight);
 let edge=hasLeft?oldLeft:hasRight?1-oldRight:.14;
 if(hasLeft&&hasRight&&Math.abs(oldLeft-.18)<.001&&Math.abs(oldRight-.82)<.001)edge=.14;
 edge=Math.max(.10,Math.min(.24,edge));
 o.streetPositions=[{x:edge,y:sharedY},{x:1-edge,y:sharedY}];
 o.regularOrientation='landscape';if(o.basketballMode!=='streetball')o.orientation='landscape';
o.streetPlayers=d.options.streetPlayers.map((row,i)=>row.map((p,j)=>({...p,...(saved.options?.streetPlayers?.[i]?.[j]||{})})));if(!SPORTS.some(x=>x.id===o.sport))o.sport='basketball';o.overlay=normalizeGraphic(({'simple':'prime-tv','narrow':'broadcast-classic','corner-left':'edge-box','corner-right':'match-cube','pro-arena':'arena-glass','pro-league':'courtside-a','tv':'prime-tv','minimal':'broadcast-classic','arena':'arena-glass'})[o.overlay]||o.overlay);
o.overlayPosition=['top-left','top-right','bottom-left','bottom-right','bottom-center'].includes(o.overlayPosition)?o.overlayPosition:(graphicIsCompact(o.overlay)?'bottom-right':'bottom-center');
for(const [field,min,max,standard] of [['overlayOffsetX',-30,30,0],['overlayOffsetY',-30,30,0],['overlayWidth',65,150,100],['overlayHeight',65,150,100],['overlaySafe',1,10,3]]){
 o[field]=Math.max(min,Math.min(max,Number.isFinite(Number(o[field]))?Number(o[field]):standard));
}
o.overlayAnimated=o.overlayAnimated!==false;o.overlaySize=saved.version<8&&Number(o.overlaySize)===100?45:Math.max(15,Math.min(115,Number(o.overlaySize)||45));o.overlayOpacity=Math.max(30,Math.min(100,o.overlayOpacity==null?100:Number(o.overlayOpacity)));if(!/^#[0-9a-fA-F]{6}$/.test(o.overlayColor||''))o.overlayColor='#E66D48';o.micInputs=Array.isArray(saved.options?.micInputs)&&saved.options.micInputs.length?saved.options.micInputs:d.options.micInputs;if(saved.version===6){o.orientation='landscape';o.micInputs[0].enabled=Boolean(saved.options?.mic??true)}const savedSponsors=Array.isArray(saved.sponsors)?saved.sponsors:[];
 const cannedNames=['COURTSIDE','NORTHSTAR','PEAK SPORT'];
 const onlyCannedSponsors=savedSponsors.length===3&&savedSponsors.every((item,i)=>item?.name===cannedNames[i]&&!item?.img);
 return {...d,...saved,sponsors:onlyCannedSponsors||!savedSponsors.length?d.sponsors:savedSponsors,version:8,options:o,live:{...d.live,...saved.live,active:false,recordOnly:false,clockRunning:false,slides:false,timeout:false,lockedOrientation:null}}}}catch(e){}return d}
const data=load();
function applyAppearance(){
 const theme=data.options.appearance==='dark'?'dark':'light';
 document.documentElement.setAttribute('data-theme',theme);
 const meta=document.querySelector('meta[name="theme-color"]');
 if(meta)meta.setAttribute('content',theme==='dark'?'#10191f':'#f8f6f2');
}
applyAppearance();
let offlineClips=[],scoreFlashTeam=-1,scoreFlashAt=0;let screen='home',createStep=0,selectedLeagueId=data.selectedLeagueId,selectedEventId=data.selectedEventId,focusTab='score',sponsorOpen=false,dialog=null,toastTimer=null,draft=null,gameDraft=null,toastText='',needsRender=false;
let remotePublishTimer=0,remoteLastPublished='';
let youtubeExpanded=false,youtubeBusy=false,youtubeAudience="",youtubeFeedback="",advancedOpen=false;
let fbFields={serverUrl:'rtmps://live-api-s.facebook.com:443/rtmp/',streamKey:''},fbConnecting=false,fbExpanded=false,fbMessage='',fbGeneration=0,fbAttemptTimer=null,fbFeedbackState='idle';
window.IMGFacebookLive?.setListener?.(({phase,detail})=>{
 if(screen!=='live'||!data.options.fb)return;
 const el=document.querySelector('[data-facebook-live-status]');
 if(el)el.textContent=phase==='sending'?'Sending video · Verify Facebook Live Producer preview':phase==='error'?'VIDEO DISCONNECTED · '+detail:'Video relay: '+phase;
 if(phase==='error')toast('Video upload stopped. End the session and reconnect.');
});
function queueRemotePublish(){
 const api=window.IMGRemote;
 if(!api||!['setup','live'].includes(screen))return;
 const r=api.status();
 if(r.role!=='host'||!r.connected)return;
 const serialized=JSON.stringify(remoteGameSnapshot());
 if(serialized===remoteLastPublished)return;
 clearTimeout(remotePublishTimer);
 remotePublishTimer=setTimeout(()=>{
  const latest=JSON.stringify(remoteGameSnapshot());
  if(latest!==remoteLastPublished&&api.commitHostState()){remoteLastPublished=latest}
 },75);
}
function persist(){try{localStorage.setItem(KEY,JSON.stringify(data))}catch(e){}queueRemotePublish()}
function toast(message){
 toastText=message;clearTimeout(toastTimer);
 const app=document.querySelector('.app');
 if(!app)return render(true);
 let node=app.querySelector('.toast');
 if(!node){node=document.createElement('div');node.className='toast';node.setAttribute('role','status');app.appendChild(node)}
 node.textContent=String(message);
 toastTimer=setTimeout(()=>{toastText='';document.querySelector('.app .toast')?.remove();},2300);
}
function datestr(date){const d=new Date(date+'T12:00:00');return Number.isNaN(d.valueOf())?date:d.toLocaleDateString(undefined,{month:'short',day:'numeric'})}
function formatTime(n){const secs=Math.max(0,Math.floor(Number(n)||0));return String(Math.floor(secs/60)).padStart(2,'0')+':'+String(secs%60).padStart(2,'0')}
function formatGameClock(n){const ms=Math.max(0,Math.round((Number(n)||0)*1000));if(ms<60000)return String(Math.floor(ms/1000)).padStart(2,'0')+'.'+String(ms%1000).padStart(3,'0');return formatTime(ms/1000)}
function periodLabel(q){const n=Number(q)||1;return n<=4?'Q'+n:n===5?'OT':'OT'+(n-4)}
function shotValue(n){return Math.max(0,Math.ceil(Number(n)||0))}
function upcoming(){const today=new Date();today.setHours(0,0,0,0);return data.events.filter(e=>e.status==='scheduled'&&new Date(e.date+'T23:59:59')>=today&&sportLeagues().some(l=>l.id===e.leagueId)).sort((a,b)=>(a.date+a.time).localeCompare(b.date+b.time))}
function thisLeague(){const leagues=sportLeagues();return leagues.find(l=>l.id===selectedLeagueId)||leagues[0]||null}
function selectedEvent(){return data.events.find(e=>e.id===selectedEventId)||null}
function getTeams(){const league=thisLeague();return league?.teams?.length?league.teams:[{id:'a',name:'Team A',logo:'',color:'#dc7455'},{id:'b',name:'Team B',logo:'',color:'#55869a'}]}
function gameTeams(){const b=data.options.broadcast;return [{id:'quick-home',name:b.homeName?.trim()||'',logo:b.homeLogo||'',color:'#dc7455'},{id:'quick-away',name:b.awayName?.trim()||'',logo:b.awayLogo||'',color:'#55869a'}]}
function mark(t,small=false){return t?.logo?`<img alt="" src="${esc(t.logo)}">`:`<span>${esc(t?.name?.split(/\s+/).slice(0,2).map(w=>w[0]).join('').toUpperCase().slice(0,2)||'')}</span>`}
function brand(){return `<div class="brand"><img src="assets/img-logo-hq.png" alt="IMG official logo"><div class="brand-divider"></div><p>LIVE STUDIO<small>Broadcast director</small></p></div>`}
function header(){return `<header class="topbar">${brand()}<div class="top-actions"><button class="sport-picker-btn sport-picker-word" data-action="choose-sport" aria-label="Choose Sports. Current: ${sportName()}" title="Choose Sports · ${sportName()}"><span>Choose Sports</span>${ic('chevron',13)}</button><button class="icon-btn dark" data-go="settings" aria-label="App settings">${ic('settings',19)}</button></div></header>`}
function bottom(){const items=[['home','Home','home'],['leagues','Leagues','trophy'],['schedule','Events','calendar'],['settings','Settings','settings']];return `<nav class="bottom" aria-label="Main navigation">${items.slice(0,2).map(([id,t,i])=>`<button class="tab ${screen===id?'selected':''}" data-go="${id}" aria-label="${t}" ${screen===id?'aria-current="page"':''}>${ic(i,21)}<span>${t}</span></button>`).join('')}<div class="live-gap"></div>${items.slice(2).map(([id,t,i])=>`<button class="tab ${screen===id?'selected':''}" data-go="${id}" aria-label="${t}" ${screen===id?'aria-current="page"':''}>${ic(i,21)}<span>${t}</span></button>`).join('')}</nav><button class="fab ${data.live.active?'onair':''}" data-action="${data.live.active?'open-live':'instant-live'}" aria-label="${data.live.active?'Open live broadcast controls':'Open broadcast settings'}">${ic('radio',19)}${data.live.active?(recordingMode()?'RECORDING':'GAME'):'GO LIVE'}</button>`}
function genericTitle(title,back='home',sub=''){return `<div class="title-top"><button class="icon-btn ghost" data-go="${back}" aria-label="Back">${ic('back')}</button><div><h1>${esc(title)}</h1>${sub?`<span class="kicker">${esc(sub)}</span>`:''}</div></div>`}

function recordOnly(){return !data.options.yt&&!data.options.fb&&!data.options.tiktok}
function broadcastStartLabel(){return recordOnly()?'RECORD VIDEO':data.options.yt?'GO LIVE NOW · PRIVATE':'START BROADCAST'}
function recordingMode(){return Boolean(data.live.active&&data.live.recordOnly)}
function recordingState(){return {sport:activeSport(),options:data.options,live:data.live,teams:gameTeams(),sponsors:data.sponsors}}

function facebookFeedbackIcon(state){
 if(state==='loading')return '<span class="facebook-connection-spinner"></span>';
 if(state==='success')return ic('check-circle',18);
 if(state==='error')return ic('alert-circle',18);
 return ic('circle',17);
}
function updateFacebookFeedback(kind,message){
 fbFeedbackState=kind;fbMessage=String(message||'').slice(0,220);
 const panel=document.querySelector('[data-facebook-feedback]');
 if(panel){
  panel.className='facebook-feedback state-'+kind;
  const label=panel.querySelector('[data-facebook-setup-status]');
  if(label)label.textContent=fbMessage;
  const graphic=panel.querySelector('[data-facebook-feedback-icon]');
  if(graphic)graphic.innerHTML=facebookFeedbackIcon(kind);
 }
 const subtitle=document.querySelector('.facebook-destination-trigger .sub');
 if(subtitle)subtitle.textContent=kind==='loading'?'Connecting…':kind==='success'?'Connected · video uploading':kind==='error'?'Not connected · check details':'Not connected';
 const indicator=document.querySelector('.facebook-destination-trigger .toggle');
 if(indicator){
  const active=kind==='success'&&window.IMGFacebookLive?.status?.().phase==='sending';
  indicator.classList.toggle('active',active);
  indicator.setAttribute('aria-label',active?'Facebook connected':'Facebook not connected');
 }
}
function facebookFields(){
 if(!fbExpanded)return '';
 const f=window.IMGFacebookLive?.status?.()||{};
 const message=fbMessage||(fbFeedbackState==='success'?'Connected — video upload accepted. Check the preview in Facebook Live Producer.':
  fbConnecting?'Connecting to Facebook…':'Paste a Facebook Stream Key to check the connection.');
 return '<div class="facebook-inline-panel" role="region" aria-label="Facebook connection details">'+
 '<label class="form-group"><span class="small-label">Facebook Stream Key</span>'+
 '<input class="input" type="password" data-facebook-field="streamKey" autocomplete="off" '+
 'spellcheck="false" placeholder="Paste your Facebook Stream Key" value="'+esc(fbFields.streamKey)+'" '+
 (fbConnecting?'disabled':'')+'></label>'+
 '<div class="facebook-feedback state-'+fbFeedbackState+'" data-facebook-feedback role="status" aria-live="polite">'+
 '<span data-facebook-feedback-icon aria-hidden="true">'+facebookFeedbackIcon(fbFeedbackState)+'</span>'+
 '<span data-facebook-setup-status>'+esc(message)+'</span></div>'+
 '<p class="helper">Pasting a Stream Key automatically starts checking the connection. '+
 'A green check appears only after the video server confirms output. '+
 'Facebook Live Producer may still require Go Live to publish the broadcast.</p>'+
 '<a class="text-link" href="https://www.facebook.com/live/producer/" target="_blank" '+
 'rel="noopener noreferrer">Get Stream Key from Facebook Live Producer ↗</a></div>';
}
function youtubeDestination(){
 const state=window.IMGAccounts?.getStatus?.().youtube||"disconnected";
 const connected=state==="connected";
 const label=state==="connected"?"Connected":state==="verifying"?"Checking account…":
  state==="authorization-pending"?"Finish Google sign-in":
  state==="connecting"?"Connecting…":"Not connected";
 return '<div class="broadcast-destination" id="youtube-destination">'+
  '<button type="button" class="broadcast-destination-trigger" data-action="destination-yt" aria-expanded="'+youtubeExpanded+'" aria-controls="youtube-destination-body">'+
  '<span class="destination-name"><strong>YouTube</strong><small class="broadcast-youtube-status '+(connected?'connected':'')+'">'+esc(label)+(data.options.yt?' · Selected':'')+'</small></span>'+
  '<span class="broadcast-destination-indicator"><span class="toggle '+(connected&&data.options.yt?'active':'')+'" aria-hidden="true"></span>'+ic(youtubeExpanded?'up':'chevron',18)+'</span></button>'+
  '<div class="broadcast-destination-body" id="youtube-destination-body" '+(youtubeExpanded?'':'hidden')+'>'+
  (connected?'<p class="helper">YouTube is connected to IMG LIVE Studio.</p>'+
   '<label class="form-group"><span class="small-label">Livestream title</span><input class="input" data-quick="liveTitle" maxlength="100" autocomplete="off" placeholder="Enter livestream title" value="'+esc(data.options.broadcast.liveTitle||'')+'"></label>'+
   '<label class="form-group"><span class="small-label">Audience classification</span><select class="input" data-youtube-audience="1" aria-label="YouTube audience classification">'+
   '<option value="" '+(!youtubeAudience?'selected':'')+'>Select audience</option>'+
   '<option value="no" '+(youtubeAudience==='no'?'selected':'')+'>Not made for kids</option>'+
   '<option value="yes" '+(youtubeAudience==='yes'?'selected':'')+'>Made for kids</option></select></label>'+
   '<button class="outline-btn" type="button" data-action="youtube-remove">Remove from this broadcast</button>'+
   '<button class="text-link" type="button" data-action="youtube-disconnect">Disconnect Google account</button>':
   state==="authorization-pending"?
    '<p class="helper">Complete the Google sign-in in your browser. Return here to verify the account automatically.</p><button class="outline-btn" data-action="youtube-verify">Verify connection</button>':
    '<button class="solid-btn" data-action="youtube-connect" '+(youtubeBusy?'disabled':'')+'>Connect YouTube</button>')+
  (youtubeFeedback?'<p class="helper" role="status">'+esc(youtubeFeedback)+'</p>':'')+
  '<p class="helper">GO LIVE NOW starts a new private YouTube broadcast as soon as the camera feed connects. Private means only authorized viewers can watch. Production authorization is still pending.</p>'+
  '</div></div>';
}
function youtubeTitle(){
 const requested=String(data.options.broadcast.liveTitle||'').trim();
 if(requested)return requested.slice(0,100);
 const event=selectedEvent(),teams=gameTeams();
 const base=event?.name||[teams[0]?.name||'Home',teams[1]?.name||'Away'].join(' vs ');
 return (sportName()+' • '+base).slice(0,98);
}
async function startPrivateYouTubeFromStudio(){
 const accounts=window.IMGAccounts;
 if(!window.IMGNativeChannel?.postMessage||!accounts)
  return toast('A V2 Android app with secure YouTube access is required.');
 if(youtubeBusy)return;
 youtubeExpanded=true;
 youtubeBusy=true;youtubeFeedback='Checking YouTube…';
 try{
  const status=accounts.getStatus();
  if(status.youtube!=='connected')throw Error('Connect YouTube in Broadcast Settings.');
  const check=await accounts.checkYoutubeLiveReadiness();
  if(!check.liveApiAccessible)throw Error('YouTube Live is unavailable for this account right now.');
  // GO LIVE NOW deliberately creates a new private event configured to
  // auto-start once Android begins RTMPS. No manually scheduled draft choice.
  if(!youtubeAudience)throw Error('Select the YouTube audience classification first.');
  if(!check.canCreatePrivateDraft)
   throw Error('YouTube private live creation is not enabled on the IMG server.');
  const title=youtubeTitle();
  const draftRequestId=Array.from(crypto.getRandomValues(new Uint8Array(16)),
   x=>x.toString(16).padStart(2,'0')).join('');
  youtubeFeedback='Creating private live broadcast…';
  await accounts.createYoutubePrivateDraft({
   title,madeForKids:youtubeAudience==='yes',draftRequestId,startNow:true
  });
  const owned=draftRequestId;
  if(!/^[a-f0-9]{32}$/.test(owned))throw Error('No valid private YouTube event is available.');
  youtubeFeedback='';
  // The WebView camera may already own the same physical lens. Release its
  // MediaStream tracks before Android Camera2 prepares the native RTMPS feed.
  // This action NEVER ends an existing native foreground upload.
  window.IMGMedia?.stopCamera?.();
  const homeTeam=gameTeams()[0]?.name||'HOME',awayTeam=gameTeams()[1]?.name||'AWAY';
  // Only bounded, non-secret game labels cross the local origin-checked
  // bridge; OAuth cookies, stream URLs and keys never enter WebView JS.
  const game=encodeURIComponent(JSON.stringify({
   sport:activeSport(),home:homeTeam.slice(0,24),away:awayTeam.slice(0,24),
   graphics:normalizeGraphic(data.options.overlay),position:data.options.overlayPosition,
   size:Math.round(data.options.overlaySize),opacity:Math.round(data.options.overlayOpacity),
   width:Math.round(data.options.overlayWidth),height:Math.round(data.options.overlayHeight),
   offsetX:Math.round(data.options.overlayOffsetX),offsetY:Math.round(data.options.overlayOffsetY),
   margin:Math.round(data.options.overlaySafe),animated:data.options.overlayAnimated!==false
  }));
  window.IMGNativeChannel.postMessage('native-private-video-start:'+owned+':'+game);
 }catch(error){
  youtubeFeedback=String(error?.message||'YouTube could not prepare the private upload.').slice(0,200);
  toast(youtubeFeedback);
 }finally{
  youtubeBusy=false;
  if(screen==='setup')render(true);
 }
}

function facebookDestination(){
 const st=window.IMGFacebookLive?.status?.()||{};
 const connected=Boolean(st.phase==='sending'&&(data.options.fb||fbFeedbackState==='success'));
 const label=connected?'Connected · video upload active':fbConnecting||fbFeedbackState==='loading'?'Connecting…':
  fbFeedbackState==='error'?'Not connected · check details':'Not connected';
 return '<div class="facebook-destination">'+
 '<button type="button" class="facebook-destination-trigger setting-row" data-action="toggle-facebook-panel" '+
 'aria-expanded="'+fbExpanded+'" aria-controls="facebook-connection-panel">'+
 '<span class="main">Facebook<span class="sub">'+esc(label)+'</span></span>'+
 '<span class="toggle '+(connected?'active':'')+'" role="img" aria-label="'+
 (connected?'Facebook connected':'Facebook not connected')+'"></span>'+
 '<span class="facebook-drop-chevron" aria-hidden="true">'+ic(fbExpanded?'up':'chevron',16)+'</span></button>'+
 '<div id="facebook-connection-panel" '+(fbExpanded?'':'hidden')+'>'+facebookFields()+'</div></div>';
}
function facebookStatus(){
 if(!data.options.fb)return '';
 const f=window.IMGFacebookLive?.status?.()||{};
 return '<section class="control-section"><h3>Facebook video upload</h3><div class="facebook-live-confirmation">'+
 (f.phase==='sending'?'<span class="facebook-confirm-check" aria-label="Video connection confirmed">'+ic('check-circle',18)+'</span>':'<span class="facebook-confirm-warning">'+ic('alert-circle',18)+'</span>')+
 '<p class="helper" data-facebook-live-status>'+
 esc(f.phase==='sending'?'Sending video · Verify preview and press Go Live in Facebook Producer':f.phase==='error'?'VIDEO DISCONNECTED · '+f.detail:'Video relay: '+(f.phase||'idle'))+
 '</p></div><p class="helper">Facebook Live Producer may still require you to press Go Live to publish.</p></section>';
}
async function startFacebookBroadcast(){
 if(fbConnecting||data.live.active||!fbExpanded)return;
 clearTimeout(fbAttemptTimer);fbAttemptTimer=null;
 const fb=window.IMGFacebookLive,media=window.IMGMedia,o=data.options,l=data.live;
 fbConnecting=true;
 const currentAttempt=++fbGeneration;
 updateFacebookFeedback('loading','Checking Facebook Live connection…');
 try{
  // A short paint opportunity guarantees immediate visible feedback when
  // pasting a stream key, even if the streaming server is unavailable.
  await new Promise(resolve=>setTimeout(resolve,350));
  if(currentAttempt!==fbGeneration)return;
  if(!fb||!media?.startLiveCapture)throw Error('Facebook streaming is unavailable. Refresh IMG LIVE Studio.');
  if(o.yt||o.tiktok)throw Error('Turn off other destinations before connecting Facebook.');
  const cfg=fb.validate(fbFields);
  const available=fb.available?.();
  if(!available?.ready)throw Error('IMG streaming server is not online yet. Facebook cannot connect until we deploy it.');
  updateFacebookFeedback('loading','Connecting video to Facebook…');
  await media.openCamera({quality:o.quality});
  if(currentAttempt!==fbGeneration)return;
  await fb.start(cfg,recordingState,{quality:o.quality,orientation:o.orientation,fps:o.fps,
   microphone:o.micInputs?.some(x=>x.type==='device'&&x.enabled)});
  if(currentAttempt!==fbGeneration){fb.stop();return}
  // Only the streaming relay's successful publishing response can show green.
  updateFacebookFeedback('success','Connected! Video is uploading. Verify the preview in Facebook Live Producer.');
  await new Promise(resolve=>setTimeout(resolve,850));
  if(currentAttempt!==fbGeneration){fb.stop();return}
  if(fb.status?.().phase!=='sending')throw Error('Video relay disconnected before broadcast could begin.');
  Object.assign(l,window.IMGPickleballRules?.initial(o,0,1,[0,0])||{
   pickleballGames:[0,0],pickleballGame:1,pickleballServer:0,pickleballServeNumber:2,
   pickleballWinner:-1,pickleballMatchWinner:-1,pickleballRallies:0,pickleballRallyMessage:'Game ready'});
  l.active=true;l.recordOnly=false;l.remoteLiveId=uid();l.remoteEndedAt=0;l.lockedOrientation=o.orientation;
  l.clockRunning=false;l.timeout=false;l.slides=false;l.scores=[0,0];
  l.fouls=[0,0];l.seconds=o.quarterMins*60;l.shot=24;l.quarter=1;l.slideIndex=0;l.undo=[];
  o.fb=true;fbFields.streamKey='';fbExpanded=false;fbMessage='';fbFeedbackState='success';
  persist();screen='live';history.pushState({screen},'','#/live');render(false);
  return requestOrientation(o.orientation,true);
 }catch(e){
  if(currentAttempt!==fbGeneration)return;
  fb?.stop?.();o.fb=false;
  const message=String(e?.message||'Facebook video connection failed.');
  updateFacebookFeedback('error',message);
 }finally{
  if(currentAttempt===fbGeneration)fbConnecting=false;
 }
}

async function refreshOfflineClips(){
 if(!window.IMGMedia)return;
 offlineClips=await window.IMGMedia.listClips();if(screen==='recordings')render(true);
}
function recordings(){
 return '<main class="page recordings-page" data-scroll="main"><button class="recordings-back" data-go="home">← Home</button><div class="row between"><div><div class="eyebrow">LOCAL DEVICE STORAGE</div><h1>Recordings</h1><p class="lead">Saved on this device, in this browser. Download videos to keep them in your phone files.</p></div><button class="icon-btn" data-action="refresh-recordings" aria-label="Refresh recordings">'+ic('repeat',18)+'</button></div>'+
 '<div class="recordings-guide">Offline MP4 recordings include your real camera and the video scoreboard. They are not uploaded to IMG. Clearing browser data can erase copies stored in this page.</div>'+
 (offlineClips.length?offlineClips.map(c=>'<div class="offline-clip"><div class="offline-clip-info"><strong>'+esc(c.name)+'</strong><small>'+new Date(c.createdAt).toLocaleString()+' · '+Math.max(1,Math.round(c.size/1048576*10)/10)+' MB · '+(c.duration||0)+'s</small></div><div class="offline-clip-actions"><button data-action="download-recording-'+c.id+'">Download</button><button data-action="delete-recording-'+c.id+'" aria-label="Delete recording">Delete</button></div></div>').join(''):
 '<div class="recordings-empty">'+ic('video',30)+'<h3>No offline recordings yet</h3><p>Turn all broadcast destinations off, enable the camera, and tap RECORD.</p><button class="solid-btn" data-action="instant-live">Open Broadcast Settings</button></div>')+'</main>';
}
async function startOfflineRecording(){
 const media=window.IMGMedia;
 if(!media?.canCamera())return toast('Camera is unavailable. Use HTTPS Chrome on a device with a camera.');
 const o=data.options,l=data.live;
 try{
  await media.openCamera({quality:o.quality});
  if(!recordOnly())return toast('Recording mode requires every broadcast destination to be off.');
  const opts={quality:o.quality,orientation:o.orientation,fps:o.fps,microphone:o.micInputs?.some(x=>x.type==='device'&&x.enabled)};
  const result=await media.startRecording(recordingState,opts);
  l.active=true;l.recordOnly=true;l.remoteLiveId=uid();l.remoteEndedAt=0;l.lockedOrientation=o.orientation;l.clockRunning=false;l.timeout=false;
  l.slides=false;l.scores=[0,0];l.fouls=[0,0];l.seconds=o.quarterMins*60;l.shot=24;l.quarter=1;l.slideIndex=0;l.undo=[];
  Object.assign(l,window.IMGPickleballRules?.initial(o,0,1,[0,0])||{
   pickleballGames:[0,0],pickleballGame:1,pickleballServer:0,pickleballServeNumber:2,pickleballWinner:-1,pickleballMatchWinner:-1,pickleballRallies:0,pickleballRallyMessage:'Game ready'});
  persist();screen='live';history.pushState({screen},'','#/live');render(false);
  requestOrientation(o.orientation,true);
  if(opts.microphone&&!result.audio)toast('Recording started without mic audio. Check microphone permission.');
 }catch(e){toast(media.readableError(e));}
}

function home(){const upcomingEvents=upcoming(),leagues=sportLeagues();return `<main class="page home-page sport-${activeSport()}" data-scroll="main"><div class="home-intro"><div class="eyebrow">YOUR BROADCAST DESK</div><h1>Good to see you.</h1><p class="lead">Ready for your next game?</p></div><div class="hero-media"><div class="hero-copy"><span>IMG LIVE STUDIO · ${sportName().toUpperCase()}</span><strong>Every game.<br>One great broadcast.</strong></div></div><button class="action-line primary" data-go="create">${ic('plus',19)}<span>Create League</span>${ic('arrow',19)}</button>${leagues.length?`<section><div class="section-heading"><h2>My Leagues</h2><button class="text-link" data-go="leagues">View all</button></div><div class="logo-rail" aria-label="Your leagues, swipe horizontally">${leagues.map(l=>`<button class="logo-item" data-league="${l.id}" aria-label="Open ${esc(l.name)}"><div class="logo-circle">${l.logo?`<img src="${esc(l.logo)}" alt="">`:`<span class="logo-initial">${esc(l.name.split(/\s+/).map(w=>w[0]).join('').slice(0,3))}</span>`}</div><span>${esc(l.name)}</span></button>`).join('')}</div></section>`:''}${leagues.length&&upcomingEvents.length?`<section><div class="section-heading"><h2>My Events</h2><button class="text-link" data-go="schedule">View schedule</button></div>${upcomingEvents.slice(0,4).map(eventRow).join('')}</section>`:''}<button class="action-line recordings-home-link" data-go="recordings">${ic('video',19)}<span>Offline Recordings</span>${ic('arrow',19)}</button><p class="offline-indicator">Choose a connected destination in Broadcast Settings to start a stream.</p></main>`}
function eventRow(e){const l=data.leagues.find(x=>x.id===e.leagueId);const h=l?.teams?.find(t=>t.id===e.home),a=l?.teams?.find(t=>t.id===e.away);const dt=new Date(e.date+'T12:00:00');return `<button class="event-row" data-event="${e.id}"><div class="date-block"><span>${Number.isNaN(dt.valueOf())?'GAME':dt.toLocaleDateString(undefined,{month:'short'}).toUpperCase()}</span>${Number.isNaN(dt.valueOf())?'–':dt.getDate()}</div><div class="grow"><div class="names">${esc(h?.name||'Home')} vs ${esc(a?.name||'Away')}</div><div class="detail">${esc(e.time||'TBD')} · ${esc(e.venue||'Venue TBD')}</div></div><span class="arrow">${ic('arrow',17)}</span></button>`}
function leagues(){const list=sportLeagues();return `<main class="page" data-scroll="main"><div class="row between" style="padding:3px 0 20px"><div><div class="eyebrow">MANAGEMENT</div><h1 style="margin:5px 0 0">My Leagues</h1></div><button class="icon-btn dark" data-go="create" aria-label="Create league">${ic('plus',20)}</button></div>${!list.length?`<div style="padding:50px 6px;text-align:center">${ic('trophy',40)}<h2 style="margin:20px 0 8px">Start your first ${sportName()} league</h2><p class="lead">Add teams, choose a format, and manage your game schedule.</p><button class="solid-btn" data-go="create" style="margin-top:24px">${ic('plus',18)} Create League</button></div>`:list.map(l=>`<button class="list-link" data-league="${l.id}"><div class="logo-circle" style="width:57px;height:57px">${l.logo?`<img src="${esc(l.logo)}" alt="">`:`<span class="logo-initial">${esc(l.name[0])}</span>`}</div><span class="main">${esc(l.name)}<span class="sub">${esc(l.season||'Season')} · ${l.teams.length} teams</span></span>${ic('arrow',19)}</button>`).join('')}</main>`}
function schedule(){const events=upcoming(),leagues=sportLeagues(),finals=data.events.filter(e=>e.status==='final'&&leagues.some(l=>l.id===e.leagueId));return `<main class="page" data-scroll="main"><div class="row between" style="padding:3px 0 20px"><div><div class="eyebrow">LEAGUE CALENDAR</div><h1 style="margin:5px 0 0">Events</h1></div>${leagues.length?`<button class="icon-btn dark" data-go="add-game">${ic('plus')}</button>`:''}</div>${events.length?`<div class="small-label">Upcoming games · ${events.length}</div>${events.map(eventRow).join('')}`:`<div style="padding:48px 5px;text-align:center"><div style="display:flex;justify-content:center;color:var(--green)">${ic('calendar',42)}</div><h2 style="margin:18px 0 9px">Nothing scheduled yet</h2><p class="lead">Create a league and add a game to see events here.</p><button class="solid-btn" data-go="${leagues.length?'add-game':'create'}" style="margin-top:25px">${ic('plus',18)} ${leagues.length?'Schedule a game':'Create League'}</button></div>`}${finals.length?`<div class="section-heading"><h2>Completed games</h2></div>${finals.map(e=>`<div class="event-row"><div class="grow"><div class="names">${esc((data.leagues.find(l=>l.id===e.leagueId)?.teams?.find(t=>t.id===e.home)?.name)||'Home')} vs ${esc((data.leagues.find(l=>l.id===e.leagueId)?.teams?.find(t=>t.id===e.away)?.name)||'Away')}</div><div class="detail">FINAL · ${e.finalScore?.join(' – ')||''}</div></div></div>`).join('')}`:''}</main>`}

/* Cloud remote scoring — separate controller on Phone 2, actual game state on Phone 1. */
const remoteApi=()=>window.IMGRemote;
let remoteJoinBusy=false,remoteScanning=false,remoteUIStamp='';
function remoteConnectionText(r){return r.connected?(r.role==='scorer'&&!r.approved?'Waiting for approval':'Connected'):r.attempt?'Reconnecting…':'Offline'}
function remoteHostPanel(){
 const r=remoteApi()?.status()||{},hosting=r.role==='host';
 return '<section class="control-section remote-host-box"><div class="row between"><h3>Remote Scoring</h3><span class="remote-status '+(r.connected?'connected':'')+'">'+(hosting?esc(remoteConnectionText(r)):'Off')+'</span></div>'+
 '<p class="helper">Let a second phone control the game without touching this camera.</p>'+
 (!hosting?'<button class="remote-main-button" data-action="remote-host-toggle">Enable remote scoring</button>':
 '<div class="remote-host-content"><div class="remote-qr" id="img-remote-qr" aria-label="QR code for second phone"></div>'+
 '<div class="remote-join-details"><span class="small-label">PAIRING CODE</span><strong class="remote-code">'+esc(r.code)+'</strong><button class="remote-minibtn" data-action="remote-copy-code">Copy code</button><button class="remote-minibtn" data-action="remote-copy-link">Copy browser link</button><p class="helper">Phone 2: Scan this QR with the built-in Camera app. It opens a remote-only controller in the browser—no IMG app needed.</p></div></div>'+
 (r.pendingId?'<div class="remote-request"><strong>Second phone waiting for approval</strong><button class="remote-main-button" data-action="remote-approve">Approve scorer</button></div>':'')+
 '<div class="row remote-action-row"><button class="remote-minibtn" data-action="remote-open-manager">Manage connection</button><button class="remote-minibtn danger" data-action="remote-host-toggle">Disable</button></div>'+
 '<small class="remote-footnote">Session expires automatically. Camera and recordings stay on this phone.</small>')+
 '</section>';
}
function remoteLiveAccess(){
 const r=remoteApi()?.status()||{};
 return '<div class="remote-live-access"><button data-action="remote-open-manager">'+ic('wifi',15)+' Remote Scoring <span class="remote-status '+(r.connected?'connected':'')+'">'+(r.role==='host'?esc(remoteConnectionText(r)):'Off')+'</span></button></div>';
}
function remoteScreen(){
 const r=remoteApi()?.status()||{},isHost=r.role==='host',isScorer=r.role==='scorer';
 if(isScorer&&r.approved&&window.IMGRemoteConsole)return window.IMGRemoteConsole.render(r);
 const st=r.state||{},scores=Array.isArray(st.scores)?st.scores:[0,0],fouls=Array.isArray(st.fouls)?st.fouls:[0,0],names=Array.isArray(st.names)?st.names:['Home','Away'],sport=st.sport||'basketball',pickle=sport==='pickleball';
 return '<main class="remote-page" data-scroll="main"><header class="remote-page-header"><button class="remote-back" data-action="remote-back">←</button><div><span class="eyebrow">IMG LIVE STUDIO</span><h1>Remote Scoring</h1></div></header>'+
 (isHost?remoteHostPanel()+
 '<p class="remote-help">Your phone is the broadcaster. Keep the camera running; the second phone only controls scores.</p>':
 !isScorer?
 '<section class="remote-join-card"><div class="remote-scan-icon">'+ic('scan',34)+'</div><h2>Join as remote scorer</h2><p>Scan the broadcaster’s QR code or enter the 10-character pairing code.</p>'+
 '<button class="remote-main-button" data-action="remote-start-scanner">'+ic('camera',18)+' Scan QR code</button>'+
 '<div class="remote-scan-area" id="remote-scan-area" hidden><video id="remote-scan-video" muted autoplay playsinline></video><button class="remote-minibtn" data-action="remote-stop-scanner">Close scanner</button></div>'+
 '<label class="remote-code-label" for="remote-code-entry">Pairing code</label><input id="remote-code-entry" class="input remote-code-input" autocomplete="off" spellcheck="false" inputmode="text" autocapitalize="characters" maxlength="12" placeholder="10-character code" aria-label="Remote scoring pairing code">'+
 '<button class="remote-secondary-button" data-action="remote-join-code" '+(remoteJoinBusy?'disabled':'')+'>'+(remoteJoinBusy?'Joining…':'Join Session')+'</button></section>':
 '<section class="remote-controller"><div class="remote-controller-status"><span class="remote-status '+(r.connected?'connected':'')+'">'+esc(remoteConnectionText(r))+'</span><span data-remote-rtt>'+(r.rtt!=null?'RTT '+r.rtt+' ms':'Cloud session')+'</span></div>'+
 (!r.approved?'<div class="remote-waiting">'+ic('shield',28)+'<h2>Waiting for broadcaster approval</h2><p>Ask Phone 1 to tap Approve scorer. This page will connect automatically.</p></div>':
 '<div class="remote-match-label">'+esc(sport==='basketball'&&st.mode==='streetball'?'Streetball':sport.charAt(0).toUpperCase()+sport.slice(1))+(pickle?' · Game '+(st.pickleballGame||1):'')+'</div>'+
 '<div class="remote-team-grid">'+[0,1].map(i=>'<section class="remote-team-card"><span class="remote-team-name">'+esc(names[i]|| (i?'Away':'Home'))+'</span><strong class="remote-score-number">'+(Number(scores[i])||0)+'</strong>'+
 (pickle?'<button class="remote-score-plus primary" data-action="remote-control-pickle-rally-'+i+'">WON RALLY</button><div class="remote-score-row"><button data-action="remote-control-pickle-correct-'+i+'-minus">−1</button><button data-action="remote-control-pickle-correct-'+i+'-plus">+1</button></div>':
 '<div class="remote-score-row"><button data-action="remote-score-'+i+'--1">−1</button><button class="remote-score-plus" data-action="remote-score-'+i+'-1">+1</button><button class="remote-score-plus" data-action="remote-score-'+i+'-2">+2</button>'+(st.mode==='streetball'?'':'<button class="remote-score-plus" data-action="remote-score-'+i+'-3">+3</button>')+'</div>'+
 (st.mode==='streetball'?'':'<div class="remote-foul-row"><span>Fouls '+(Number(fouls[i])||0)+'</span><button data-action="remote-foul-'+i+'--1">−</button><button data-action="remote-foul-'+i+'-1">+</button></div>'))+
 '</section>').join('')+'</div>'+
 '<div class="remote-game-tools"><button data-action="remote-control-undo">↶ Undo</button>'+
 (pickle?'<button data-action="remote-control-pickle-game-next">Next game</button>':
 '<button data-action="remote-control-toggle-clock">'+(st.clockRunning?'Pause clock':'Start clock')+'</button>'+
 (st.mode==='streetball'?'':'<button data-action="remote-control-shot-24">Shot 24</button><button data-action="remote-control-shot-14">Shot 14</button><button data-action="remote-control-quarter-next-ot">OT +</button>'))+
 '</div>')+
 (r.outbox?'<p class="remote-pending">Pending confirmation: '+r.outbox+' action(s). They will retry automatically.</p>':'')+
 (r.lastError?'<p class="remote-error">'+esc(r.lastError)+'</p>':'')+
 '<div class="remote-footer-actions"><button data-action="remote-reconnect">Reconnect</button><button class="danger" data-action="remote-leave">Leave scoring</button></div></section>')+
 '</main>';
}
function remoteGameSnapshot(){
 const l=data.live,o=data.options;
 if(l.active&&!l.remoteLiveId)l.remoteLiveId=uid();
 return {live:!!l.active,liveId:l.active?l.remoteLiveId:null,ended:!!l.remoteEndedAt,recordOnly:!!l.recordOnly,shotVisible:!!o.shotVisible,sport:activeSport(),mode:streetballActive()?'streetball':'regular',
  names:streetballActive()?[streetTeamName(0)||'Left',streetTeamName(1)||'Right']:gameTeams().map((t,i)=>t.name|| (i?'Away':'Home')),scores:[...l.scores],fouls:[...l.fouls],
  quarter:l.quarter,seconds:Math.round(l.seconds*100)/100,shot:Math.round(l.shot*100)/100,
  clockRunning:!!l.clockRunning,timeout:!!l.timeout,gameId:l.remoteLiveId||l.remoteSetupId||null,
  pickleballGame:l.pickleballGame,pickleballServer:l.pickleballServer,
  pickleballServeNumber:l.pickleballServeNumber,pickleballWinner:l.pickleballWinner,
  pickleballGames:l.pickleballGames,pickleballType:o.pickleballType,
  pickleballScoring:o.pickleballScoring,pickleballCall:window.IMGPickleballRules?.call?.(l,o)||'',
  possession:l.possession,slides:!!l.slides,playing:!!l.playing,
  streetFormat:o.streetFormat};
}
function applyRemoteScoreCommand(payload){
 if(!payload||typeof payload!=='object'||!['setup','live'].includes(screen))return false;
 // A previously queued action must never change a newly opened broadcast.
 if(!payload.gameId||payload.gameId!==(data.live.remoteLiveId||data.live.remoteSetupId||null))return false;
 if(payload.kind==='score'||payload.kind==='foul'){
  const team=Number(payload.team),delta=Number(payload.delta);
  if(![0,1].includes(team)||![-1,1,2,3].includes(delta))return false;
  if(payload.kind==='foul'&&![-1,1].includes(delta))return false;
  if(activeSport()==='pickleball')return false;
  if(streetballActive()&&payload.kind==='foul')return false;
  pushUndo();
  const a=payload.kind==='score'?data.live.scores:data.live.fouls;
  a[team]=Math.max(0,a[team]+delta);
  if(payload.kind==='score'){scoreFlashTeam=team;scoreFlashAt=Date.now()}
  persist();render(true);return true;
 }
 if(payload.kind==='set_clock'||payload.kind==='set_shot'){
  if(activeSport()==='pickleball'||streetballActive())return false;
  const secs=Number(payload.seconds);
  if(!Number.isInteger(secs)||secs<0||(payload.kind==='set_clock'?secs>5999:secs>99))return false;
  if(payload.kind==='set_clock'){data.live.seconds=secs;data.live.clockRunning=false}
  else data.live.shot=secs;
  persist();render(true);return true;
 }
 if(payload.kind==='control'&&payload.name==='end-live'){
  // The scorer explicitly confirmed stopping this exact game. Old queued requests
  // cannot end a subsequent game that reuses the same scoring session.
  const liveId=String(payload.liveId||'');
  if(screen!=='live'||!data.live.active||!liveId||liveId!==data.live.remoteLiveId)return false;
  return finishBroadcast().then(()=>true);
 }
 if(payload.kind==='control'&&typeof payload.name==='string'){
  const name=payload.name;
  const l=data.live,o=data.options,pickle=activeSport()==='pickleball',street=streetballActive();
  const common=['undo','timeout','resume-game','show-sponsors','slide-prev','slide-next','slide-play'];
  const standard=['toggle-clock','toggle-shot-visible','shot-24','shot-14','shot-minus','clock-minus-10','clock-plus-10',
   'quarter-next-ot'];
  const pb=['pickle-game-next','pickle-rally-0','pickle-rally-1',
   'pickle-correct-0-plus','pickle-correct-0-minus','pickle-correct-1-plus','pickle-correct-1-minus',
   'pickle-server-0','pickle-server-1','pickle-serve-number-1','pickle-serve-number-2'];
  if(common.includes(name)||(pickle?pb.includes(name):!street&&standard.includes(name))){
   if(name==='show-sponsors'&&!l.timeout||name==='resume-game'&&!l.timeout||
      name==='timeout'&&l.timeout)return false;
   if(name.startsWith('slide-')&&!l.slides)return false;
   action(name);return true;
  }
  if(!pickle&&!street&&name==='reset-clock'){
   l.seconds=o.quarterMins*60;l.clockRunning=false;persist();render(true);return true;
  }
  if(!pickle&&!street&&/^period-[1-5]$/.test(name)){
   l.quarter=Number(name.slice(-1));persist();render(true);return true;
  }
  if(!pickle&&!street&&/^possession-[01]$/.test(name)){
   l.possession=Number(name.slice(-1));persist();render(true);return true;
  }
  return false;
 }
 return false;
}
function remoteUpdated(){
 const r=remoteApi()?.status()||{};
 const latency=document.querySelector('[data-remote-rtt]');
 if(latency)latency.textContent=r.rtt!=null?'RTT '+r.rtt+' ms':'Cloud session';
 const stamp=[r.role,r.connected,r.approved,r.pendingId,r.revision,r.outbox,r.lastError].join('|');
 if(remoteUIStamp===stamp)return;
 remoteUIStamp=stamp;
 if(screen==='remote'||document.querySelector('.remote-host-box')||document.querySelector('.remote-live-access')){
  // Never redraw forms while typing on the camera device.
  if(!document.activeElement?.matches?.('input,textarea,[contenteditable=true]'))render(true);
 }
}

function settings(){const o=data.options;return `<main class="page settings-page" data-scroll="main"><div class="eyebrow">PREFERENCES</div><h1>Settings</h1><p class="lead">Your studio preferences</p>
<div class="appearance-inline" role="group" aria-label="App appearance"><span class="appearance-switch-label ${o.appearance==='light'?'selected':''}">Light</span><button type="button" class="appearance-toggle ${o.appearance==='dark'?'active':''}" data-action="appearance-toggle" role="switch" aria-checked="${o.appearance==='dark'}" aria-label="Dark mode"><span class="appearance-toggle-thumb" aria-hidden="true"></span></button><span class="appearance-switch-label ${o.appearance==='dark'?'selected':''}">Dark</span></div>
<div class="divider"></div><button class="list-link remote-settings-entry" data-action="remote-join"><span class="main">Remote scoring<span class="sub">QR remote access and permissions</span></span>${ic('arrow',18)}</button>
<div class="divider"></div><button class="list-link" data-action="studio-reset"><span class="main">Reset studio data<span class="sub">Erase locally saved games, leagues and settings</span></span>${ic('trash',18)}</button>
<p class="settings-compact-note">YouTube, microphones, camera, quality, and broadcast graphics are configured in Broadcast Settings.</p></main>`}

function newDraft(){return {sport:activeSport(),name:'',season:'2026–2027',logo:'',format:'single',teams:[{id:uid(),name:'',color:'#d36e4d',logo:''},{id:uid(),name:'',color:'#598fa4',logo:''}],quarter:10,penalty:5}}
function createPage(){const d=draft||(draft=newDraft());const labels=['League info','Teams & logos','Competition format','Review'];return `<main class="page create-wizard-page" data-scroll="main">${genericTitle('Create League','leagues',`Step ${createStep+1} of 4 · ${labels[createStep]}`)}<div class="stepper" aria-label="Setup progress">${labels.map((x,i)=>`<span class="step-dot ${i<=createStep?'done':''}"></span>`).join('')}</div>${createStep===0?`<div class="eyebrow">01 · ABOUT YOUR LEAGUE</div><div class="form-group"><label for="league-name">League name</label><input id="league-name" class="input" placeholder="e.g. ${d.sport==='pickleball'?'City Pickleball League':'Metro Basketball League'}" data-draft="name" value="${esc(d.name)}" maxlength="80"></div><div class="form-group"><label for="league-season">Season</label><input id="league-season" class="input" data-draft="season" value="${esc(d.season)}"></div><div class="form-group"><label>League logo</label><label class="upload">${d.logo?`<img src="${esc(d.logo)}" alt="League logo preview">`:ic('image',33)}<span>${d.logo?'Replace logo':'Upload league logo'}</span><input type="file" accept="image/*" data-upload="league" hidden></label><p class="helper">PNG or JPG. Appears in your My Leagues carousel.</p></div>`:createStep===1?`<div class="eyebrow">02 · PARTICIPATING TEAMS</div><p class="lead" style="margin:9px 0 14px">Add each team’s name and optional logo.</p><div class="team-list">${d.teams.map((t,i)=>`<div class="team-row"><div class="team-mark" style="background:${esc(t.color)}">${mark(t)}</div><div class="grow"><input class="input" style="padding:10px;min-height:39px" aria-label="Team ${i+1} name" data-team-name="${i}" placeholder="Team ${i+1} name" value="${esc(t.name)}"><label style="font-size:10px;color:var(--green);display:flex;gap:5px;margin-top:6px;cursor:pointer">${ic('upload',12)} Upload logo<input type="file" accept="image/*" data-upload="team-${i}" hidden></label></div>${d.teams.length>2?`<button class="icon-btn" data-action="team-remove-${i}" aria-label="Remove team">${ic('x',17)}</button>`:''}</div>`).join('')}</div><button class="action-line" data-action="team-add">${ic('plus',18)} <span>Add another team</span>${ic('arrow',17)}</button>`:createStep===2?`<div class="eyebrow">03 · COMPETITION FORMAT</div><p class="lead" style="margin-top:9px">Choose a tournament style. You can adjust rules later.</p>${[['single','Single round robin','Every team meets each opponent once'],['double','Double round robin','Two meetings against each opponent'],['groups','Groups + playoffs','Pool play followed by knockout rounds'],['knockout','Knockout','Single-elimination bracket']].map(([id,title,desc])=>`<button class="format-choice ${d.format===id?'active':''}" data-format="${id}"><span class="radio"></span><div><h3>${title}</h3><p>${desc}</p></div></button>`).join('')}${d.sport==='pickleball'?`<div class="pick-rule-note">Pickleball scoring is configured when you go live. Basketball quarters and foul rules do not apply.</div>`:`<div class="input-row"><div class="form-group"><label for="qmins">Quarter length</label><select class="input" id="qmins" data-draft="quarter"><option ${d.quarter==8?'selected':''} value="8">8 minutes</option><option ${d.quarter==10?'selected':''} value="10">10 minutes</option><option ${d.quarter==12?'selected':''} value="12">12 minutes</option></select></div><div class="form-group"><label for="penalty">Penalty threshold</label><select class="input" id="penalty" data-draft="penalty"><option ${d.penalty==4?'selected':''} value="4">4 fouls</option><option ${d.penalty==5?'selected':''} value="5">5 fouls</option><option ${d.penalty==6?'selected':''} value="6">6 fouls</option></select></div></div>`}`:`<div class="eyebrow">04 · ALMOST THERE</div><div class="league-head" style="margin-top:20px"><div class="logo-circle">${d.logo?`<img src="${esc(d.logo)}" alt="League logo">`:`<span class="logo-initial">${esc(d.name.slice(0,2).toUpperCase())}</span>`}</div><div><h1>${esc(d.name||'New league')}</h1><span class="lead">${esc(d.season)} · ${d.teams.length} teams</span></div></div><div class="small-label">Teams</div>${d.teams.map((t,i)=>`<div class="team-row"><div class="team-mark" style="width:35px;height:35px;border-radius:10px;background:${esc(t.color)}">${mark(t)}</div><span style="font-size:12px;font-weight:820">${esc(t.name)}</span></div>`).join('')}<p class="lead" style="margin-top:23px">Your league will be saved on this device. After creation you can schedule games and manage standings.</p>`}<div class="flow-actions">${createStep>0?`<button class="outline-btn" data-action="create-back">${ic('back',16)} Back</button>`:''}<button class="solid-btn" data-action="create-next">${createStep===3?`Create League ${ic('check',18)}`:`Continue ${ic('arrow',17)}`}</button></div></main>`}
function leagueDetail(){const l=thisLeague();if(!l)return leagues();const e=data.events.filter(x=>x.leagueId===l.id);return `<main class="page" data-scroll="main">${genericTitle('League overview','leagues','Manage competition')}<div class="league-head"><div class="logo-circle">${l.logo?`<img alt="" src="${esc(l.logo)}">`:`<span class="logo-initial">${esc(l.name.slice(0,3).toUpperCase())}</span>`}</div><div class="grow"><div class="eyebrow">${esc(sportName().toUpperCase())} LEAGUE</div><h1>${esc(l.name)}</h1><span class="lead">${esc(l.season)}</span></div></div><div class="metric-row"><div class="metric"><strong>${l.teams.length}</strong><small>Teams</small></div><div class="metric"><strong>${e.length}</strong><small>Games</small></div><div class="metric"><strong>${e.filter(x=>x.status==='final').length}</strong><small>Finals</small></div></div><div class="section-heading"><h2>Manage league</h2></div>${[['Teams & logos','Review participating teams','league-teams','users'],['Schedules','Create games, dates and venues','league-games','calendar'],['Standings & results','Ranking and final scores','standings','trophy']].map(([title,sub,id,icon])=>`<button class="list-link" data-go="${id}">${ic(icon,20)}<span class="main">${title}<span class="sub">${sub}</span></span>${ic('arrow',18)}</button>`).join('')}<button class="solid-btn" style="margin-top:28px" data-go="setup">${ic('radio',18)} SET UP BROADCAST</button></main>`}
function leagueTeams(){const l=thisLeague();return `<main class="page" data-scroll="main">${genericTitle('Teams & Logos','league-detail',l?.name||'League')}<div class="team-list">${(l?.teams||[]).map((t,i)=>`<div class="team-row"><div class="team-mark" style="background:${esc(t.color)}">${mark(t)}</div><div class="grow"><div class="team-name">${esc(t.name)}</div><div class="team-sub">Team ${i+1}</div></div><button class="icon-btn" data-action="edit-team-${t.id}" aria-label="Edit team">${ic('edit',17)}</button></div>`).join('')}</div><p class="hint" style="margin-top:21px">Team names and logos can be changed from this panel.</p></main>`}
function leagueGames(){const l=thisLeague();const all=data.events.filter(e=>e.leagueId===l?.id);return `<main class="page" data-scroll="main">${genericTitle('Game schedules','league-detail',l?.name||'League')}<button class="solid-btn" data-go="add-game">${ic('plus',18)} Schedule game</button><div class="section-heading"><h2>Games</h2></div>${all.length?all.map(eventRow).join(''):`<p class="empty-hint">No games yet. Schedule the first matchup to see it here and on Home.</p>`}</main>`}
function startNewGame(){const l=thisLeague();gameDraft={leagueId:l?.id||'',home:l?.teams?.[0]?.id||'',away:l?.teams?.[1]?.id||'',date:new Date(Date.now()+86400000).toISOString().slice(0,10),time:'18:00',venue:''};screen='add-game';history.pushState({screen},'', '#/'+screen);render(false)}
function addGame(){const l=thisLeague();if(!l)return leagues();if(!gameDraft||gameDraft.leagueId!==l.id)gameDraft={leagueId:l.id,home:l.teams[0]?.id||'',away:l.teams[1]?.id||'',date:new Date(Date.now()+86400000).toISOString().slice(0,10),time:'18:00',venue:''};const d=gameDraft;return `<main class="page schedule-game-page" data-scroll="main">${genericTitle('Schedule a game','league-detail',l.name)}<div class="eyebrow" style="margin-bottom:16px">MATCH DETAILS</div><div class="form-group"><label>Home team</label><select class="input" data-game="home">${l.teams.map(t=>`<option value="${t.id}" ${d.home===t.id?'selected':''}>${esc(t.name)}</option>`).join('')}</select></div><div class="form-group"><label>Away team</label><select class="input" data-game="away">${l.teams.map(t=>`<option value="${t.id}" ${d.away===t.id?'selected':''}>${esc(t.name)}</option>`).join('')}</select></div><div class="input-row"><div class="form-group"><label>Date</label><input class="input" type="date" data-game="date" value="${esc(d.date)}"></div><div class="form-group"><label>Start time</label><input class="input" type="time" data-game="time" value="${esc(d.time)}"></div></div><div class="form-group"><label>Venue</label><input class="input" data-game="venue" value="${esc(d.venue)}" placeholder="Venue name"></div><div class="action-banner">${ic('calendar',19)} This game will appear under My Events once it's scheduled in the future.</div><button class="solid-btn" style="margin-top:28px" data-action="save-game">${ic('check',18)} Save game schedule</button></main>`}
function standings(){const l=thisLeague();if(!l)return leagues();const finals=data.events.filter(e=>e.leagueId===l.id&&e.status==='final'&&e.finalScore);const stats=l.teams.map(t=>{let w=0,loss=0,pf=0,pa=0;finals.forEach(e=>{const home=e.home===t.id,away=e.away===t.id;if(home||away){const score=home?e.finalScore:[e.finalScore[1],e.finalScore[0]];pf+=score[0];pa+=score[1];if(score[0]>score[1])w++;else if(score[0]<score[1])loss++}});return {...t,w,loss,pf,pa}}).sort((a,b)=>b.w-a.w||b.pf-b.pa-(a.pf-a.pa));return `<main class="page" data-scroll="main">${genericTitle('Standings','league-detail',l.name)}<div class="action-banner" style="margin-bottom:18px">Rankings will update from games marked final on this device. No sports API is connected.</div><div class="table-scroll"><table class="standings"><thead><tr><th>#</th><th>TEAM</th><th>W</th><th>L</th><th>PF</th><th>PA</th><th>DIFF</th></tr></thead><tbody>${stats.map((t,i)=>`<tr><td>${i+1}</td><td>${esc(t.name)}</td><td>${t.w}</td><td>${t.loss}</td><td>${t.pf}</td><td>${t.pa}</td><td>${t.pf-t.pa}</td></tr>`).join('')}</tbody></table></div></main>`}
function setupOpts(){const o=data.options,b=o.broadcast;const ev=selectedEvent();const icons=['home','away'];return `<section class="control-section instant-intro"><span class="sport-setup-tag">${ic(activeSport(),15)} ${sportName().toUpperCase()}</span><h3>${ev?'Scheduled game':'Instant broadcast · no league needed'}</h3><p class="helper">${ev?'Game selected from your schedule. You can adjust the on-air labels below.':'Set up the teams and select where to broadcast.'}</p>${upcoming().length?`<label class="form-group"><span class="small-label">Optional scheduled game</span><select class="input" data-input="selected-event"><option value="">Instant game (no league)</option>${upcoming().map(e=>{const league=data.leagues.find(l=>l.id===e.leagueId);const home=league?.teams.find(t=>t.id===e.home),away=league?.teams.find(t=>t.id===e.away);return `<option value="${e.id}" ${selectedEventId===e.id?'selected':''}>${esc(home?.name)} vs ${esc(away?.name)} · ${datestr(e.date)}</option>`}).join('')}</select></label>`:''}</section>${activeSport()==='basketball'?`<section class="control-section basketball-broadcast-mode"><h3>Basketball broadcast mode</h3><div class="chips"><button class="chip ${!streetballActive()?'active':''}" data-action="basketball-mode-standard">Regular game</button><button class="chip ${streetballActive()?'active':''}" data-action="basketball-mode-streetball">Streetball</button></div><p class="helper">Streetball uses a custom portrait scoreboard with players on opposite sides.</p></section>`:''}${streetballActive()?streetballSettings():''}${streetballActive()?'':`<section class="control-section setup-teams-editor"><h3>Team names & logos</h3><p class="helper">Both teams are editable even without creating a league.</p>${[0,1].map(i=>{const home=i===0,key=home?'home':'away',name=home?b.homeName:b.awayName,logo=home?b.homeLogo:b.awayLogo;return `<div class="broadcast-team-edit"><div class="setup-team-heading"><span class="small-label">${home?'HOME':'AWAY'} TEAM</span>${logo?`<button class="text-link" data-action="clear-brand-${key}-logo">Remove logo</button>`:''}</div><div class="row" style="gap:12px"><label class="quick-logo-upload" title="Upload ${key} team logo">${logo?`<img alt="${key} logo" src="${esc(logo)}">`:ic('upload',21)}<input type="file" accept="image/*" data-upload="quick-${key}-logo" aria-label="Upload ${key} team logo" hidden></label><label class="grow"><span class="sr-only">${key} team name</span><input class="input" data-quick="${key}Name" maxlength="40" placeholder="${home?'Home':'Away'} team name" value="${esc(name)}"></label></div></div>`}).join('')}</section>` }<section class="control-section broadcast-destinations-section"><h3 class="broadcast-primary-heading">Destinations</h3>${youtubeDestination()}${facebookDestination()}<div class="broadcast-quick-note">TikTok broadcasting isn't available yet. Connect YouTube above or use local recording.</div></section><details class="broadcast-advanced" id="img-production-advanced" ${advancedOpen?'open':''}><summary>Production controls</summary><div class="broadcast-advanced-content">${remoteHostPanel()}<section class="control-section"><h3>League branding <span class="subtle-title">optional</span></h3><div class="broadcast-team-edit"><label class="form-group"><span class="small-label">League name (can be left blank)</span><input class="input" data-quick="leagueName" maxlength="55" placeholder="e.g. City Basketball Cup" value="${esc(b.leagueName)}"></label><div class="row" style="gap:12px;align-items:center"><label class="quick-logo-upload" title="Upload league logo">${b.leagueLogo?`<img alt="League logo" src="${esc(b.leagueLogo)}">`:ic('upload',21)}<input type="file" data-upload="quick-league-logo" accept="image/*" aria-label="Upload league logo" hidden></label><div class="grow"><div class="main">League logo</div><p class="helper">Displayed over video only when uploaded.</p></div>${b.leagueLogo?`<button class="text-link" data-action="clear-brand-league-logo">Remove</button>`:''}</div></div><div class="setting-row"><span class="main">Show league name<span class="sub">Turn off for a clean broadcast without a league title</span></span><button class="toggle ${b.showLeagueName?'active':''}" data-action="toggle-league-name" role="switch" aria-checked="${b.showLeagueName}" aria-label="Show league name"></button></div></section>${activeSport()==='pickleball'?`<section class="control-section pickle-rules-setup"><h3>Official Pickleball scoring · 2026</h3><p class="helper">USA Pickleball standard side-out scoring is the default; rally scoring is a provisional 2026 option. Tap the winning side after each rally and the app calculates the scores and serve.</p>
<div class="pickle-rule-grid">
<label><span class="small-label">Scoring system</span><select class="input" data-input="pickleballScoring"><option value="sideout" ${o.pickleballScoring==='sideout'?'selected':''}>Standard · Side-out</option><option value="rally" ${o.pickleballScoring==='rally'?'selected':''}>Rally · Provisional 2026</option></select></label>
<label><span class="small-label">Match format</span><select class="input" data-input="pickleballMatch"><option value="single" ${o.pickleballMatch==='single'?'selected':''}>One game</option><option value="best3" ${o.pickleballMatch==='best3'?'selected':''}>Best of 3</option><option value="best5" ${o.pickleballMatch==='best5'?'selected':''}>Best of 5</option></select></label>
<label><span class="small-label">Points per game</span><select class="input" data-input="pickleballTarget">${[11,15,21].map(n=>`<option value="${n}" ${Number(o.pickleballTarget)===n?'selected':''}>${n} points</option>`).join('')}</select></label>
<label><span class="small-label">Players</span><select class="input" data-input="pickleballType"><option value="doubles" ${o.pickleballType==='doubles'?'selected':''}>Doubles</option><option value="singles" ${o.pickleballType==='singles'?'selected':''}>Singles</option></select></label>
</div><p class="pickle-scoring-explainer">Win by two. Standard doubles begins at 0–0–2. In singles, one player serves per turn. Use Undo to correct the previous rally.</p>
<a class="pickle-rules-source" href="https://usapickleball.org/rules/" target="_blank" rel="noopener noreferrer">USA Pickleball official rules ↗</a></section>
<section class="control-section"><h3>Pickleball scoreboard appearance</h3>${scoreSettings()}</section>`:`<section class="control-section"><h3>${streetballActive()?'Streetball Graphics':'Broadcast Graphics'}</h3><p class="helper">${streetballActive()?'Side-by-side vertical scores, photos and names in portrait. Adjust overall size, opacity and color.':'Six broadcast styles. Customize the entire scoreboard size, opacity and color.'}</p>${scoreSettings()}${streetballActive()?'':`<div class="setting-row"><span class="main">Show shot clock<span class="sub">Hide or display shot time in video scoreboard</span></span><button class="toggle ${o.shotVisible?'active':''}" data-action="toggle-shot-visible" role="switch" aria-checked="${o.shotVisible}" aria-label="Show shot clock"></button></div>`}</section>`}${activeSport()==='pickleball'||streetballActive()?'':`<section class="control-section"><h3>Basketball foul rules</h3><div class="setting-row"><span class="main">Team penalty threshold<span class="sub">Per period · customizable</span></span><select class="input" style="width:120px;min-height:42px" data-input="penalty">${[4,5,6].map(n=>`<option value="${n}" ${o.penalty===n?'selected':''}>${n} fouls</option>`).join('')}</select></div></section>`}<section class="control-section"><h3>Video orientation</h3><p class="helper">${streetballActive()?'Streetball portrait output will be available in a future update.':'Landscape 16:9 is the default and only enabled V2 broadcast mode.'}</p></section><section class="control-section"><h3>Video quality</h3><div class="quality-group"><div><span class="small-label">Resolution</span><div class="chips">${['720p','1080p'].map(v=>`<button class="chip ${o.quality===v?'active':''}" data-set="quality:${v}">${v}</button>`).join('')}</div></div><div><span class="small-label">Frame rate</span><div class="chips">${[30,60].map(v=>`<button class="chip ${Number(o.fps)===v?'active':''}" data-set="fps:${v}">${v} FPS</button>`).join('')}</div></div></div><p class="helper">Saved preference. The native private upload currently uses a fixed encoder profile with adaptive bitrate.</p></section><section class="control-section"><h3>Microphone sources</h3><p class="helper">Enable one or several sources at once. The built-in microphone is supported. Multiple microphone mixing is not available yet.</p>${o.micInputs.map(m=>`<div class="setting-row"><span class="main">${esc(m.name)}<span class="sub">${m.type==='device'?'Built-in device microphone':'Additional microphone (not yet available)'}</span></span><div class="row" style="gap:7px"><button class="toggle ${m.enabled?'active':''}" data-action="mic-source-${m.id}" role="switch" aria-checked="${m.enabled}" aria-label="Use ${esc(m.name)}"></button>${m.type!=='device'?`<button class="icon-btn ghost" data-action="remove-mic-${m.id}" aria-label="Remove ${esc(m.name)}">${ic('x',17)}</button>`:''}</div></div>`).join('')}<button class="action-line" data-action="add-mic" disabled aria-disabled="true">${ic('plus',17)}<span>Additional microphones unavailable</span>${ic('arrow',16)}</button></section><section class="control-section" style="border-bottom:0"><div class="dropdown"><button data-action="toggle-sponsors" aria-expanded="${sponsorOpen}"><span>Sponsors & Timeout Slides <span class="chip-inline">${data.sponsors.length} slides</span></span>${ic(sponsorOpen?'up':'chevron',19)}</button>${sponsorOpen?sponsorSettings():''}</div></section></div></details>`}
function sponsorSettings(){const o=data.options;return `<div class="dropdown-body"><p class="hint">Only the camera video switches to sponsor slides. Your phone controls remain visible.</p><div class="sponsor-list">${data.sponsors.map((s,i)=>`<div class="sponsor-row"><span class="sponsor-number">${i+1}</span><span>${esc(s.name)}${s.img?' ✓':''}</span><button data-action="sponsor-up-${i}" aria-label="Move sponsor up" ${i===0?'disabled':''}>${ic('up',16)}</button><button data-action="sponsor-edit-${i}" aria-label="Edit sponsor">${ic('edit',16)}</button><button data-action="sponsor-remove-${i}" aria-label="Remove sponsor" ${data.sponsors.length<2?'disabled':''}>${ic('x',16)}</button></div>`).join('')}</div><button class="action-line" data-action="sponsor-add" style="font-size:12px">${ic('plus',16)} <span>Add sponsor slide</span>${ic('arrow',15)}</button><div class="small-label" style="margin:16px 0 8px">Slide duration</div><div class="chips">${[3,5,10,0].map(s=>`<button class="chip ${o.slideSecs===s?'active':''}" data-set="slideSecs:${s}">${s?s+' sec':'Manual'}</button>`).join('')}</div><div class="small-label" style="margin:18px 0 8px">Transition</div><div class="chips">${['fade','slide'].map(t=>`<button class="chip ${o.slideTransition===t?'active':''}" data-set="slideTransition:${t}">${t[0].toUpperCase()+t.slice(1)}</button>`).join('')}</div></div>`}

// Shared public graphic identifiers are mirrored by V2BroadcastService.
// The first 8 styles are wide and default to bottom-center. The last 4
// are compact corner graphics, with editable positions.
const SCOREBOARD_STYLES=[
 ["prime-tv","Prime TV","wide","Glass / stadium wipe"],
 ["courtside-a","Courtside A","wide","Split chrome / score takeover"],
 ["courtside-b","Courtside B","wide","Oval center dial"],
 ["arena-glass","Arena Glass","wide","Glass reflect sweep"],
 ["rivalry-plate","Rivalry Plate","wide","Team identity takeover"],
 ["center-dial","Center Dial","wide","Circular time module"],
 ["broadcast-classic","Broadcast Classic","wide","Number roll"],
 ["crown-pill","Crown Pill","wide","Gloss capsule flip"],
 ["split-box","Split Box","compact","Stacked modular boxes"],
 ["match-cube","Match Cube","compact","Vertical stats tower"],
 ["edge-box","Edge Box","compact","Corner score burst"],
 ["legacy-tv","Legacy TV","compact","Heritage stacked bars"]
];
const SCOREBOARD_IDS=SCOREBOARD_STYLES.map(x=>x[0]);
const graphicIsCompact=id=>SCOREBOARD_STYLES.some(x=>x[0]===id&&x[2]==="compact");
const normalizeGraphic=id=>SCOREBOARD_IDS.includes(id)?id:"prime-tv";
const SCOREBOARD_PALETTE=["#E66D48","#C33F4B","#225AA9","#21846D","#B88637","#6E5CAC"];
function scoreStyleName(value){const found=SCOREBOARD_STYLES.find(x=>x[0]===value);return found?found[1]:'Prime TV'}
function scoreSettings(compact=false){
 const o=data.options,isolated=streetballActive(),
 size=Math.min(115,Math.max(15,Number(isolated?o.streetOverlaySize:o.overlaySize)||60)),
 alpha=Math.min(100,Math.max(30,isolated?o.streetOverlayOpacity:o.overlayOpacity)),
 choice=isolated?o.streetOverlayColor:o.overlayColor,
 color=/^#[0-9a-fA-F]{6}$/.test(choice||'')?choice:'#2FE0B0';
 const selected=normalizeGraphic(o.overlay);
 const styleCard=([id,name,type,motion],i)=>
  '<button type="button" class="graphics-pack '+(selected===id?'active':'')+'" data-set="overlay:'+id+'" aria-pressed="'+(selected===id)+'" title="'+esc(name)+'">'+
  '<span class="graphics-art style-'+id+'"><i class="logo-unit"></i><b class="team-one"></b><strong class="score-one">86</strong><i class="time-unit">Q4<br>8:24</i><strong class="score-two">89</strong><b class="team-two"></b><i class="shine"></i></span>'+
  '<span class="graphics-copy"><strong>'+name+'</strong><small>'+((type==='compact')?'CORNER • ':'WIDE • ')+esc(motion)+'</small></span>'+
  '</button>';
 const row=(label,key,min,max,step=5)=>
  '<label class="scoreboard-adjust"><span>'+label+' <output data-graphics-output="'+key+'">'+o[key]+'</output></span>'+
  '<input type="range" min="'+min+'" max="'+max+'" step="'+step+'" value="'+Number(o[key])+'" data-graphics-range="'+key+'" aria-label="'+label+'"></label>';
 return '<div class="scoreboard-designer broadcast-graphics-designer '+(compact?'designer-dialog':'')+'">'+
 (activeSport()==='pickleball'||isolated?'<p class="helper">Independent '+(isolated?'Streetball':'Pickleball')+' graphics controls</p>':
 '<div class="graphics-header"><h3>Broadcast Graphics</h3><p>Premium broadcast packages with different layouts, motion and placement. Prime TV and Courtside A are included.</p></div>'+
 '<div class="graphics-library" role="group" aria-label="Broadcast Graphics style library">'+SCOREBOARD_STYLES.map(styleCard).join('')+'</div>'+
 '<div class="graphics-subhead"><strong>PLACEMENT</strong><span>'+(graphicIsCompact(selected)?'Compact • corner by default':'Wide • bottom center by default')+'</span></div>'+
 '<div class="graphics-positions" role="group" aria-label="Graphics position">'+
 [['bottom-center','Bottom center'],['bottom-left','Bottom left'],['bottom-right','Bottom right'],['top-left','Top left'],['top-right','Top right']].map(([id,name])=>'<button class="'+(o.overlayPosition===id?'active':'')+'" data-set="overlayPosition:'+id+'" aria-pressed="'+(o.overlayPosition===id)+'">'+name+'</button>').join('')+'</div>'+
 '<div class="graphics-geometry">'+row('Width %','overlayWidth',65,150)+row('Height %','overlayHeight',65,150)+row('Horizontal offset %','overlayOffsetX',-30,30,1)+row('Vertical offset %','overlayOffsetY',-30,30,1)+row('Safe edge margin %','overlaySafe',1,10,1)+'</div>'+
 '<label class="graphics-switch-row"><span>Animated score awards</span><input type="checkbox" data-graphics-check="overlayAnimated" '+(o.overlayAnimated?'checked':'')+'></label>')+
 '<div class="scoreboard-adjust-grid">'+
 '<label class="scoreboard-adjust"><span>Overall size <output data-score-range-output="size">'+size+'%</output></span><input type="range" min="15" max="115" step="5" value="'+size+'" data-score-range="size"></label>'+
 '<label class="scoreboard-adjust"><span>Opacity <output data-score-range-output="opacity">'+alpha+'%</output></span><input type="range" min="30" max="100" step="5" value="'+alpha+'" data-score-range="opacity"></label></div>'+
 '<div class="score-color-row"><span class="small-label">Team graphic accent</span><div class="score-color-palette">'+
 SCOREBOARD_PALETTE.map(hex=>'<button class="score-color-swatch '+(color.toLowerCase()===hex.toLowerCase()?'selected':'')+'" style="--swatch:'+hex+'" data-set="overlayColor:'+hex+'" aria-pressed="'+(color.toLowerCase()===hex.toLowerCase())+'" aria-label="Accent '+hex+'"></button>').join('')+
 '<label class="score-color-custom"><input type="color" data-score-color="1" value="'+color+'" aria-label="Custom accent color"><span>Custom</span></label></div></div>'+
 (isolated&&compact?(streetWinTargetControls()+streetPositionControls()):'')+'</div>';
}



function streetWinTarget(){
 return Math.max(1,Math.min(999,Math.round(Number(data.options.streetWinTarget)||21)));
}
function streetWinner(){
 const target=streetWinTarget(),a=Math.max(0,Number(data.live.scores?.[0])||0),b=Math.max(0,Number(data.live.scores?.[1])||0);
 if((a<target&&b<target)||a===b)return -1;
 return a>b?0:1;
}
function streetWinTargetControls(){
 const target=streetWinTarget();
 return '<section class="street-win-settings" aria-label="Streetball winning score">'+
 '<div class="street-win-header"><span class="street-win-icon">'+ic('trophy',19)+'</span><div><strong>Points to win</strong><small>First to reach the target wins</small></div><span class="street-win-current">'+target+' pts</span></div>'+
 '<div class="street-win-presets">'+[11,15,21,25].map(n=>'<button type="button" data-action="street-target-'+n+'" class="'+(target===n?'active':'')+'" aria-pressed="'+(target===n)+'">'+n+'</button>').join('')+'</div>'+
 '<label class="street-win-custom"><span>Custom winning score</span><input class="input" type="number" min="1" max="999" step="1" inputmode="numeric" data-street-target-custom="1" value="'+target+'" aria-label="Custom points needed to win"></label>'+
 '<p class="street-win-hint">Shown in the preview and MP4. The winner is highlighted at the target, and recording continues until stopped.</p></section>';
}
function streetPositionControls(){
 const positions=data.options.streetPositions||[{x:.14,y:.52},{x:.86,y:.52}];
 const y=Math.round((positions[0]?.y??.52)*100);
 const edge=Math.round(Math.max(.10,Math.min(.24,Number(positions[0]?.x)||.14))*100);
 return '<section class="street-position-section" aria-label="Streetball mirrored scoreboard position">'+
 '<div class="street-position-heading"><div><strong>'+ic('sliders',17)+' Position on video</strong><p>Move both score graphics together, including player portraits and names.</p></div></div>'+
 '<label class="street-position-shared"><span>Both sides · Up / down <output data-street-vertical-output="1">'+y+'%</output></span><input type="range" min="23" max="70" step="1" data-street-vertical="1" value="'+y+'" aria-label="Move both Streetball overlays up or down"></label>'+
 '<label class="street-position-shared"><span>Both sides · Toward the edges <output data-street-horizontal-output="1">'+edge+'%</output></span><input type="range" min="10" max="24" step="1" data-street-horizontal="1" value="'+edge+'" aria-label="Move both overlays symmetrically horizontally"></label>'+
 '<p class="street-position-hint">10% moves both scores closer to their outer edges, while 24% moves both slightly inward. They stay mirrored and inside the video.</p>'+
 '<button class="street-position-reset-all" data-action="street-position-reset-all">'+ic('repeat',14)+' Reset both positions</button></section>';
}
const streetballActive=()=>activeSport()==='basketball'&&data.options.basketballMode==='streetball';
const streetPlayerCount=()=>data.options.streetFormat==='1v1'?1:data.options.streetFormat==='2v2'?2:0;
const streetTeamName=(side)=>data.options.broadcast[side===0?'homeName':'awayName']?.trim()||'';

async function streetPhotoData(file){
 const canvas=document.createElement('canvas');canvas.width=192;canvas.height=192;
 const ctx=canvas.getContext('2d');if(!ctx)throw Error('Photo canvas unavailable');
 let img,finish=()=>{};
 if(typeof createImageBitmap==='function'){img=await createImageBitmap(file);finish=()=>img.close?.();}
 else{
  const url=URL.createObjectURL(file);
  img=await new Promise((resolve,reject)=>{const picture=new Image();picture.onload=()=>resolve(picture);picture.onerror=reject;picture.src=url;});
  finish=()=>URL.revokeObjectURL(url);
 }
 try{
  const width=img.width||img.naturalWidth,height=img.height||img.naturalHeight,side=Math.min(width,height);
  if(!side)throw Error('Empty photo');
  ctx.drawImage(img,(width-side)/2,(height-side)/2,side,side,0,0,192,192);
  return canvas.toDataURL('image/jpeg',.76);
 }finally{finish()}
}

function streetballSettings(){
 const o=data.options,b=o.broadcast,count=streetPlayerCount();
 return '<section class="control-section street-config">'+
 '<div class="row between"><h3>Streetball game rules</h3><span class="street-tag">PORTRAIT · 9:16</span></div>'+
 '<p class="helper">Choose the game format, then set the points needed to win.</p>'+
 '<div class="street-format-pick">'+['1v1','2v2','3v3'].map(k=>'<button class="street-format-btn '+(o.streetFormat===k?'active':'')+'" data-action="street-format-'+k+'" aria-pressed="'+(o.streetFormat===k)+'">'+k.replace('v',' vs ')+'</button>').join('')+'</div>'+
 streetWinTargetControls()+
 '</section>'+
 '<section class="control-section street-roster-config"><h3>Players &amp; team names</h3>'+
 '<p class="helper">In 2v2 the two player photos on each side stack vertically. In 3v3 only team names appear.</p>'+
 (count?'<div class="setting-row street-show-team-row"><span class="main">Show team names <span class="sub">Optional for 1v1 and 2v2</span></span><button class="toggle '+(o.streetShowTeams?'active':'')+'" data-action="street-toggle-teams" role="switch" aria-checked="'+o.streetShowTeams+'"></button></div>':'<p class="street-3v3-hint">3v3: team names only, without individual player portraits.</p>')+
 (!count||o.streetShowTeams?'<div class="street-side-labels">'+[0,1].map(i=>'<label><span class="small-label">'+(i===0?'LEFT':'RIGHT')+' TEAM NAME'+(count?' · OPTIONAL':'')+'</span><input class="input" data-quick="'+(i===0?'homeName':'awayName')+'" maxlength="36" value="'+esc(streetTeamName(i))+'" placeholder="Team '+(i===0?'A':'B')+'"></label>').join('')+'</div>':'')+
 (count?'<div class="street-rosters">'+[0,1].map(i=>'<div class="street-roster-side"><h4>'+(i===0?'LEFT SIDE':'RIGHT SIDE')+'</h4><div class="street-player-grid">'+Array.from({length:count},(_,j)=>{const p=o.streetPlayers[i][j];return '<div class="street-player-editor"><label class="street-avatar-upload" title="Upload player photo">'+(p.photo?'<img alt="Player portrait" src="'+esc(p.photo)+'">':ic('upload',22))+'<input type="file" accept="image/*" data-upload="street-'+i+'-'+j+'" aria-label="Upload '+(i===0?'left':'right')+' player '+(j+1)+' photo" hidden></label><label class="street-player-label"><span class="small-label">PLAYER '+(j+1)+'</span><input class="input" maxlength="32" data-street-player="'+i+':'+j+'" value="'+esc(p.name)+'" placeholder="Player name"></label></div>'}).join('')+'</div></div>').join('')+'</div>':'')+
 '</section>'+ 
 '<section class="control-section street-placement-config"><h3>Score positioning</h3>'+ 
 streetPositionControls()+'</section>';
}
function streetballScoreboard(){
 const o=data.options,l=data.live,count=streetPlayerCount();
 const settings='--street-size:'+(Math.max(.32,Math.min(1.45,(Number(o.streetOverlaySize)||45)/60)))+';--street-alpha:'+(Math.max(30,Math.min(100,Number(o.streetOverlayOpacity)||100))/100)+';--street-accent:'+(/^#[0-9a-fA-F]{6}$/.test(o.streetOverlayColor||'')?o.streetOverlayColor:'#E66D48');
 return '<div class="streetball-score-overlay street-'+o.streetFormat+'" style="'+settings+'">'+
 [0,1].map(i=>'<div class="street-side street-side-'+(i===0?'left':'right')+'"><strong class="street-score '+(scoreFlashTeam===i&&Date.now()-scoreFlashAt<1000?'score-updated':'')+'">'+(l.scores[i]||0)+'</strong>'+
 (count&&o.streetShowTeams||!count?'<span class="street-team-label">'+esc(streetTeamName(i))+'</span>':'')+
 (count?'<div class="street-players">'+o.streetPlayers[i].slice(0,count).map((p,j)=>'<div class="street-player-onair"><div class="street-face">'+(p.photo?'<img src="'+esc(p.photo)+'" alt="">':ic('user',20))+'</div><span class="street-player-name" data-street-name="'+i+':'+j+'">'+esc(p.name||'Player')+'</span></div>').join('')+'</div>':'')+'</div>').join('')+
 '</div>';
}
function streetballLiveControls(){
 const o=data.options,l=data.live,count=streetPlayerCount();
 return '<div class="street-live-panel"><div class="street-live-head"><span class="street-tag">STREETBALL · '+o.streetFormat.toUpperCase()+'</span><button class="text-link" data-action="edit-scoreboard">Size · Opacity · Color</button></div>'+
 '<div class="street-score-controls">'+[0,1].map(i=>'<div class="street-live-team"><span>'+(count?esc(o.streetPlayers[i][0].name||'Player'):esc(streetTeamName(i)||(i?'RIGHT':'LEFT')))+'</span><strong class="street-live-score">'+(l.scores[i]||0)+'</strong><div class="street-point-buttons"><button data-score="'+i+':-1" aria-label="Subtract 1 point">−1</button><button data-score="'+i+':1">+1</button><button data-score="'+i+':2">+2</button></div></div>').join('')+'</div>'+
 '<div class="street-live-actions"><button data-action="undo" '+(!l.undo.length?'disabled':'')+'>Undo</button><button data-action="timeout" '+(l.timeout?'disabled':'')+'>TIMEOUT</button>'+
 (l.timeout?'<button data-action="show-sponsors" '+(l.slides?'disabled':'')+'>Sponsors</button><button data-action="resume-game">Resume</button>':'')+
 (l.slides?'<button data-action="slide-prev">◀</button><button data-action="slide-next">▶</button><button data-action="slide-play">'+(l.playing?'Pause':'Play')+'</button>':'')+'</div>'+
 '<p class="street-control-helper">Score changes appear immediately on both sides of the portrait camera preview.</p></div>';
}

function pickleballScorebug(){
 const l=data.live,o=data.options,t=gameTeams();
 const scale=Math.max(15,Math.min(115,Number(o.overlaySize)||45))/100;
 const opacity=Math.max(30,Math.min(100,o.overlayOpacity==null?100:Number(o.overlayOpacity)))/100;
 const accent=/^#[0-9a-fA-F]{6}$/.test(o.overlayColor||'')?o.overlayColor:'#E66D48';
 return '<div class="pickle-scorebug" style="--overlay-size:'+scale+';--overlay-opacity:'+opacity+';--overlay-accent:'+accent+'">'+
 t.map((team,i)=>'<div class="pickle-score-team '+(l.pickleballServer===i?'serving':'')+'"><div class="pickle-name"><span class="pickle-team-icon">'+(team.logo?'<img src="'+esc(team.logo)+'" alt="">':esc(team.name?.charAt(0)||''))+'</span><span>'+esc(team.name)+'</span></div><span class="pickle-server-flag">'+(l.pickleballServer===i?'SERVE':'')+'</span><strong class="bug-score '+(scoreFlashTeam===i&&Date.now()-scoreFlashAt<1000?'score-updated':'')+'">'+l.scores[i]+'</strong></div>').join('')+
 '<div class="pickle-score-meta"><span>GAME '+(l.pickleballGame||1)+'</span><span>'+esc(window.IMGPickleballRules?.call(l,o)||'0–0–2')+'</span><span>'+(l.pickleballWinner>=0?'GAME WON':(o.pickleballScoring==='rally'?'RALLY':'SIDE-OUT'))+'</span></div></div>';
}

function camera(compact=false){const o=data.options,live=data.live,ts=gameTeams(),score=live.scores,b=o.broadcast,sceneActive=live.slides;const useCompositedPreview=activeSport()==='basketball'&&!streetballActive();const showLeague=(b.leagueLogo||b.showLeagueName&&b.leagueName);const league=`${showLeague?`<div class="onair-league">${b.leagueLogo?`<img src="${esc(b.leagueLogo)}" alt="League logo">`:''}${b.showLeagueName&&b.leagueName?`<span>${esc(b.leagueName)}</span>`:''}</div>`:''}`;const scene=sceneActive?`<div class="sponsor-scene" key="sponsor-${live.slideIndex}">${data.sponsors[live.slideIndex]?.img?`<img src="${esc(data.sponsors[live.slideIndex].img)}" alt="Sponsor logo">`:''}<span>IMG LIVE STUDIO · OFFICIAL PARTNER</span><strong>${esc(data.sponsors[live.slideIndex]?.name||'SPONSOR')}</strong><small>SLIDE ${live.slideIndex+1} / ${data.sponsors.length} · VIDEO FRAME ONLY</small></div>` :activeSport()==='pickleball'?`<span class="camera-chip">${live.active?(live.recordOnly?'● REC':'● GAME SESSION'):'● PICKLEBALL CAMERA'}</span>${league}${live.timeout?'<div class="video-timeout-ribbon">TIMEOUT</div>':''}${pickleballScorebug()}`:streetballActive()?`<span class="camera-chip">${live.active?(live.recordOnly?'● REC':'● GAME SESSION'):'● STREETBALL CAMERA'}</span>`:`<span class="camera-chip">${live.active?(live.recordOnly?'● REC':o.fb&&window.IMGFacebookLive?.status?.().phase==='sending'?'● ON AIR · FACEBOOK':'● GAME SESSION'):'● CAMERA'}</span>${league}${live.timeout?'<div class="video-timeout-ribbon">TIMEOUT</div>':''}<div class="scorebug ${o.overlay} ${live.seconds<60?'clock-urgent':''} ${live.timeout?'break-state':''}" style="--overlay-size:${Math.max(15,Math.min(115,Number(o.overlaySize)||45))/100};--overlay-opacity:${Math.max(30,Math.min(100,o.overlayOpacity==null?100:Number(o.overlayOpacity)))/100};--overlay-accent:${/^#[0-9a-fA-F]{6}$/.test(o.overlayColor||'')?o.overlayColor:'#E66D48'}"><div class="score-side"><div class="bug-logo">${ts[0].logo?`<img src="${esc(ts[0].logo)}" alt="">`:esc(ts[0].name?.charAt(0)||'')}</div><div style="min-width:0"><div class="bug-name">${esc(ts[0].name.toUpperCase())}</div><div class="bug-foul">FLS ${live.fouls[0]} <span class="bug-pen">${live.fouls[0]>=o.penalty?'PENALTY':''}</span></div></div><strong class="bug-score ${scoreFlashTeam===0&&Date.now()-scoreFlashAt<1000?'score-updated':''}">${score[0]}</strong></div><div class="bug-center"><small class="bug-period">${periodLabel(live.quarter)}</small><strong class="bug-clock">${formatGameClock(live.seconds)}</strong>${o.shotVisible?`<span class="bug-shot">SHOT ${shotValue(live.shot)}</span>`:''}</div><div class="score-side away"><div class="bug-logo">${ts[1].logo?`<img src="${esc(ts[1].logo)}" alt="">`:esc(ts[1].name?.charAt(0)||'')}</div><div style="min-width:0;text-align:right"><div class="bug-name">${esc(ts[1].name.toUpperCase())}</div><div class="bug-foul">FLS ${live.fouls[1]} <span class="bug-pen">${live.fouls[1]>=o.penalty?'PENALTY':''}</span></div></div><strong class="bug-score ${scoreFlashTeam===1&&Date.now()-scoreFlashAt<1000?'score-updated':''}">${score[1]}</strong></div></div>`;return `<div class="preview-wrap ${compact?'compact-live-preview':''}">${compact?'':`<div class="preview-label"><span>${ic('camera',15)} &nbsp; ${sportName().toUpperCase()} CAMERA OUTPUT</span><small>${o.orientation==='portrait'?'9:16 portrait':'16:9 landscape'} · ${o.quality} / ${o.fps} FPS</small></div>`}<div class="camera sport-camera-${activeSport()} ${streetballActive()?'streetball-camera-preview':''} ${useCompositedPreview?'broadcast-canvas-camera':''} ${o.orientation==='portrait'?'portrait-camera':'landscape-camera'} ${sceneActive?'sponsor-on':''}" aria-label="Camera and overlay preview"><video class="camera-source" data-img-camera autoplay muted playsinline aria-label="Real camera feed"></video>${useCompositedPreview?'<canvas class="broadcast-video-frame" data-broadcast-preview aria-label="Exact outgoing Facebook and recording video preview"></canvas>':''}${scene}${streetballActive()?'<canvas class="streetball-video-overlay" data-street-overlay aria-label="Exact 9 by 16 recorded video frame preview"></canvas>':''}${!window.IMGMedia?.isCameraOn()?`<button class="camera-activate" data-action="enable-camera">${ic('camera',15)} Enable camera</button>`:''}</div></div>`}
function liveClockSection(){const l=data.live,o=data.options;return `<div class="control-section live-clock-strip"><div class="row between"><div><div class="small-label">GAME CLOCK · ${periodLabel(l.quarter)}</div><strong class="fast-clock clock-value">${formatGameClock(l.seconds)}</strong></div><div class="live-clock-actions"><button class="live-compact-button primary-clock" data-action="toggle-clock" aria-label="${l.clockRunning?'Pause':'Start'} game clock">${ic(l.clockRunning?'pause':'play',19)}</button><button class="live-compact-button" data-action="set-clock" aria-label="Set game clock manually">${ic('edit',17)}<span>Set</span></button></div></div><div class="quick-time-adjust"><button data-action="clock-minus-10">−10s</button><button data-action="clock-plus-10">+10s</button><button data-action="reset-clock">Reset</button></div><div class="row between" style="margin-top:11px"><span class="small-label">Show shot clock on video</span><button class="toggle ${o.shotVisible?'active':''}" data-action="toggle-shot-visible" role="switch" aria-checked="${o.shotVisible}" aria-label="Show shot clock"></button></div>${o.shotVisible?`<div class="row between" style="margin-top:10px"><div><span class="small-label">SHOT CLOCK</span><strong class="shot-digits">${shotValue(l.shot)}s</strong></div><div class="live-clock-actions"><button class="live-compact-button" data-action="set-shot">${ic('edit',17)} Set</button><button class="live-compact-button" data-action="shot-24">24</button><button class="live-compact-button" data-action="shot-14">14</button></div></div>`:''}</div>`}
function timeoutControls(){const l=data.live;return `<div class="timeout-action"><div class="row between"><h3>Timeout active</h3><span class="pill">Clock paused</span></div>${!l.slides?`<p class="hint">Show your sponsor slides on the camera output, while keeping your controls visible.</p><button class="solid-btn" data-action="show-sponsors" style="margin:13px 0">${ic('image',17)} SHOW SPONSORS</button>`:`<div class="row between"><div><div class="small-label">On-air sponsor</div><div style="font-size:13px;font-weight:900">${esc(data.sponsors[l.slideIndex]?.name)}</div></div><span class="pill">${l.slideIndex+1} / ${data.sponsors.length}</span></div><div class="sponsor-ops"><button data-action="slide-prev">${ic('back',15)} Prev</button><button data-action="slide-next">Next ${ic('arrow',15)}</button><button data-action="slide-play">${ic(l.playing?'pause':'play',15)} ${l.playing?'Pause slides':'Autoplay'}</button></div>`}<button class="solid-btn green" data-action="resume-game">${ic('play',17)} RESUME GAME</button></div>`}
function liveBreaksSection(){const l=data.live;return `<div class="control-section timeouts-live dense-live-card"><div class="row between"><h3>Game breaks</h3>${l.timeout?'<span class="pill">Timeout active</span>':''}</div><button class="timeout-btn" data-action="timeout" ${l.timeout?'disabled':''}>${ic('pause',18)} TIMEOUT</button>${l.timeout?timeoutControls():''}</div>`}
function liveScoresSection(){const l=data.live,o=data.options,t=gameTeams();return `<div class="control-section dense-live-card live-score-card"><div class="row between"><h3>Scores & team fouls</h3><button class="text-link" data-action="undo" ${!l.undo.length?'disabled':''}>${ic('undo',14)} Undo</button></div><div class="landscape-team-grid">${t.map((team,i)=>`<div class="team-control team-compact"><div class="row between"><span class="name">${esc(team.name)}</span><span class="big-score">${l.scores[i]}</span></div><div class="score-buttons score-buttons-4"><button data-score="${i}:-1">-1</button>${[1,2,3].map(n=>`<button data-score="${i}:${n}">+${n}</button>`).join('')}</div><div class="foul-step"><span>TEAM FOULS ${l.fouls[i]>=o.penalty?`<b class="penalty">PENALTY</b>`:''}</span><button data-foul="${i}:-1" aria-label="Remove ${esc(team.name)} foul">${ic('minus',16)}</button><strong>${l.fouls[i]}</strong><button data-foul="${i}:1" aria-label="Add ${esc(team.name)} foul">${ic('plus',16)}</button></div></div>`).join('')}</div></div>`}
function liveExtraSection(){const l=data.live,o=data.options;return `<div class="control-section dense-live-card"><h3>Period · ${periodLabel(l.quarter)}</h3><div class="chips">${[1,2,3,4,5].map(q=>`<button class="chip ${l.quarter===q?'active':''}" data-quarter="${q}">${periodLabel(q)}</button>`).join('')}<button class="chip ${l.quarter>5?'active':''}" data-action="quarter-next-ot">${l.quarter>=5?'OT +':'OT +'} </button></div></div><div class="control-section dense-live-card"><div class="row between"><h3>Scoreboard style</h3><button class="text-link" data-action="edit-scoreboard">Adjust appearance</button></div><div class="chips">${SCOREBOARD_STYLES.map(([k,n])=>`<button class="chip ${o.overlay===k?'active':''}" data-set="overlay:${k}">${n}</button>`).join('')}</div></div>`}
function liveControls(){return facebookStatus()+remoteLiveAccess()+(activeSport()==='pickleball'?pickleballLiveControls():streetballActive()?streetballLiveControls():`${liveClockSection()}${liveBreaksSection()}${liveScoresSection()}${liveExtraSection()}`)}
function operatorClock(){const l=data.live,o=data.options;return `<section class="operator-clock" aria-label="Broadcast clocks"><div class="operator-clock-main"><div class="operator-time"><span>GAME · ${periodLabel(l.quarter)}</span><strong class="clock-value">${formatGameClock(l.seconds)}</strong></div><div class="operator-time-buttons"><button class="primary" data-action="toggle-clock" aria-label="${l.clockRunning?'Pause':'Start'} game clock">${ic(l.clockRunning?'pause':'play',17)}</button><button data-action="set-clock">Set</button><button data-action="clock-minus-10">−10s</button><button data-action="clock-plus-10">+10s</button><button data-action="reset-clock">Reset</button></div></div><div class="operator-shot"><div class="operator-shot-head"><span>SHOT CLOCK ${o.shotVisible?'':'· OFF'}</span><button class="toggle ${o.shotVisible?'active':''}" data-action="toggle-shot-visible" role="switch" aria-checked="${o.shotVisible}" aria-label="Show shot clock on video"></button></div>${o.shotVisible?`<div class="operator-shot-actions"><strong class="shot-digits">${shotValue(l.shot)}s</strong><button data-action="set-shot">Set</button><button data-action="shot-24">24</button><button data-action="shot-14">14</button></div>`:''}</div><div class="operator-break">${!l.timeout?`<button class="operator-timeout" data-action="timeout">${ic('pause',14)} TIMEOUT</button>`:!l.slides?`<div class="operator-break-actions"><span class="break-indicator">TIMEOUT</span><button data-action="show-sponsors">Show sponsors</button><button class="resume" data-action="resume-game">Resume game</button></div>`:`<div class="operator-break-actions"><span class="break-indicator">SPONSOR ${l.slideIndex+1}/${data.sponsors.length}</span><button data-action="slide-prev">Prev</button><button data-action="slide-next">Next</button><button data-action="slide-play">${l.playing?'Pause':'Auto'}</button><button class="resume" data-action="resume-game">Resume</button></div>`}</div></section>`}
function operatorTeamCard(team,i){const l=data.live,o=data.options;return `<section class="operator-team" aria-label="${esc(team.name)} scoring"><div class="operator-team-head"><span class="operator-name" title="${esc(team.name)}">${esc(team.name)}</span><strong class="operator-score">${l.scores[i]}</strong></div><div class="operator-scoring"><button data-score="${i}:-1">−1</button>${[1,2,3].map(n=>`<button data-score="${i}:${n}">+${n}</button>`).join('')}</div><div class="operator-fouls"><span>FOULS ${l.fouls[i]>=o.penalty?'<b>PENALTY</b>':''}</span><button data-foul="${i}:-1" aria-label="Decrease fouls">−</button><strong>${l.fouls[i]}</strong><button data-foul="${i}:1" aria-label="Increase fouls">+</button></div></section>`}
function operatorTools(){const l=data.live,o=data.options;return `<section class="operator-tools" aria-label="Period and graphics"><div class="operator-tools-top"><span>PERIOD <strong>${periodLabel(l.quarter)}</strong></span>${o.fb?'<span class="facebook-operator-status" data-facebook-live-status>FB VIDEO · SENDING</span>':''}<button class="operator-undo" data-action="undo" ${!l.undo.length?'disabled':''}>${ic('undo',13)} Undo</button></div><div class="operator-period">${[1,2,3,4,5].map(q=>`<button class="${l.quarter===q?'active':''}" data-quarter="${q}">${periodLabel(q)}</button>`).join('')}<button class="${l.quarter>5?'active':''}" data-action="quarter-next-ot">OT+</button></div><div class="operator-styles"><span>GRAPHIC</span><select class="operator-style-select" data-input="overlay" aria-label="Scoreboard design">${SCOREBOARD_STYLES.map(([k,n])=>`<option value="${k}" ${o.overlay===k?'selected':''}>${n}</option>`).join('')}</select><button class="operator-graphic-edit" data-action="edit-scoreboard" aria-label="Customize entire scoreboard size opacity and color">Adjust</button></div><button class="operator-end" data-action="exit-live">${recordingMode()?'Stop & save':o.fb?'Stop Facebook upload':'End game'}</button></section>`}

function pickleballBreakControls(){
 const l=data.live;
 return '<div class="pickle-break">'+(!l.timeout?
 '<button class="operator-timeout" data-action="timeout">TIMEOUT</button>':
 !l.slides?'<div class="operator-break-actions"><span class="break-indicator">TIMEOUT</span><button data-action="show-sponsors">Show sponsors</button><button class="resume" data-action="resume-game">Resume game</button></div>':
 '<div class="operator-break-actions"><span class="break-indicator">SPONSOR '+(l.slideIndex+1)+'/'+data.sponsors.length+'</span><button data-action="slide-prev">Prev</button><button data-action="slide-next">Next</button><button data-action="slide-play">'+(l.playing?'Pause':'Auto')+'</button><button class="resume" data-action="resume-game">Resume</button></div>')+'</div>';
}
function pickleballRules(){return window.IMGPickleballRules}
function pickleballControlPanel(){
 const l=data.live,o=data.options,t=gameTeams(),rules=pickleballRules();
 const call=rules?.call(l,o)||'0–0–2',finished=l.pickleballWinner===0||l.pickleballWinner===1;
 const matchFinished=l.pickleballMatchWinner===0||l.pickleballMatchWinner===1;
 return '<section class="operator-clock pickleball-controls" aria-label="Official Pickleball scoring controls">'+
 '<div class="pickle-control-head"><span class="small-label">2026 USA PICKLEBALL · GAME '+(l.pickleballGame||1)+'</span><strong>'+l.scores[0]+' — '+l.scores[1]+'</strong><small>'+
 (o.pickleballScoring==='rally'?'PROVISIONAL RALLY SCORING':'STANDARD SIDE-OUT')+' · TO '+(o.pickleballTarget||11)+' · WIN BY 2</small></div>'+
 '<div class="pickle-official-call"><span>OFFICIAL SCORE CALL</span><strong>'+esc(call)+'</strong></div>'+
 '<div class="pickle-control-row"><span class="small-label">SERVING SIDE</span><div class="pickle-pair">'+
 t.map((team,i)=>'<button class="'+(l.pickleballServer===i?'selected':'')+'" data-action="pickle-server-'+i+'" aria-label="Correct serving side to '+(i===0?'Left':'Right')+'">'+esc(team.name|| (i?'Right':'Left'))+'</button>').join('')+'</div></div>'+
 (o.pickleballType==='doubles'&&o.pickleballScoring!=='rally'?'<div class="pickle-control-row"><span class="small-label">SERVER (manual correction)</span><div class="pickle-pair"><button class="'+(l.pickleballServeNumber===1?'selected':'')+'" data-action="pickle-serve-number-1">1</button><button class="'+(l.pickleballServeNumber===2?'selected':'')+'" data-action="pickle-serve-number-2">2</button></div></div>':'')+
 '<div class="pickle-rally-message '+(finished?'pickle-rally-finished':'')+'">'+
 (finished?(matchFinished?'MATCH WINNER':'GAME WINNER')+' · '+esc(t[l.pickleballWinner]?.name|| (l.pickleballWinner?'Right':'Left')):esc(l.pickleballRallyMessage||'Tap the team that won the rally.'))+'</div>'+
 (finished&&!matchFinished?'<button class="pickle-next-game" data-action="pickle-game-next">Start game '+((l.pickleballGame||1)+1)+' →</button>':'')+
 pickleballBreakControls()+'</section>';
}
function pickleballTeamCard(team,i){
 const l=data.live,finished=l.pickleballWinner===0||l.pickleballWinner===1,champ=l.pickleballWinner===i;
 const name=esc(team.name|| (i?'Right':'Left'));
 return '<section class="operator-team pickleball-team '+(champ?'pickle-game-winner':'')+'" aria-label="'+name+' scoring">'+
 '<div class="operator-team-head"><span class="operator-name" title="'+name+'">'+name+'</span><strong class="operator-score">'+l.scores[i]+'</strong></div>'+
 '<button class="pickle-won-rally" data-action="pickle-rally-'+i+'" '+(finished?'disabled':'')+'>'+
 (champ?'✓ GAME WON':'✓ WON RALLY')+'</button>'+
 '<div class="pickle-correction"><span>Score correction</span><button data-action="pickle-correct-'+i+'-minus" aria-label="Correct '+name+' score minus one">−1</button><button data-action="pickle-correct-'+i+'-plus" aria-label="Correct '+name+' score plus one">+1</button></div>'+
 '<div class="pickle-gamewins"><span>GAMES WON</span><b>'+(l.pickleballGames?.[i]||0)+'</b></div>'+
 '<div class="pickle-team-serve">'+(l.pickleballServer===i?'● SERVING':'RECEIVING')+'</div></section>';
}
function pickleballTools(){
 const l=data.live;
 return '<section class="operator-tools pickleball-tools" aria-label="Pickleball game and graphic controls">'+
 '<div class="operator-tools-top"><span>GAME <strong>'+(l.pickleballGame||1)+'</strong></span>'+
 '<button class="operator-undo" data-action="undo" '+(!l.undo.length?'disabled':'')+'>Undo rally / correction</button></div>'+
 '<p class="pickle-operator-hint">Tap WON RALLY for the side that won. Scoring, server 1/2, side-outs and win-by-two are automatic. Use correction or Undo for mistakes.</p>'+
 '<div class="operator-styles"><span>GRAPHIC</span><strong>Pickleball</strong><button class="operator-graphic-edit" data-action="edit-scoreboard">Adjust</button></div>'+
 '<button class="operator-end" data-action="exit-live">'+(recordingMode()?'Stop & save':'End game')+'</button></section>';
}
function pickleballLiveControls(){const t=gameTeams();return '<div class="pickle-mobile-controls">'+pickleballControlPanel()+pickleballTeamCard(t[0],0)+pickleballTeamCard(t[1],1)+pickleballTools()+'</div>'}
function pickleballLandscapeLayout(){const t=gameTeams();return '<div class="operator-landscape pickleball-operator"><div class="operator-preview">'+camera(true)+'</div>'+pickleballControlPanel()+'<div class="operator-bottom">'+pickleballTeamCard(t[0],0)+pickleballTeamCard(t[1],1)+pickleballTools()+'</div></div>'}

function liveLandscapeLayout(){if(activeSport()==='pickleball')return pickleballLandscapeLayout()+remoteLiveAccess();const t=gameTeams();return `<div class="operator-landscape"><div class="operator-preview">${camera(true)}</div>${operatorClock()}<div class="operator-bottom">${operatorTeamCard(t[0],0)}${operatorTeamCard(t[1],1)}${operatorTools()}</div>${remoteLiveAccess()}</div>`}
function landscapeQuickTeams(){const b=data.options.broadcast;return `<div class="setup-quick-teams"><div class="setup-quick-teams-heading">INSTANT TEAMS · EDIT NAMES / LOGOS</div>${['home','away'].map(key=>{const isHome=key==='home',value=b[key+'Name'],img=b[key+'Logo'];return `<div class="setup-quick-team"><label class="setup-team-logo" title="Upload ${key} team logo">${img?`<img alt="${key} logo" src="${esc(img)}">`:isHome?'H':'A'}<input type="file" accept="image/*" data-upload="quick-${key}-logo" aria-label="Upload ${key} team logo" hidden></label><input class="setup-team-name" data-quick="${key}Name" aria-label="${isHome?'Home':'Away'} team name" maxlength="40" value="${esc(value)}" placeholder="${isHome?'Home':'Away'} team name"></div>`}).join('')}</div>`}
function setupLandscapeLayout(){const o=data.options;return `<div class="studio-page setup-landscape-page"><div class="setup-landscape-left">${camera(true)}<div class="setup-preview-meta"><span class="setup-ready-indicator"><i></i> ${window.IMGMedia?.isCameraOn()?'CAMERA READY':'ENABLE CAMERA'}</span><span>${o.orientation==='portrait'?'9:16':'16:9'} · ${o.quality} · ${o.fps} FPS</span></div>${landscapeQuickTeams()}</div><div class="setup-landscape-right"><div class="setup-landscape-head"><div class="setup-landscape-actions"><button class="setup-back-btn" data-action="exit-setup" aria-label="Back to home">${ic('back',19)}</button>${!fbExpanded?`<button class="setup-start-btn" data-action="start-broadcast">${ic(recordOnly()?'video':'radio',15)}<span>${broadcastStartLabel()}</span></button>`:''}</div></div><div class="setup-landscape-settings" data-scroll="controls">${setupOpts()}</div></div></div>`}

function streetballGlassScreen(){
 const l=data.live,o=data.options,count=streetPlayerCount(),winner=streetWinner(),target=streetWinTarget();
 const state=l.slides?'SPONSOR':l.timeout?'TIMEOUT':l.recordOnly?'RECORDING':o.fb?(window.IMGFacebookLive?.status()?.phase==='sending'?'FACEBOOK UPLOAD':'VIDEO DISCONNECTED'):'GAME SESSION';
 return '<div class="street-glass-screen">'+
 '<div class="street-camera-stage">'+camera(true)+'</div>'+
 '<div class="street-glass-header"><div class="street-glass-status"><span class="street-status-dot"></span><span>'+state+'</span><span class="street-format-label">'+o.streetFormat.toUpperCase()+'</span></div>'+
 '<div class="street-header-actions"><button data-action="remote-open-manager" aria-label="Remote scorer connection">'+ic('wifi',15)+'</button><button data-action="edit-scoreboard" aria-label="Change Streetball scoreboard size opacity or color">'+ic('sliders',18)+'</button><button class="street-finish-btn" data-action="exit-live" title="Stop and save recording" aria-label="Stop and save recording">'+ic('square',15)+'</button></div></div>'+
 '<div class="street-glass-dock" aria-label="One-page Streetball game controls">'+
 '<div class="street-dock-title"><span>'+ic('trophy',14)+(winner>=0?' WINNER':' FIRST TO '+target)+'</span><span>'+(winner>=0?esc(count?(o.streetPlayers[winner]?.[0]?.name||'Player'):(streetTeamName(winner)||(winner?'RIGHT':'LEFT'))):o.streetFormat.toUpperCase()+' · '+(l.recordOnly?'OFFLINE REC':'GAME'))+'</span></div>'+
 '<div class="street-glass-score-row">'+[0,1].map(i=>{
 const name=count?(o.streetPlayers[i][0]?.name||'Player'):streetTeamName(i);
 return '<div class="street-glass-team '+(winner===i?'street-winner-team':'')+'"><div class="street-glass-team-meta"><span>'+esc(name|| (i?'RIGHT':'LEFT'))+'</span><strong>'+Math.max(0,l.scores[i]||0)+'</strong></div>'+
 '<div class="street-glass-scoring"><button class="street-score-minus" data-score="'+i+':-1" aria-label="Subtract one point from '+(i?'right':'left')+'">'+ic('minus',15)+'<span>−1</span></button><button class="street-score-plus" data-score="'+i+':1" aria-label="Add one point to '+(i?'right':'left')+'">'+ic('plus',15)+'<span>+1</span></button><button class="street-score-plus" data-score="'+i+':2" aria-label="Add two points to '+(i?'right':'left')+'">'+ic('plus',15)+'<span>+2</span></button></div></div>';
 }).join('')+'</div>'+
 '<div class="street-glass-actions">'+
 '<button data-action="undo" '+(!l.undo.length?'disabled':'')+'>'+ic('undo',14)+'<span>Undo</span></button>'+
 (l.timeout?
 (l.slides?
 '<button data-action="slide-prev">'+ic('back',14)+'<span>Prev</span></button><button data-action="slide-next">'+ic('arrow',14)+'<span>Next</span></button><button data-action="slide-play">'+ic(l.playing?'pause':'play',14)+'<span>'+(l.playing?'Pause':'Play')+'</span></button>':
 '<button data-action="show-sponsors">'+ic('image',14)+'<span>Sponsors</span></button>')+
 '<button class="street-glass-resume" data-action="resume-game">'+ic('play',14)+'<span>Resume</span></button>':
 '<button class="street-glass-timeout" data-action="timeout">'+ic('pause',14)+'<span>Timeout</span></button>')+
 '</div></div></div>';
}

function studio(){if(screen==='live'&&streetballActive())return streetballGlassScreen();const isLive=screen==='live';const liveLandscape=isLive&&data.options.orientation==='landscape';const setupLandscape=screen==='setup'&&useLandscapeSetup();if(liveLandscape)return `<div class="studio-page live-landscape-page">${liveLandscapeLayout()}</div>`;if(setupLandscape)return setupLandscapeLayout();return `<div class="studio-page ${isLive?'live-standard-page':''}">${!isLive?`<div class="setup-floating-exit"><button class="icon-btn" data-action="exit-setup" aria-label="Back to home">${ic('back',19)}</button></div>`:`<div class="studio-top"><div><p class="eyebrow">${isLive?(recordingMode()?'● RECORDING · OFFLINE':data.options.fb?(window.IMGFacebookLive?.status()?.phase==='sending'?'● SENDING TO FACEBOOK':'● FACEBOOK VIDEO DISCONNECTED'):'● GAME SESSION'):'PRE-BROADCAST'}</p><h1>${isLive?'Live game controls':'Broadcast settings'}</h1></div><button class="icon-btn" data-action="${isLive?'exit-live':'exit-setup'}" aria-label="${isLive?'End broadcast':'Leave setup'}">${ic('x',19)}</button></div>`}${camera()}<div class="scroll-controls safe-pad" data-scroll="controls">${isLive?liveControls():setupOpts()}</div>${!isLive&&!fbExpanded?`<div class="fixed-cta"><button class="solid-btn orange" data-action="start-broadcast">${ic(recordOnly()?'video':'radio',17)} ${broadcastStartLabel()}</button></div>`:''}</div>`}

/* Preserve phone camera and keyboard focus across UI updates.
   A full container rewrite would otherwise restart <video> and dismiss the Android IME. */
function isTypingControl(el){
 if(!el)return false;
 if(el.tagName==='TEXTAREA'||el.isContentEditable)return true;
 return el.tagName==='INPUT'&&['text','number','search','email','tel','url','password','time','date'].includes(el.type||'text');
}

/* Sync live text previews directly, without rebuilding video or dismissing keyboard. */
function updateQuickPreview(key,value,source){
 document.querySelectorAll('[data-quick="'+key+'"]').forEach(el=>{if(el!==source&&el.value!==value)el.value=value});
 if(key==='homeName'||key==='awayName'){
  const home=key==='homeName',label=String(value).trim().toUpperCase();
  const root=home?'.scorebug .score-side:not(.away) .bug-name':'.scorebug .score-side.away .bug-name';
  document.querySelectorAll(root).forEach(el=>{el.textContent=label});document.querySelectorAll(home?'.street-side-left .street-team-label':'.street-side-right .street-team-label').forEach(el=>{el.textContent=String(value).trim()});
  const pickle=home?'.pickle-scorebug .pickle-score-team:first-child .pickle-name span:last-child':'.pickle-scorebug .pickle-score-team:nth-child(2) .pickle-name span:last-child';
  document.querySelectorAll(pickle).forEach(el=>{el.textContent=String(value).trim()});
 }
 if(key==='leagueName')document.querySelectorAll('.onair-league span').forEach(el=>{el.textContent=value});
}

function currentEditor(){
 const el=document.activeElement;
 if(!isTypingControl(el))return null;
 const attr=['data-quick','data-street-player','data-draft','data-team-name','data-game','data-input','data-score-range','id'].find(k=>k==='id'?Boolean(el.id):el.hasAttribute?.(k));
 if(!attr)return null;
 const value=attr==='id'?el.id:el.getAttribute(attr);
 if(!/^[a-zA-Z0-9_-]+$/.test(value||''))return null;
 let start=null,end=null;
 try{start=el.selectionStart;end=el.selectionEnd}catch(e){}
 return {attr,value,start,end,oldValue:el.value};
}
function restoreEditor(editor){
 if(!editor)return;
 const el=editor.attr==='id'?document.getElementById(editor.value):document.querySelector('['+editor.attr+'="'+editor.value+'"]');
 if(!el||!isTypingControl(el))return;
 if(editor.oldValue!==undefined&&el.value!==editor.oldValue)el.value=editor.oldValue;
 try{el.focus({preventScroll:true})}catch(e){}
 try{if(editor.start!=null)el.setSelectionRange(editor.start,editor.end??editor.start)}catch(e){}
}

function render(keep=false){if(needsRender)return;
 applyAppearance();
 const editor=keep?currentEditor():null;
 const priorVideo=window.IMGMedia?.isCameraOn?.()?document.querySelector('video[data-img-camera]'):null;
 const holder=priorVideo&&document.createDocumentFragment?.();
 if(holder)holder.appendChild(priorVideo);
 document.body?.classList?.toggle('img-physical-portrait',!isPhysicalLandscape());
 const oldScroll=keep?Object.fromEntries([...document.querySelectorAll('[data-scroll]')].map(x=>[x.dataset.scroll,x.scrollTop])):{};const isStudio=['setup','live'].includes(screen);const showNav=['home','leagues','schedule','settings','recordings'].includes(screen);
 const remoteConsole=screen==='remote'&&remoteApi()?.status().role==='scorer'&&remoteApi()?.status().approved;
 document.documentElement.classList.toggle('img-remote-console-active',!!remoteConsole);const immersiveStreet=screen==='live'&&streetballActive();const immersiveLive=screen==='live'&&(data.options.orientation==='landscape'||immersiveStreet);const immersiveSetup=screen==='setup'&&useLandscapeSetup();const headerlessSetup=screen==='setup';const isAppLandscape=isPhysicalLandscape()&&window.innerWidth>=570&&!immersiveLive&&!immersiveSetup;let body='';switch(screen){case'home':body=home();break;case'leagues':body=leagues();break;case'schedule':body=schedule();break;case'settings':body=settings();break;case'remote':body=remoteScreen();break;case'recordings':body=recordings();break;case'create':body=createPage();break;case'league-detail':body=leagueDetail();break;case'league-teams':body=leagueTeams();break;case'league-games':body=leagueGames();break;case'add-game':body=addGame();break;case'standings':body=standings();break;case'setup':case'live':body=studio();break;default:body=home()}
 $('#app').innerHTML=`<div class="app ${keep?'stable-update':'' } ${immersiveLive?'immersive-live-app':''} ${immersiveStreet?'immersive-streetball-app':''} ${immersiveSetup?'immersive-setup-app':''} ${headerlessSetup?'headerless-setup-app':''} ${isAppLandscape?'app-wide-landscape':''} ${screen==='remote'?'remote-screen-app':''} ${remoteConsole?'remote-console-app':''}">${immersiveLive||headerlessSetup||screen==='remote'?'':header()}${isStudio?`<div class="content" style="overflow:hidden;display:flex;flex-direction:column">${body}</div>`:`<div class="content" data-scroll="root">${body}</div>`}${showNav?bottom():(immersiveLive||immersiveSetup||headerlessSetup?'':`<div class="no-nav"></div>`)}${toastText?`<div class="toast" role="status">${esc(toastText)}</div>`:''}${dialog?modal():''}</div>`;const freshVideo=document.querySelector('video[data-img-camera]');if(holder&&freshVideo){freshVideo.replaceWith(priorVideo);}window.IMGMedia?.attach?.(recordingState);if(document.getElementById('img-remote-qr'))remoteApi()?.renderQr(document.getElementById('img-remote-qr'),remoteApi()?.pairingUrl());if(keep){document.querySelectorAll('[data-scroll]').forEach(el=>{if(oldScroll[el.dataset.scroll]!=null)el.scrollTop=oldScroll[el.dataset.scroll]});restoreEditor(editor)}}

/* A new broadcast is an independent game. Keep global preferences and saved
   leagues/events, but never inherit the previous game's names, logos, rules,
   graphics, times, fouls, or stale undo history. */
function resetForNewBroadcast(){
 if(data.live.active)return false;
 window.IMGFacebookLive?.stop?.();
 clearTimeout(fbAttemptTimer);fbAttemptTimer=null;fbGeneration++;fbFields={serverUrl:'rtmps://live-api-s.facebook.com:443/rtmp/',streamKey:''};fbExpanded=false;fbMessage='';fbConnecting=false;fbFeedbackState='idle';
 const d=defaults();
 const previous=data.options;
 const fresh=d.options;
 // Preserve operator preferences and the selected verified destination
 // across games; never make the operator reconfigure all controls to start.
 fresh.sport=previous.sport;
 fresh.appearance=previous.appearance;
 fresh.appOrientation=previous.appOrientation;
 fresh.orientation=previous.orientation;
 fresh.regularOrientation=previous.regularOrientation;
 fresh.quality=previous.quality;
 fresh.fps=previous.fps;
 fresh.yt=Boolean(previous.yt);
 fresh.overlay=previous.overlay;
 fresh.overlaySize=previous.overlaySize;
 fresh.overlayOpacity=previous.overlayOpacity;
 fresh.overlayColor=previous.overlayColor;
 for(const k of ['overlayPosition','overlayOffsetX','overlayOffsetY','overlayWidth','overlayHeight','overlaySafe','overlayAnimated'])fresh[k]=previous[k];
 fresh.shotVisible=previous.shotVisible;
 fresh.mic=previous.mic;
 youtubeExpanded=Boolean(previous.yt);
 fresh.streetPlayers=fresh.streetPlayers.map(side=>side.map(player=>({...player,name:'',photo:''})));
 data.options=fresh;
 data.live={...d.live,remoteSetupId:uid()};
 scoreFlashTeam=-1;scoreFlashAt=0;focusTab='score';sponsorOpen=false;
 clearTimeout(remotePublishTimer);remoteLastPublished='';
 const ev=selectedEvent();
 if(ev){
  const league=data.leagues.find(l=>l.id===ev.leagueId);
  const home=league?.teams?.find(t=>t.id===ev.home),away=league?.teams?.find(t=>t.id===ev.away);
  Object.assign(data.options.broadcast,{
   homeName:home?.name||'',awayName:away?.name||'',
   homeLogo:home?.logo||'',awayLogo:away?.logo||'',
   leagueName:league?.name||'',leagueLogo:league?.logo||'',
   showLeagueName:!!league?.name
  });
 }
 persist();
 return true;
}

function navigate(where){if(where!=='remote'){remoteApi()?.stopScan?.();remoteScanning=false;window.IMGRemoteConsole?.exitLandscape?.()}if(where==='recordings')refreshOfflineClips();if(screen==='setup'&&where!=='setup'&&where!=='live'&&!data.live.active)window.IMGMedia?.stopCamera?.();if(data.live.active&&where!=='live'){dialog={type:'exit-live'};return render(true)}if(where==='add-game'){startNewGame();return}screen=where;if(where==='create'){draft=newDraft();createStep=0}if(where==='league-detail'&&!thisLeague()){screen='leagues'}if(where==='setup'&&screen!=='setup')resetForNewBroadcast();if(where==='setup')queueRemotePublish();history.pushState({screen},'', '#/'+screen);render(false)}
function modal(){if(!dialog)return '';const d=dialog;let body='';if(d.type==='remote-manager')body=`<h2>Remote scoring</h2>${remoteHostPanel()}<button class="outline-btn" data-action="close-modal" style="width:100%;margin-top:8px">Close</button>`;if(d.type==='sport-switch')body=`<div class="sport-switch-heading"><span class="eyebrow">IMG LIVE STUDIO</span><h2>Choose your sport</h2><p>Choose a sport to set up its leagues, events and broadcast controls.</p></div><div class="sport-choice-list">${SPORTS.map(s=>`<button class="sport-choice ${activeSport()===s.id?'active':''}" data-action="select-sport-${s.id}" aria-pressed="${activeSport()===s.id}"><span class="sport-choice-icon sport-choice-${s.id}">${ic(s.icon,28)}</span><span class="sport-choice-copy"><strong>${s.name}</strong><small>${s.description}</small></span>${activeSport()===s.id?`<span class="sport-choice-selected">${ic('check',18)}</span>`:ic('arrow',18)}</button>`).join('')}</div><p class="sport-more-coming">${ic('plus',14)} More sports coming soon</p>`;if(d.type==='exit-live')body=`<h2>${recordingMode()?'Stop and save recording?':'End game session?'}</h2><p>${recordingMode()?'The recording will stop, be saved in this browser if storage is available, and you can download the video file.':'This ends the scoring session. Stop any separate native upload from its broadcast controls.'}</p>${selectedEventId?'<p>The game result will be saved to the selected event.</p>':''}<button class="solid-btn" data-action="finish-broadcast">${ic('check',18)} ${recordingMode()?'STOP & SAVE':'End game session'}</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Keep broadcasting</button>`;if(d.type==='recording-saving')body=`<h2>Saving recording...</h2><p>Please wait while the video is finalized and stored on your device.</p>`;
if(d.type==='recording-finished')body=`<h2>${d.clip?.saved?'Recording saved offline':'Recording ready'}</h2><p>${d.clip?.saved?'This recording is saved in this browser on your device.':'Browser storage could not save the file; download it now to your phone.'} ${d.clip?.error?esc(d.clip.error):''}</p>${d.clip?.name?`<p class="recording-file-name">${esc(d.clip.name)}</p><button class="solid-btn orange" data-action="download-last-recording">${ic('video',16)} Download video</button>`:'<p>Recording data could not be created. Please try again.</p>'}${d.clip?.saved?'<button class="outline-btn" data-action="open-recordings" style="width:100%;margin-top:8px">View saved recordings</button>':''}<button class="outline-btn" data-action="close-modal" style="width:100%;margin-top:8px">Close</button>`;
if(d.type==='delete-recording')body=`<h2>Delete offline recording?</h2><p>This permanently removes the browser-local copy. Download any videos you want to keep first.</p><button class="solid-btn orange" data-action="confirm-delete-recording">Delete recording</button><button class="outline-btn" data-action="close-modal" style="width:100%;margin-top:8px">Cancel</button>`;
if(d.type==='sponsor-edit')body=`<h2>Edit sponsor</h2><p>Add a sponsor name and optional local logo.</p><div class="form-group"><label>Sponsor name</label><input id="sponsor-title" class="input" value="${esc(data.sponsors[d.i]?.name)}"></div><label class="upload">${ic('upload')} <span>Add sponsor image</span><input type="file" accept="image/*" data-upload="sponsor-${d.i}" hidden></label><button class="solid-btn" data-action="save-sponsor" style="margin-top:20px">Save sponsor</button>`;if(d.type==='team-edit')body=`<h2>Edit team</h2><p>Change this team's display name and logo.</p><div class="form-group"><label>Team name</label><input id="edit-team-name" class="input" value="${esc(thisLeague()?.teams.find(t=>t.id===d.id)?.name)}"></div><label class="upload">${ic('upload')}<span>Replace team logo</span><input type="file" accept="image/*" data-upload="saved-team-${d.id}" hidden></label><button class="solid-btn" data-action="save-team" style="margin-top:18px">Save team</button>`;if(d.type==='reset')body=`<h2>Reset studio data?</h2><p>This deletes locally saved leagues, games, and scores from this device.</p><button class="solid-btn orange" data-action="confirm-reset">Reset studio data</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Cancel</button>`;if(d.type==='reset-clock')body=`<h2>Reset the game clock?</h2><p>The game clock will pause and return to the configured quarter length. Scores and fouls stay unchanged.</p><button class="solid-btn" data-action="confirm-reset-clock">Reset clock</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Cancel</button>`;if(d.type==='set-clock')body=`<h2>Set game time</h2><p>Adjust the game clock manually. The timer pauses when you save.</p><div class="manual-time-grid"><label>Minutes<input id="manual-minutes" class="input" type="number" inputmode="numeric" min="0" max="99" value="${Math.floor(data.live.seconds/60)}"></label><label>Seconds<input id="manual-seconds" class="input" type="number" inputmode="numeric" min="0" max="59" value="${Math.floor(data.live.seconds)%60}"></label><label>Milliseconds<input id="manual-millis" class="input" type="number" inputmode="numeric" min="0" max="999" value="${Math.round(data.live.seconds*1000)%1000}"></label></div><p>Below one minute the clock displays seconds and milliseconds.</p><button class="solid-btn" data-action="confirm-set-clock">Save game time</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Cancel</button>`;if(d.type==='set-shot')body=`<h2>Set shot clock</h2><p>Manually set the shot clock time (0–99 seconds).</p><div class="form-group"><label>Seconds</label><input class="input" id="manual-shot" type="number" inputmode="numeric" min="0" max="99" value="${data.live.shot}"></div><button class="solid-btn" data-action="confirm-set-shot">Save shot clock</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Cancel</button>`;if(d.type==='overlay-customize')body=`<h2>Scoreboard appearance</h2><p>These changes affect the video graphic, not your game scores or time.</p>${scoreSettings(true)}<button class="solid-btn" data-action="close-modal">Done</button>`;if(d.type==='add-mic')body=`<h2>Add microphone source</h2><p>Multiple microphone mixing is not yet available Android app, not in Chrome.</p><div class="form-group"><label>Microphone name</label><input class="input" id="new-mic-name" maxlength="50" placeholder="e.g. Court-side USB microphone"></div><button class="solid-btn" data-action="confirm-add-mic">Add source</button><button class="outline-btn" style="width:100%;margin-top:8px" data-action="close-modal">Cancel</button>`;return `<div class="modal-backdrop" data-action="close-modal"><div class="sheet" role="dialog" aria-modal="true" aria-label="${esc(d.type)}"><div class="handle"></div>${body}</div></div>`}
function goLeague(id){selectedLeagueId=id;data.selectedLeagueId=id;selectedEventId=null;data.selectedEventId=null;persist();navigate('league-detail')}
function goEvent(id){selectedEventId=id;data.selectedEventId=id;const ev=data.events.find(e=>e.id===id);if(ev){selectedLeagueId=ev.leagueId;data.selectedLeagueId=ev.leagueId}persist();navigate('setup')}
function saveDraft(){if(!draft.name.trim()){toast('Please enter the league name.');createStep=0;render(true);return}const names=draft.teams.map(t=>t.name.trim());if(names.some(n=>!n)||new Set(names.map(n=>n.toLowerCase())).size!==names.length){toast('Each team needs a different name.');createStep=1;render(true);return}const league={id:uid(),sport:draft.sport||activeSport(),name:draft.name.trim(),season:draft.season||'New season',logo:draft.logo||'',format:draft.format,teams:draft.teams.map(t=>({...t,name:t.name.trim()})),penalty:parseInt(draft.penalty),quarter:parseInt(draft.quarter)};data.leagues.push(league);selectedLeagueId=league.id;data.selectedLeagueId=league.id;if((league.sport||'basketball')==='basketball'){data.options.penalty=league.penalty;data.options.quarterMins=league.quarter}persist();draft=null;toastText='League created successfully';navigate('league-detail');setTimeout(()=>{toastText='';render(true)},1800)}
function pushUndo(){
 const live=data.live;
 live.undo.push({scores:[...live.scores],fouls:[...live.fouls],
  pickleballServer:live.pickleballServer,pickleballServeNumber:live.pickleballServeNumber,
  pickleballGame:live.pickleballGame,pickleballGames:[...(live.pickleballGames||[0,0])],
  pickleballWinner:live.pickleballWinner,pickleballMatchWinner:live.pickleballMatchWinner,pickleballRallies:live.pickleballRallies,
  pickleballRallyMessage:live.pickleballRallyMessage});
 if(live.undo.length>40)live.undo.shift();
}
async function finishBroadcast(){const offline=recordingMode(),wasFacebook=!!data.options.fb,media=window.IMGMedia;let recording=null;
 if(wasFacebook)window.IMGFacebookLive?.stop?.();fbFields.streamKey='';
 if(offline){dialog={type:'recording-saving'};render(true);
  try{recording=await media?.stopRecording?.();}catch(e){recording={saved:false,error:media?.readableError(e)||String(e)};}
 }
 const ev=selectedEvent();if(ev){ev.status='final';ev.finalScore=[...data.live.scores]}
 data.live.active=false;data.live.recordOnly=false;if(wasFacebook)data.options.fb=false;data.live.remoteEndedAt=Date.now();data.live.remoteLiveId=null;data.live.lockedOrientation=null;data.live.clockRunning=false;data.live.slides=false;data.live.timeout=false;
 selectedEventId=null;data.selectedEventId=null;dialog=null;media?.stopCamera?.();
 try{window.screen?.orientation?.unlock?.()}catch(e){}
 window.IMGNativeChannel?.postMessage?.('img-orientation:auto');
 persist();navigate(ev?'league-detail':'home');
 if(offline){await refreshOfflineClips();dialog={type:'recording-finished',clip:recording?{name:recording.name,bytes:recording.size,saved:recording.saved,error:recording.error}:null};render(true);}
 else toast(wasFacebook?'Facebook upload stopped; end the live video in Facebook Producer.':'Game session ended'+(ev?' · result saved':''));
}

async function remoteAction(name){
 const api=remoteApi();if(!api)return toast('Remote scoring is loading. Refresh the page.');
 const current=api.status();
 try{
  if(name==='remote-join'){remoteScanning=false;return navigate('remote')}
  if(name==='remote-back'){api.stopScan();remoteScanning=false;window.IMGRemoteConsole?.exitLandscape();return navigate('settings')}
  if(name==='remote-open-manager'){api.stopScan();remoteScanning=false;if(current.role==='host'&&screen==='live'){dialog={type:'remote-manager'};return render(true)}return navigate(current.role==='host'?'setup':'remote')}
  if(name==='remote-host-toggle'){
   if(current.role==='host'){await api.revoke();api.stop();toast('Remote scoring disabled')}
   else {await api.startHost(activeSport());toast('Pairing code ready')}
   return render(true);
  }
  if(name==='remote-approve'){await api.approve();toast('Scorer approved');return render(true)}
  if(name==='remote-copy-code'){await navigator.clipboard.writeText(current.code);return toast('Pairing code copied')}
  if(name==='remote-copy-link'){const url=api.pairingUrl();if(!url)return toast('Enable Remote Scoring first.');await navigator.clipboard.writeText(url);return toast('Remote browser link copied')}
  if(name==='remote-join-code'){
   // Request fullscreen while the user tap is still a gesture (before network await).
   window.IMGRemoteConsole?.tryLandscape();
   const code=document.getElementById('remote-code-entry')?.value||'';
   remoteJoinBusy=true;await api.join(code);
   remoteJoinBusy=false;remoteScanning=false;render(true);return;
  }
  if(name==='remote-start-scanner'){
   const area=document.getElementById('remote-scan-area'),video=document.getElementById('remote-scan-video');
   if(!area||!video)return;
   area.hidden=false;remoteScanning=true;
   try{await api.scan(video,code=>{remoteScanning=false;api.join(code).then(()=>render(true)).catch(e=>toast(e.message))})}
   catch(e){area.hidden=true;remoteScanning=false;toast(e.message)}
   return;
  }
  if(name==='remote-stop-scanner'){api.stopScan();remoteScanning=false;const el=document.getElementById('remote-scan-area');if(el)el.hidden=true;return}
  if(name==='remote-landscape'){const ok=await window.IMGRemoteConsole?.tryLandscape();if(!ok)toast('Enable Auto-rotate and turn your phone sideways.');return}
  if(name==='remote-edit-clock'||name==='remote-edit-shot'){
   window.IMGRemoteConsole?.openEdit(name==='remote-edit-clock'?'clock':'shot');render(true);return;
  }
  if(name==='remote-end-live'){
   if(!current.connected||!current.state.live||!current.state.liveId)return toast('The broadcaster is not currently live or connected.');
   window.IMGRemoteConsole?.openEndConfirm();render(true);return;
  }
  if(name==='remote-cancel-end-live'){window.IMGRemoteConsole?.closeEndConfirm();render(true);return}
  if(name==='remote-confirm-end-live'){
   if(!current.connected||!current.state.live||!current.state.liveId)return toast('Cannot end a disconnected or inactive broadcast.');
   api.score({kind:'control',name:'end-live',liveId:current.state.liveId});
   window.IMGRemoteConsole?.endRequested();render(true);return;
  }
  if(name==='remote-edit-cancel'){window.IMGRemoteConsole?.closeEdit();render(true);return}
  if(name==='remote-edit-submit'){
   const command=window.IMGRemoteConsole?.editValue();
   if(command)api.score(command);
   window.IMGRemoteConsole?.closeEdit();render(true);return;
  }
  if(name==='remote-reconnect'){api.restore();toast('Reconnecting to session…');return}
  if(name==='remote-leave'){api.stopScan();api.stop();window.IMGRemoteConsole?.closeEdit();window.IMGRemoteConsole?.closeEndConfirm();window.IMGRemoteConsole?.exitLandscape();remoteScanning=false;return render(true)}
  const score=/^remote-(score|foul)-([01])-(-?[123])$/.exec(name);
  if(score){api.score({kind:score[1],team:Number(score[2]),delta:Number(score[3])});return}
  if(name.startsWith('remote-control-')){api.score({kind:'control',name:name.slice('remote-control-'.length)});return}
 }catch(e){remoteJoinBusy=false;toast(e.message||String(e));render(true)}
}

function action(name){const o=data.options,l=data.live;
if(name.startsWith('remote-'))return remoteAction(name);
if(name==='appearance-toggle'){
 o.appearance=o.appearance==='dark'?'light':'dark';
 persist();applyAppearance();return render(true);
}
if(name==='choose-sport'){dialog={type:'sport-switch'};return render(true)}
if(name.startsWith('select-sport-')){const id=name.slice('select-sport-'.length);if(!SPORTS.some(x=>x.id===id))return;if(l.active)return toast('End the current broadcast before changing sport.');o.sport=id;selectedLeagueId=null;selectedEventId=null;data.selectedLeagueId=null;data.selectedEventId=null;dialog=null;persist();navigate('home');return}
if(name.startsWith('basketball-mode-')){
 if(l.active)return toast('End recording before changing basketball modes.');
 const mode=name.slice('basketball-mode-'.length);if(!['standard','streetball'].includes(mode))return;
 if(mode==='streetball'){o.regularOrientation=o.orientation;o.orientation='portrait'}else{o.orientation=o.regularOrientation||'landscape'}o.basketballMode=mode;
 persist();render(true);if(mode==='streetball')return requestOrientation('portrait');return;
}
if(name.startsWith('street-format-')){
 if(l.active)return toast('Cannot change Streetball format during recording.');
 const format=name.slice('street-format-'.length);if(!['1v1','2v2','3v3'].includes(format))return;
 o.streetFormat=format;o.orientation='portrait';persist();return render(true);
}
if(name==='street-toggle-teams'){o.streetShowTeams=!o.streetShowTeams;persist();return render(true)}
if(name.startsWith('street-target-')){
 const value=Number(name.slice('street-target-'.length));
 if(!Number.isInteger(value)||value<1||value>999)return;
 o.streetWinTarget=value;persist();return render(true);
}
if(name==='street-position-reset-all'||name.startsWith('street-position-reset-')){
 o.streetPositions=[{x:.14,y:.52},{x:.86,y:.52}];
 persist();render(true);return;
}
if(name==='edit-scoreboard'){dialog={type:'overlay-customize'};return render(true)}
if(name==='notifications')return toast('No notifications.');
if(name==='exit-setup'){window.IMGNativeChannel?.postMessage?.('img-orientation:auto');clearTimeout(fbAttemptTimer);fbAttemptTimer=null;if(fbConnecting){fbGeneration++;window.IMGFacebookLive?.stop?.();fbConnecting=false}fbFields.streamKey='';window.IMGMedia?.stopCamera?.();return navigate('home')}
if(name==='enable-camera'){const media=window.IMGMedia;if(!media?.canCamera())return toast('Use HTTPS Chrome on your phone to enable the real camera.');return media.openCamera({quality:o.quality}).then(()=>{render(true);toast('Real camera preview enabled')}).catch(e=>toast(media.readableError(e)))}
if(name==='refresh-recordings')return refreshOfflineClips();
if(name==='download-last-recording'){try{window.IMGMedia?.downloadClip(window.IMGMedia.latestClip());}catch(e){toast(e.message)}return}
if(name.startsWith('download-recording-')){const id=name.slice('download-recording-'.length);return window.IMGMedia?.getClip(id).then(c=>{if(c)window.IMGMedia.downloadClip(c);else toast('Recording not found on this device.')}).catch(e=>toast(e.message))}
if(name.startsWith('delete-recording-')){dialog={type:'delete-recording',id:name.slice('delete-recording-'.length)};return render(true)}
if(name==='confirm-delete-recording'){const id=dialog?.id;dialog=null;return window.IMGMedia?.deleteClip(id).then(()=>refreshOfflineClips()).catch(e=>toast(e.message))}
if(name==='open-recordings'){dialog=null;return navigate('recordings')}
if(name==='exit-live'){dialog={type:'exit-live'};return render(true)}
if(name==='close-modal'){dialog=null;return render(true)}
if(name==='finish-broadcast')return finishBroadcast();
if(name==='toggle-mic'){const m=o.micInputs.find(m=>m.id==='device');if(m)m.enabled=!m.enabled;o.mic=Boolean(m?.enabled);persist();return render(true)}
if(name==='instant-live'){selectedEventId=null;data.selectedEventId=null;selectedLeagueId=null;data.selectedLeagueId=null;persist();return navigate('setup')}
if(name==='open-live')return navigate('live');
if(name==='toggle-league-name'){o.broadcast.showLeagueName=!o.broadcast.showLeagueName;persist();return render(true)}
if(name==='toggle-shot-visible'){o.shotVisible=!o.shotVisible;persist();return render(true)}
if(name.startsWith('pickle-rally-')){
 const side=Number(name.slice('pickle-rally-'.length)),rules=window.IMGPickleballRules;
 if(!rules)return toast('Pickleball scoring is loading. Refresh the page.');
 const update=rules.award(l,side,o);
 if(update.error)return toast(update.error);
 pushUndo();Object.assign(l,update.state);
 scoreFlashTeam=side;scoreFlashAt=Date.now();
 persist();return render(true);
}
if(name.startsWith('pickle-correct-')){
 const match=/^pickle-correct-([01])-(plus|minus)$/.exec(name),rules=window.IMGPickleballRules;
 if(!match||!rules)return;
 const update=rules.correct(l,Number(match[1]),match[2]==='plus'?1:-1,o);
 if(update.error)return toast(update.error);
 pushUndo();Object.assign(l,update.state);
 persist();return render(true);
}
if(name.startsWith('pickle-server-')){
 const i=Number(name.slice('pickle-server-'.length));
 if(i!==0&&i!==1||l.pickleballWinner>=0)return;
 const opening=Number(l.pickleballRallies||0)===0;
 pushUndo();l.pickleballServer=i;
 l.pickleballServeNumber=opening&&o.pickleballType==='doubles'&&o.pickleballScoring!=='rally'?2:1;
 l.pickleballRallyMessage=opening?'Starting serve selected':'Serving side corrected';
 persist();return render(true);
}
if(name.startsWith('pickle-serve-number-')){
 const n=Number(name.slice('pickle-serve-number-'.length));
 if((n!==1&&n!==2)||o.pickleballType!=='doubles'||o.pickleballScoring==='rally'||l.pickleballWinner>=0)return;
 pushUndo();l.pickleballServeNumber=n;l.pickleballRallyMessage='Server number corrected';
 persist();return render(true);
}
if(name==='pickle-game-next'){
 const rules=window.IMGPickleballRules;if(!rules)return;
 const update=rules.next(l,o);
 if(update.error)return toast(update.error);
 pushUndo();Object.assign(l,update.state);
 persist();return render(true);
}
if(name==='quarter-next-ot'){l.quarter=Math.max(5,Number(l.quarter)+1);persist();return render(true)}
if(name==='add-mic'){dialog={type:'add-mic'};return render(true)}
if(name==='confirm-add-mic'){const v=String($('#new-mic-name')?.value||'').trim();if(!v)return toast('Enter a microphone name.');o.micInputs.push({id:uid(),name:v,enabled:true,type:'external'});dialog=null;persist();return render(true)}
if(name.startsWith('mic-source-')){const m=o.micInputs.find(x=>x.id===name.slice(11));if(m)m.enabled=!m.enabled;o.mic=Boolean(o.micInputs.find(x=>x.id==='device')?.enabled);persist();return render(true)}
if(name.startsWith('remove-mic-')){o.micInputs=o.micInputs.filter(x=>x.id!==name.slice(11)||x.type==='device');persist();return render(true)}
if(name.startsWith('clear-brand-')){const key=name.slice(12).replace(/-logo$/,'');if(key==='home'||key==='away'||key==='league')o.broadcast[key+'Logo']='';persist();return render(true)}
if(name==='set-clock'){dialog={type:'set-clock'};return render(true)}
if(name==='confirm-set-clock'){const m=Number($('#manual-minutes')?.value),sec=Number($('#manual-seconds')?.value),ms=Number($('#manual-millis')?.value||0);if(!Number.isInteger(m)||!Number.isInteger(sec)||!Number.isInteger(ms)||m<0||m>99||sec<0||sec>59||ms<0||ms>999)return toast('Enter 0–99 minutes and 0–59 seconds.');l.seconds=m*60+sec+ms/1000;l.clockRunning=false;dialog=null;persist();return render(true)}
if(name==='set-shot'){dialog={type:'set-shot'};return render(true)}
if(name==='confirm-set-shot'){const secs=Number($('#manual-shot')?.value);if(!Number.isInteger(secs)||secs<0||secs>99)return toast('Enter 0–99 seconds.');l.shot=secs;dialog=null;persist();return render(true)}
if(name==='clock-minus-10'||name==='clock-plus-10'){l.seconds=Math.min(5999,Math.max(0,l.seconds+(name==='clock-minus-10'?-10:10)));persist();return render(true)}
if(name==='toggle-facebook-panel'){
 clearTimeout(fbAttemptTimer);fbAttemptTimer=null;
 if(fbConnecting){fbGeneration++;window.IMGFacebookLive?.stop?.();fbConnecting=false;fbExpanded=false;fbMessage='';fbFeedbackState='idle';return render(true)}
 fbExpanded=!fbExpanded;fbMessage='';fbFeedbackState='idle';return render(true)
}
if(name==='destination-yt'){
 // One tap selects YouTube and starts the official secure Google sign-in
 // when the account is disconnected. A connected account only expands its
 // destination settings; collapsing never silently unselects YouTube.
 if(!o.yt){o.yt=true;youtubeExpanded=true}
 else youtubeExpanded=!youtubeExpanded;
 persist();render(true);
 const accounts=window.IMGAccounts,kind=accounts?.getStatus?.().youtube;
 if(youtubeExpanded&&kind==='disconnected'){
  youtubeFeedback='Opening secure Google sign-in…';
  accounts.connect('youtube').then(()=>render(true)).catch(error=>{
   youtubeFeedback=String(error?.message||'Google sign-in unavailable.').slice(0,180);
   toast(youtubeFeedback);render(true)
  });
 }
 return;
}
if(name==='youtube-remove'){
 o.yt=false;youtubeExpanded=false;youtubeFeedback='';
 persist();return render(true)
}
if(name==='youtube-connect'){
 youtubeFeedback='Connecting securely…';
 return window.IMGAccounts?.connect('youtube').then(()=>render(true)).catch(e=>{youtubeFeedback=e.message;toast(e.message);render(true)})
}
if(name==='youtube-verify')return window.IMGAccounts?.verifyPairing('youtube').then(()=>render(true)).catch(e=>{youtubeFeedback=e.message;toast(e.message);render(true)})
if(name==='youtube-disconnect'){
 if(!confirm('Disconnect YouTube from IMG LIVE Studio?'))return;
 return window.IMGAccounts?.disconnect('youtube').then(()=>{o.yt=false;persist();render(true)}).catch(e=>toast(e.message))
}
if(name.startsWith('destination-')){const p=name.slice(12);if(p==='tiktok')return toast('TikTok broadcasting is not available yet.');if(p==='fb')return; return toast('This platform is unavailable.')}
if(name==='start-broadcast'){if(o.yt){if(o.fb)return toast('Choose one streaming destination.');return startPrivateYouTubeFromStudio()}if(o.fb&&window.IMGFacebookLive?.status?.().phase!=='sending')return toast('Connect Facebook video before starting game controls.');if(fbConnecting||fbExpanded)return;if(streetballActive())o.orientation='portrait';if(recordOnly())return startOfflineRecording();l.recordOnly=false;Object.assign(l,window.IMGPickleballRules?.initial(o,0,1,[0,0])||{
 pickleballGames:[0,0],pickleballGame:1,pickleballServer:0,pickleballServeNumber:2,pickleballWinner:-1,pickleballMatchWinner:-1,pickleballRallies:0,pickleballRallyMessage:'Game ready'});l.active=true;l.remoteLiveId=uid();l.remoteEndedAt=0;l.lockedOrientation=o.orientation;l.clockRunning=false;l.timeout=false;l.slides=false;l.scores=[0,0];l.fouls=[0,0];l.seconds=o.quarterMins*60;l.shot=24;l.quarter=1;l.slideIndex=0;l.undo=[];persist();screen='live';history.pushState({screen},'', '#/live');render(false);return requestOrientation(o.orientation,true)}
if(name==='toggle-clock'){if(l.timeout)return toast('Resume the game before starting the clock.');l.clockRunning=!l.clockRunning;persist();return render(true)}
if(name==='reset-clock'){dialog={type:'reset-clock'};return render(true)}
if(name==='confirm-reset-clock'){l.clockRunning=false;l.seconds=o.quarterMins*60;dialog=null;persist();return render(true)}
if(name==='shot-24'||name==='shot-14'||name==='shot-minus'){l.shot=name==='shot-24'?24:name==='shot-14'?14:Math.max(0,l.shot-1);persist();return render(true)}
if(name==='undo'){const prev=l.undo.pop();if(prev){
 l.scores=prev.scores;l.fouls=prev.fouls;
 if(activeSport()==='pickleball'){
  for(const key of ['pickleballServer','pickleballServeNumber','pickleballGame','pickleballWinner',
   'pickleballMatchWinner','pickleballRallyMessage','pickleballRallies'])if(key in prev)l[key]=prev[key];
  if(prev.pickleballGames)l.pickleballGames=[...prev.pickleballGames];
 }
 persist();render(true)}return}
if(name==='timeout'){l.timeout=true;l.clockRunning=false;persist();return render(true)}
if(name==='show-sponsors'){if(!data.sponsors.some(item=>item.name?.trim()||item.img))return toast('Add a real sponsor before showing sponsorship graphics.');l.slides=true;l.slideIndex=0;l.playing=o.slideSecs>0;lastSlideAt=Date.now();persist();return render(true)}
if(name==='resume-game'){l.timeout=false;l.slides=false;l.playing=false;persist();return render(true)}
if(name==='slide-prev'||name==='slide-next'){l.slideIndex=(l.slideIndex+(name==='slide-prev'?-1:1)+data.sponsors.length)%data.sponsors.length;lastSlideAt=Date.now();persist();return render(true)}
if(name==='slide-play'){l.playing=!l.playing;lastSlideAt=Date.now();persist();return render(true)}
if(name.startsWith('possession-')){l.possession=parseInt(name.slice(-1));persist();return render(true)}
if(name==='toggle-sponsors'){sponsorOpen=!sponsorOpen;return render(true)}
if(name==='sponsor-add'){data.sponsors.push({name:'NEW SPONSOR',img:''});persist();sponsorOpen=true;return render(true)}
if(name.startsWith('sponsor-up-')){const i=parseInt(name.slice(11));if(i>0)[data.sponsors[i-1],data.sponsors[i]]=[data.sponsors[i],data.sponsors[i-1]];persist();return render(true)}
if(name.startsWith('sponsor-remove-')){if(data.sponsors.length>1)data.sponsors.splice(parseInt(name.slice(15)),1);l.slideIndex=0;persist();return render(true)}
if(name.startsWith('sponsor-edit-')){dialog={type:'sponsor-edit',i:parseInt(name.slice(13))};return render(true)}
if(name==='save-sponsor'){const n=$('#sponsor-title')?.value?.trim();if(n){data.sponsors[dialog.i].name=n;dialog=null;persist();render(true)}else toast('Sponsor name is required.');return}
if(name==='team-add'){draft.teams.push({id:uid(),name:'',color:'#47816c',logo:''});return render(true)}
if(name.startsWith('team-remove-')){const i=parseInt(name.slice(12));if(draft.teams.length>2)draft.teams.splice(i,1);return render(true)}
if(name==='create-next'){if(createStep===0&&!draft.name.trim())return toast('Enter your league name to continue.');if(createStep===1&&(!draft.teams.every(t=>t.name.trim())||new Set(draft.teams.map(t=>t.name.toLowerCase().trim())).size!==draft.teams.length))return toast('Give each team a unique name.');if(createStep===3)return saveDraft();createStep++;return render(false)}
if(name==='create-back'){createStep=Math.max(0,createStep-1);return render(false)}
if(name==='save-game'){if(!gameDraft.home||!gameDraft.away||gameDraft.home===gameDraft.away)return toast('Choose two different teams.');if(!gameDraft.date||!gameDraft.time)return toast('Enter a date and tip-off time.');data.events.push({...gameDraft,id:uid(),status:'scheduled',finalScore:null});persist();gameDraft=null;navigate('league-games');return toast('Game scheduled successfully.');}
if(name.startsWith('edit-team-')){dialog={type:'team-edit',id:name.slice(10)};return render(true)}
if(name==='save-team'){const t=thisLeague()?.teams.find(x=>x.id===dialog.id),val=$('#edit-team-name')?.value.trim();if(t&&val){t.name=val;dialog=null;persist();render(true)}else toast('Please enter the team name.');return}
if(name==='studio-reset'){dialog={type:'reset'};return render(true)}
if(name==='confirm-reset'){const d=defaults();Object.keys(data).forEach(k=>delete data[k]);Object.assign(data,d);selectedLeagueId=null;selectedEventId=null;dialog=null;draft=null;persist();navigate('home');return toast('Studio data cleared.');}
}
function setOption(input){const [k,v]=input.split(':');if(k==='overlayColor'&&!/^#[0-9a-fA-F]{6}$/.test(v))return;
if(k==='overlay'&&!SCOREBOARD_IDS.includes(v))return;
if(k==='overlayPosition'&&!['top-left','top-right','bottom-left','bottom-right','bottom-center'].includes(v))return;
if(k==='overlay' && graphicIsCompact(v) && !graphicIsCompact(data.options.overlay))data.options.overlayPosition='bottom-right';
if(k==='overlay' && !graphicIsCompact(v) && graphicIsCompact(data.options.overlay))data.options.overlayPosition='bottom-center';if(k==='orientation'){if(data.live.active){toast('Orientation is locked during broadcasting.');return}if(streetballActive()&&v!=='portrait'){toast('Streetball uses portrait orientation. Select Regular game for landscape.');return}data.options.orientation=v;if(!streetballActive())data.options.regularOrientation=v;persist();render(true);return requestOrientation(v)}data.options[streetballActive()&&k==='overlayColor'?'streetOverlayColor':k]=['penalty','slideSecs','quarterMins','fps'].includes(k)?Number(v):v;persist();render(true)}
let orientationRequestId=0;
async function requestOrientation(mode,liveStart=false){const req=++orientationRequestId;
 if(window.IMGNativeChannel?.postMessage){
  window.IMGNativeChannel.postMessage('img-orientation:'+mode);
  return;
 }if(!window.matchMedia('(pointer:coarse)').matches){if(!liveStart)toast(mode==='landscape'?'Landscape output selected (desktop preview).':'Portrait output selected (desktop preview).');return}let locked=false;try{if(!document.fullscreenElement&&document.documentElement.requestFullscreen)await document.documentElement.requestFullscreen();if(req!==orientationRequestId)return;if(data.live.active&&data.live.lockedOrientation!==mode)return;if(screen.orientation&&typeof screen.orientation.lock==='function'){await screen.orientation.lock(mode);locked=true}}catch(e){}if(req!==orientationRequestId)return;if(!locked&&!window.matchMedia('(orientation: '+mode+')').matches){toast('Chrome could not rotate automatically. Enable Auto-rotate and turn your phone '+mode+'.')}else if(locked&&!liveStart)toast(mode==='landscape'?'Landscape mode activated':'Portrait mode activated')}
function getViewScroll(){return Array.from(document.querySelectorAll('[data-scroll]')).map(x=>({name:x.dataset.scroll,top:x.scrollTop}))}
document.addEventListener('click',e=>{const target=e.target.closest('[data-action],[data-go],[data-league],[data-event],[data-format],[data-set],[data-livetab],[data-score],[data-foul],[data-quarter]');if(!target)return;
if(target.classList.contains('modal-backdrop')&&e.target.closest('.sheet'))return;
if(target.hasAttribute('data-action'))return action(target.dataset.action);
if(target.hasAttribute('data-league'))return goLeague(target.dataset.league);
if(target.hasAttribute('data-event'))return goEvent(target.dataset.event);
if(target.hasAttribute('data-go'))return navigate(target.dataset.go);
if(target.hasAttribute('data-format')){draft.format=target.dataset.format;return render(true)}
if(target.hasAttribute('data-set'))return setOption(target.dataset.set);
if(target.hasAttribute('data-livetab')){focusTab=target.dataset.livetab;return render(true)}
if(target.hasAttribute('data-quarter')){data.live.quarter=Number(target.dataset.quarter);persist();return render(true)}
if(activeSport()==='pickleball'&&target.hasAttribute('data-score')){
 toast('Use WON RALLY to apply official Pickleball scoring.');return;
}
if(target.hasAttribute('data-score')||target.hasAttribute('data-foul')){const raw=target.dataset.score||target.dataset.foul,[i,d]=raw.split(':').map(Number);if(target.hasAttribute('data-score')){scoreFlashTeam=i;scoreFlashAt=Date.now()}pushUndo();const arr=target.hasAttribute('data-score')?data.live.scores:data.live.fouls;arr[i]=Math.max(0,arr[i]+d);persist();return render(true)}
});
document.addEventListener('input',e=>{const t=e.target;
 if(t.dataset.facebookField==='streamKey'){
  fbFields.streamKey=t.value;
  clearTimeout(fbAttemptTimer);fbAttemptTimer=null;
  if(!fbExpanded||data.live.active||fbConnecting)return;
  const key=fbFields.streamKey.trim();
  if(!key){updateFacebookFeedback('idle','Paste a Facebook Stream Key to connect.');return}
  if(key.length<10){updateFacebookFeedback('idle','Enter the complete Stream Key.');return}
  updateFacebookFeedback('loading','Checking Facebook Live connection…');
  fbAttemptTimer=setTimeout(()=>{
   fbAttemptTimer=null;
   if(fbExpanded&&!fbConnecting&&!data.live.active)startFacebookBroadcast();
  },e.inputType==='insertFromPaste'?60:650);
  return;
 }
 if(t.dataset.streetHorizontal){
  const raw=Number(t.value);if(!Number.isFinite(raw))return;
  const x=Math.max(10,Math.min(24,Math.round(raw)))/100;
  const y=data.options.streetPositions?.[0]?.y??.52;
  data.options.streetPositions=[{x,y},{x:1-x,y}];
  document.querySelectorAll('[data-street-horizontal-output]').forEach(el=>el.textContent=Math.round(x*100)+'%');
  persist();window.IMGMedia?.paintStreetballPreview?.();return;
 }
 if(t.dataset.streetVertical){
  if(!Array.isArray(data.options.streetPositions))data.options.streetPositions=[{x:.14,y:.52},{x:.86,y:.52}];
  const y=Math.max(23,Math.min(70,Math.round(Number(t.value)||52)))/100;
  data.options.streetPositions.forEach(p=>p.y=y);
  document.querySelectorAll('[data-street-vertical-output]').forEach(el=>el.textContent=Math.round(y*100)+'%');
  persist();window.IMGMedia?.paintStreetballPreview?.();return;
 }
if(t.dataset.graphicsRange){
 const k=t.dataset.graphicsRange,allowed={overlayWidth:[65,150],overlayHeight:[65,150],overlayOffsetX:[-30,30],overlayOffsetY:[-30,30],overlaySafe:[1,10]};
 if(!allowed[k])return;const value=Math.round(Number(t.value));if(!Number.isFinite(value))return;
 data.options[k]=Math.max(allowed[k][0],Math.min(allowed[k][1],value));persist();
 document.querySelectorAll('[data-graphics-output="'+k+'"]').forEach(x=>x.textContent=data.options[k]);
 window.IMGMedia?.paintBroadcastPreview?.();return;
}
if(t.dataset.scoreRange){const street=streetballActive(),prop=t.dataset.scoreRange==='size'?(street?'streetOverlaySize':'overlaySize'):(street?'streetOverlayOpacity':'overlayOpacity'),v=Math.round(Number(t.value));data.options[prop]=v;persist();document.querySelectorAll('[data-score-range-output="'+t.dataset.scoreRange+'"]').forEach(el=>el.textContent=v+'%');document.querySelectorAll('.scorebug,.pickle-scorebug').forEach(el=>el.style.setProperty(t.dataset.scoreRange==='size'?'--overlay-size':'--overlay-opacity',v/100));document.querySelectorAll('.streetball-score-overlay').forEach(el=>el.style.setProperty(t.dataset.scoreRange==='size'?'--street-size':'--street-alpha',t.dataset.scoreRange==='size'?Math.max(.32,Math.min(1.45,v/60)):v/100));window.IMGMedia?.paintStreetballPreview?.();return}
if(t.dataset.scoreColor){data.options[streetballActive()?'streetOverlayColor':'overlayColor']=t.value;persist();document.querySelectorAll('.scorebug,.pickle-scorebug').forEach(el=>el.style.setProperty('--overlay-accent',t.value));document.querySelectorAll('.streetball-score-overlay').forEach(el=>el.style.setProperty('--street-accent',t.value));window.IMGMedia?.paintStreetballPreview?.();return}
if(t.dataset.streetPlayer){
 const [side,slot]=t.dataset.streetPlayer.split(':').map(Number),p=data.options.streetPlayers?.[side]?.[slot];
 if(p){p.name=t.value;persist();document.querySelectorAll('[data-street-name="'+side+':'+slot+'"]').forEach(el=>el.textContent=t.value||'Player');}
 return;
}
if(t.dataset.draft){draft[t.dataset.draft]=['quarter','penalty'].includes(t.dataset.draft)?Number(t.value):t.value}if(t.dataset.teamName)draft.teams[Number(t.dataset.teamName)].name=t.value;if(t.dataset.game)gameDraft[t.dataset.game]=t.value;if(t.dataset.quick){data.options.broadcast[t.dataset.quick]=t.value;persist();updateQuickPreview(t.dataset.quick,t.value,t)}if(t.dataset.input){if(t.dataset.input==='selected-event'){selectedEventId=t.value||null;data.selectedEventId=selectedEventId;if(t.value){const ev=selectedEvent();selectedLeagueId=ev.leagueId;data.selectedLeagueId=ev.leagueId;const league=data.leagues.find(x=>x.id===ev.leagueId),home=league?.teams.find(x=>x.id===ev.home),away=league?.teams.find(x=>x.id===ev.away);Object.assign(data.options.broadcast,{homeName:home?.name||'',awayName:away?.name||'',homeLogo:home?.logo||'',awayLogo:away?.logo||'',leagueName:league?.name||'',leagueLogo:league?.logo||'',showLeagueName:Boolean(league?.name)});}else{Object.assign(data.options.broadcast,defaults().options.broadcast);}persist();render(true)}else{if(activeSport()==='pickleball'&&data.live.active&&['pickleballTarget','pickleballType','pickleballScoring','pickleballMatch'].includes(t.dataset.input)){
 toast('Finish the match before changing official scoring rules.');return render(true);
}
data.options[t.dataset.input]=['penalty','fps','pickleballTarget'].includes(t.dataset.input)?Number(t.value):t.value;persist();render(true)}}});
document.addEventListener('change',async e=>{const t=e.target;
 if(t.dataset.facebookField==='streamKey'){
  fbFields.streamKey=t.value.trim();
  if(fbAttemptTimer&&fbFields.streamKey.length>=10&&fbExpanded&&!fbConnecting&&!data.live.active){
   clearTimeout(fbAttemptTimer);fbAttemptTimer=null;
   await startFacebookBroadcast();
  }
  return;
 }
 if(t.dataset.streetTargetCustom){
  const points=Number(t.value);
  if(!t.value.trim()||!Number.isInteger(points)||points<1||points>999){t.value=streetWinTarget();toast('Enter a winning score between 1 and 999.');return}
  data.options.streetWinTarget=points;persist();return render(true);
 }
 if(t.dataset.streetVertical||t.dataset.streetHorizontal){persist();return}
if(t.dataset.graphicsCheck){
 if(t.dataset.graphicsCheck==='overlayAnimated'){data.options.overlayAnimated=!!t.checked;persist();return render(true)}return;
}
if(t.dataset.graphicsRange){persist();return render(true)}
if(t.dataset.scoreRange||t.dataset.scoreColor){persist();return render(true)}if(t.dataset.quick){data.options.broadcast[t.dataset.quick]=t.value;persist();return}if(!t.dataset.upload||!t.files?.[0])return;const file=t.files[0];
if(t.dataset.upload.startsWith('street-')){
 const match=/^street-([01])-([01])$/.exec(t.dataset.upload);if(!match)return;
 if(!file.type.startsWith('image/'))return toast('Choose a photo image.');
 if(file.size>8_000_000)return toast('Choose a player photo under 8 MB.');
 try{
  const photo=await streetPhotoData(file);
  data.options.streetPlayers[Number(match[1])][Number(match[2])].photo=photo;persist();render(true);
 }catch(e){toast('Unable to prepare this photo. Please try another image.');}
 return;
}
if(file.size>2_500_000){toast('Choose an image smaller than 2.5 MB.');return}if(!file.type.startsWith('image/')){toast('Upload an image file.');return}const r=new FileReader();r.onload=()=>{const value=String(r.result);if(t.dataset.upload==='quick-home-logo'){data.options.broadcast.homeLogo=value;persist()}else if(t.dataset.upload==='quick-away-logo'){data.options.broadcast.awayLogo=value;persist()}else if(t.dataset.upload==='quick-league-logo'){data.options.broadcast.leagueLogo=value;persist()}else if(t.dataset.upload==='league'&&draft)draft.logo=value;else if(t.dataset.upload.startsWith('team-')&&draft){const i=parseInt(t.dataset.upload.slice(5));if(draft.teams[i])draft.teams[i].logo=value}else if(t.dataset.upload.startsWith('sponsor-')){const i=parseInt(t.dataset.upload.slice(8));if(data.sponsors[i])data.sponsors[i].img=value;persist()}else if(t.dataset.upload.startsWith('saved-team-')){const id=t.dataset.upload.slice(11),tm=thisLeague()?.teams.find(x=>x.id===id);if(tm)tm.logo=value;persist()}render(true)};r.readAsDataURL(file)});

function keepKeyboardFieldVisible(){
 const el=document.activeElement;
 if(!isTypingControl(el))return;
 const pane=el.closest?.('.setup-quick-teams, .setup-landscape-settings, .scroll-controls, .content, .page');
 if(!pane)return;
 // Never scroll the left container: its top child is the live camera.
 const left=el.closest?.('.setup-landscape-left');
 if(left&&left.scrollTop!==0)left.scrollTop=0;
 try{
  const bounds=el.getBoundingClientRect(),parent=pane.getBoundingClientRect();
  const vv=window.visualViewport;
  const visibleBottom=vv?Math.min(parent.bottom,vv.height+vv.offsetTop):parent.bottom;
  if(bounds.bottom>visibleBottom-12)pane.scrollTop+=bounds.bottom-visibleBottom+24;
  else if(bounds.top<parent.top+6)pane.scrollTop-=parent.top-bounds.top+12;
 }catch(e){}
}
/* Keep the active video preview mounted through IME transitions.
 * Focus does NOT mean the on-screen keyboard is visible. On Android Chrome
 * the active input often stays focused after dismissing the keyboard.
 */
let keyboardBaseHeight=Math.max(window.innerHeight,window.visualViewport?.height||0);
let imeFocusTimer=null;
function syncKeyboardViewport(){
 const vv=window.visualViewport;
 const visible=Number(vv?.height)||window.innerHeight;
 const maxHeight=Math.max(180,Math.round(visible));
 document.documentElement.style.setProperty('--img-keyboard-visible-height',maxHeight+'px');
 document.documentElement.style.setProperty('--img-vv-top',Math.round(Math.max(0,Number(vv?.offsetTop)||0))+'px');
 const focusing=isTypingControl(document.activeElement);
 if(!focusing)keyboardBaseHeight=Math.max(keyboardBaseHeight,visible);
 const shrink=keyboardBaseHeight-visible;
 // Only shrink UI for a real keyboard, not focus alone or browser toolbar scroll.
 const keyboardVisible=focusing&&shrink>155&&visible<keyboardBaseHeight*.80;
 const wasKeyboardVisible=Boolean(document.body?.classList?.contains('img-keyboard-active'));
 document.body?.classList?.toggle('img-keyboard-active',keyboardVisible);
 document.body?.classList?.toggle('img-keyboard-left',keyboardVisible&&Boolean(document.activeElement?.closest?.('.setup-landscape-left')));
 if(keyboardVisible){
  const left=document.querySelector('.setup-landscape-left');
  if(left&&left.scrollTop!==0)left.scrollTop=0;
  requestAnimationFrame(keepKeyboardFieldVisible);
 }else if(wasKeyboardVisible){
  // Return the camera to its normal, in-flow landscape position immediately.
  // The focused input is deliberately left focused; a rerender would disrupt video.
  const left=document.querySelector('.setup-landscape-left');
  if(left)left.scrollTop=0;
 }
 // Recalculate the normal camera layout immediately when keyboard is dismissed.
 if(!keyboardVisible&&visible>=keyboardBaseHeight-80)keyboardBaseHeight=Math.max(keyboardBaseHeight,visible);
 return keyboardVisible;
}
document.addEventListener('focusin',event=>{
 if(!isTypingControl(event.target))return;
 clearTimeout(imeFocusTimer);
 const left=document.querySelector('.setup-landscape-left');
 if(left)left.scrollTop=0;
 syncKeyboardViewport();
 // Chrome may pause visible camera playback during IME compositing. Preserve the
 // stream and resume the existing video element; never re-request permission.
 requestAnimationFrame(()=>{
  if(window.IMGMedia?.isCameraOn?.())window.IMGMedia?.attach?.();
 });
 if(screen==='setup'&&!isPhysicalLandscape()&&document.querySelector('.setup-landscape-page')){
  render(true); // Undo a stale landscape editor while retaining focused entry.
 }
});
document.addEventListener('focusout',event=>{
 if(!isTypingControl(event.target))return;
 clearTimeout(imeFocusTimer);
 imeFocusTimer=setTimeout(()=>{
  if(isTypingControl(document.activeElement))return;
  document.body?.classList?.remove('img-keyboard-active','img-keyboard-left');
  keyboardBaseHeight=Math.max(keyboardBaseHeight,window.visualViewport?.height||window.innerHeight);
  document.documentElement.style.removeProperty('--img-keyboard-visible-height');
  document.documentElement.style.removeProperty('--img-vv-top');
 },90);
});
window.visualViewport?.addEventListener('resize',()=>{
 const visible=syncKeyboardViewport();
 if(screen==='setup'&&useLandscapeSetup()&&window.IMGMedia?.isCameraOn?.()){
  const video=document.querySelector('video[data-img-camera]');
  if(video?.paused)requestAnimationFrame(()=>window.IMGMedia?.attach?.());
 }
 if(visible)requestAnimationFrame(()=>requestAnimationFrame(keepKeyboardFieldVisible));
});
window.visualViewport?.addEventListener('scroll',()=>{
 if(isTypingControl(document.activeElement))syncKeyboardViewport();
});

let lastTick=performance.now(),lastSlideAt=Date.now(),lastPersistAt=0;
setInterval(()=>{const now=performance.now(),delta=Math.max(0,Math.min(0.20,(now-lastTick)/1000));lastTick=now;const l=data.live;
 if(l.active&&l.clockRunning&&!l.timeout){l.seconds=Math.max(0,l.seconds-delta);l.shot=Math.max(0,l.shot-delta);if(l.seconds===0)l.clockRunning=false;
 const shown=formatGameClock(l.seconds),shot=shotValue(l.shot);
 document.querySelectorAll('.bug-clock,.clock-value').forEach(n=>n.textContent=shown);
 document.querySelectorAll('.bug-shot').forEach(n=>n.textContent='SHOT '+shot);
 document.querySelectorAll('.shot-digits').forEach(n=>n.textContent=shot+'s');
 if(now-lastPersistAt>1000){persist();lastPersistAt=now}
 }
 if(l.active&&l.timeout&&l.slides&&l.playing&&data.options.slideSecs>0&&Date.now()-lastSlideAt>=data.options.slideSecs*1000){l.slideIndex=(l.slideIndex+1)%data.sponsors.length;lastSlideAt=Date.now();persist();render(true)}
},33);
window.screen?.orientation?.addEventListener?.('change',()=>{
 if(!isTypingControl(document.activeElement))render(true);
});
const initialRemoteHash=location.hash.match(/^#\/remote(?:\?(.+))?$/);
history.replaceState({screen:initialRemoteHash?'remote':'home'},'',initialRemoteHash?location.hash:'#/home');
let lastOrientation=isPhysicalLandscape();
window.addEventListener('resize',()=>{
 const landscape=isPhysicalLandscape();
 if(landscape!==lastOrientation){
  lastOrientation=landscape;
  if(!isTypingControl(document.activeElement))render(true);
 }
 if(isTypingControl(document.activeElement)){
  if(syncKeyboardViewport())requestAnimationFrame(keepKeyboardFieldVisible);
 }else keyboardBaseHeight=Math.max(keyboardBaseHeight,window.visualViewport?.height||window.innerHeight);
});
window.IMGAccounts?.subscribe?.(status=>{
 if(screen!=='setup'||!youtubeExpanded)return;
 const label=document.querySelector('#youtube-destination .broadcast-youtube-status');
 if(label){label.textContent=status.youtube==='connected'?'Connected':status.youtube==='verifying'?'Checking account…':
  status.youtube==='authorization-pending'?'Finish Google sign-in':'Not connected';
  label.classList.toggle('connected',status.youtube==='connected');}
});
window.addEventListener('img-native-resume',()=>{
 if(!youtubeExpanded)return;
 const api=window.IMGAccounts;
 if(api?.getStatus?.().youtube==='authorization-pending')
  api.verifyPairing('youtube').then(()=>render(true)).catch(e=>{youtubeFeedback=e.message;render(true)});
});
document.addEventListener('click',e=>{if(e.target?.closest?.('#img-production-advanced > summary'))advancedOpen=!advancedOpen;});
document.addEventListener('change',e=>{
 if(e.target?.matches?.('[data-youtube-audience]'))youtubeAudience=e.target.value;
});
window.addEventListener('popstate',event=>{remoteApi()?.stopScan?.();const dest=event.state?.screen||'home';if(screen==='live'&&data.live.active&&dest!=='live'){history.pushState({screen:'live'},'', '#/live');dialog={type:'exit-live'};render(true);return}screen=dest;if(screen==='create'&&!draft){draft=newDraft();createStep=0}if(screen==='add-game'&&!gameDraft){startNewGame();return}render(false)});
// Pairing links from Phone 1 open Phone 2 directly in the remote controller.
const remoteHash=initialRemoteHash;
if(remoteHash){
 screen='remote';
 const code=new URLSearchParams(remoteHash[1]||'').get('code');
 if(code&&remoteApi()?.status().role==='none')
  remoteApi()?.join(code).then(()=>render(true)).catch(e=>toast(e.message));
}
remoteApi()?.configure({applyAction:applyRemoteScoreCommand,snapshot:remoteGameSnapshot,update:remoteUpdated});
render(false);
})();