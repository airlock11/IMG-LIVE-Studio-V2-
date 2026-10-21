/* IMG LIVE Studio second-phone landscape scoring console.
   All command IDs route through IMGRemote; no camera/recorder controls live here. */
(()=>{'use strict';
let edit=null,endConfirm=false,endPending=false;
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=n=>{const s=Math.max(0,Number(n)||0),m=Math.floor(s/60);return String(m).padStart(2,'0')+':'+String(Math.floor(s%60)).padStart(2,'0')};
const b=(label,action,cls='',disabled=false)=>'<button type="button" class="rc-btn '+cls+'" data-action="'+action+'" '+(disabled?'disabled':'')+'>'+label+'</button>';
const sec=(label,body,cls='')=>'<div class="rc-group '+cls+'"><div class="rc-label">'+label+'</div>'+body+'</div>';
function timerPanel(st){
 const running=!!st.clockRunning,timeout=!!st.timeout,secs=Number(st.seconds)||0,shot=Number(st.shot)||0;
 return sec('GAME CLOCK · '+(st.quarter>4?'OT '+Math.max(1,(st.quarter||5)-4):'Q'+(st.quarter||1)),
 '<div class="rc-clock-display"><strong data-rc-clock>'+fmt(secs)+'</strong><span class="'+(running?'rc-running':'')+'">'+(running?'RUNNING':timeout?'TIMEOUT':'STOPPED')+'</span></div>'+
 '<div class="rc-button-grid three">'+b(running?'Pause':'Start','remote-control-toggle-clock','rc-accent',timeout)+b('Set','remote-edit-clock')+b('Reset','remote-control-reset-clock')+
 b('−10s','remote-control-clock-minus-10')+b('+10s','remote-control-clock-plus-10')+b('Undo','remote-control-undo')+'</div>','rc-clock-group')+
 sec('SHOT CLOCK <button type="button" class="rc-shot-toggle '+(st.shotVisible===false?'':'active')+'" role="switch" aria-label="Show shot clock on video" aria-checked="'+(st.shotVisible!==false)+'" data-action="remote-control-toggle-shot-visible"><span class="rc-toggle-thumb"></span><span class="rc-toggle-label">'+(st.shotVisible===false?'OFF':'ON')+'</span></button>',
 '<div class="rc-shot-row '+(st.shotVisible===false?'rc-shot-disabled':'')+'"><strong data-rc-shot>'+(st.shotVisible===false?'OFF':Math.floor(shot)+'s')+'</strong>'+
 b('24','remote-control-shot-24','rc-accent',st.shotVisible===false)+b('14','remote-control-shot-14','',st.shotVisible===false)+b('−1','remote-control-shot-minus','',st.shotVisible===false)+b('Set','remote-edit-shot','',st.shotVisible===false)+'</div>','rc-shot-group');
}
function periodPanel(st){
 return sec('PERIOD & POSSESSION','<div class="rc-period-row">'+[1,2,3,4].map(q=>b('Q'+q,'remote-control-period-'+q,(st.quarter===q?'rc-selected':''))).join('')+
 b('OT','remote-control-period-5',(st.quarter===5?'rc-selected':''))+b('OT +','remote-control-quarter-next-ot')+'</div>'+
 '<div class="rc-possession-row">'+b('◀ '+esc(st.names?.[0]||'Home'),'remote-control-possession-0',st.possession===0?'rc-selected':'')+
 b(esc(st.names?.[1]||'Away')+' ▶','remote-control-possession-1',st.possession===1?'rc-selected':'')+'</div>');
}
function breakPanel(st){
 const t=!!st.timeout,slides=!!st.slides;
 const actions=t?
   b('Resume game','remote-control-resume-game','rc-accent')+
   (slides?b('◀ Slide','remote-control-slide-prev')+b('Next ▶','remote-control-slide-next')+b(st.playing?'Pause slides':'Play slides','remote-control-slide-play'):
   b('Show sponsors','remote-control-show-sponsors')) :
   b('Timeout','remote-control-timeout','rc-warn');
 return sec('GAME BREAKS','<div class="rc-break-row">'+actions+'</div>');
}
function picklePanel(st){
 const serving=Number(st.pickleballServer)||0,game=Number(st.pickleballGame)||1;
 return sec('OFFICIAL PICKLEBALL · GAME '+game,
 '<div class="rc-serve-status">Serving: <strong>'+esc(st.names?.[serving]|| (serving?'Away':'Home'))+'</strong>'+
 ' · Server '+(st.pickleballServeNumber||1)+(st.pickleballWinner===0||st.pickleballWinner===1?' · Game finished':'')+'</div>'+
 '<div class="rc-pickle-row">'+b('Serve ◀','remote-control-pickle-server-0',serving===0?'rc-selected':'')+
 b('Serve ▶','remote-control-pickle-server-1',serving===1?'rc-selected':'')+
 (st.pickleballType==='doubles'&&st.pickleballScoring!=='rally'?
 b('Server 1','remote-control-pickle-serve-number-1',st.pickleballServeNumber===1?'rc-selected':'')+
 b('Server 2','remote-control-pickle-serve-number-2',st.pickleballServeNumber===2?'rc-selected':''):'')+
 b('Next game','remote-control-pickle-game-next','rc-accent')+
 b('Undo','remote-control-undo')+'</div>');
}
function team(st,i){
 const pickle=st.sport==='pickleball',street=st.mode==='streetball',score=Array.isArray(st.scores)?Number(st.scores[i])||0:0;
 const fouls=Array.isArray(st.fouls)?Number(st.fouls[i])||0:0;
 const name=esc(st.names?.[i]|| (i?'AWAY':'HOME'));
 return '<section class="rc-team rc-team-'+i+'" aria-label="'+name+' scoring controls">'+
 '<div class="rc-team-head"><span>'+(i?'AWAY / RIGHT':'HOME / LEFT')+'</span><span class="rc-team-name" title="'+name+'">'+name+'</span></div>'+
 '<div class="rc-score">'+Math.max(0,score)+'</div>'+
 (pickle?b('✓ WON RALLY','remote-control-pickle-rally-'+i,'rc-rally')+
 '<div class="rc-team-scoring rc-pickle-score">'+b('−1','remote-control-pickle-correct-'+i+'-minus')+b('+1','remote-control-pickle-correct-'+i+'-plus','rc-accent')+'</div>'+
 '<div class="rc-team-footer">Games won <strong>'+Number(st.pickleballGames?.[i]||0)+'</strong></div>':
 '<div class="rc-team-scoring">'+b('−1','remote-score-'+i+'--1')+
 b('+1','remote-score-'+i+'-1','rc-accent')+b('+2','remote-score-'+i+'-2','rc-accent')+
 (!street?b('+3','remote-score-'+i+'-3','rc-accent'):'')+'</div>'+
 (!street?'<div class="rc-foul"><span>TEAM FOULS <strong>'+fouls+'</strong></span>'+
 b('−','remote-foul-'+i+'--1')+b('+','remote-foul-'+i+'-1')+'</div>':'<div class="rc-team-footer">Streetball</div>'))+
 '</section>';
}
function editOverlay(){
 if(!edit)return '';
 return '<div class="rc-edit-backdrop" role="presentation"><section class="rc-edit-sheet" role="dialog" aria-modal="true" aria-label="Set '+edit+'"><h2>Set '+(edit==='clock'?'game clock':'shot clock')+'</h2>'+
 (edit==='clock'?'<label>Minutes<input id="rc-edit-minutes" type="number" inputmode="numeric" min="0" max="99" value="10"></label><label>Seconds<input id="rc-edit-seconds" type="number" inputmode="numeric" min="0" max="59" value="00"></label>':
 '<label>Seconds<input id="rc-edit-seconds" type="number" inputmode="numeric" min="0" max="99" value="24"></label>')+
 '<div class="rc-edit-actions">'+b('Cancel','remote-edit-cancel')+b('Apply','remote-edit-submit','rc-accent')+'</div></section></div>';
}
function endConfirmOverlay(st){
 if(!endConfirm)return '';
 return '<div class="rc-edit-backdrop" role="presentation"><section class="rc-edit-sheet rc-end-confirm" role="alertdialog" aria-modal="true" aria-label="Confirm End Live">'+
 '<h2>End Live?</h2><p>'+(st.recordOnly?'This will stop the broadcaster’s recording and save the video on Phone 1.':'This will end the broadcaster’s live session on Phone 1.')+' This cannot be undone.</p>'+
 '<div class="rc-edit-actions">'+b('Cancel','remote-cancel-end-live')+b(st.recordOnly?'Stop & Save':'End Live','remote-confirm-end-live','rc-end-live')+'</div></section></div>';
}
function render(r){
 const st=r?.state||{},sport=st.sport||'basketball',isPickle=sport==='pickleball',street=st.mode==='streetball';
 if(st.ended&&!st.live){endPending=false;endConfirm=false;}

 const sportLabel=isPickle?'PICKLEBALL':street?'STREETBALL':'BASKETBALL';
 return '<div class="remote-console" role="application" aria-label="IMG LIVE Studio second-phone scoring controller">'+
 '<div class="rc-topbar"><span class="rc-brand">IMG <b>REMOTE</b></span>'+
 '<span class="rc-connection"><i class="'+(r.connected?'rc-connected':'')+'"></i>'+esc(r.connected?'Connected':'Reconnecting')+'</span>'+
 '<span class="rc-sport">'+sportLabel+'</span>'+
 '<span class="rc-network" data-remote-rtt>'+(r.rtt!=null?'RTT '+r.rtt+' ms':'Cloud link')+'</span>'+
 b('⛶ Full screen','remote-landscape','rc-layout')+
 b('↻ Reconnect','remote-reconnect','rc-layout')+
 b('■ End Live','remote-end-live','rc-end-live',!st.live||!r.connected||endPending)+
 b('✕ Leave','remote-leave','rc-exit')+'</div>'+
 '<div class="rc-content">'+team(st,0)+team(st,1)+
 '<section class="rc-operations">'+(st.ended&&!st.live?
 sec('BROADCAST ENDED','<p class="rc-ended-message">Phone 1 has ended the session. Video files remain on the broadcaster device.</p>'+b('Leave remote','remote-leave','rc-exit'))+
 (isPickle?picklePanel(st):street?sec('STREETBALL','<div class="rc-street-meta">'+esc(st.streetFormat||'3v3')+' · Fast scoring</div>'+b('Undo','remote-control-undo')):timerPanel(st)+periodPanel(st)):
 (isPickle?picklePanel(st):street?sec('STREETBALL','<div class="rc-street-meta">'+esc(st.streetFormat||'3v3')+' · Fast scoring</div>'+b('Undo','remote-control-undo')):timerPanel(st)+periodPanel(st)))+
 breakPanel(st)+
 (isPickle?sec('MATCH','<div class="rc-score-call">Score call: '+esc(st.pickleballCall||'See broadcaster')+'</div>'):'')+
 (endPending?'<p class="rc-queue">End Live requested. Waiting for Phone 1 to finish…</p>':'')+
 (r.outbox?'<p class="rc-queue">'+r.outbox+' pending score action(s)</p>':'')+
 (r.lastError?'<p class="rc-error">'+esc(r.lastError)+'</p>':'')+
 '</section></div>'+
 '<div class="rc-rotate-tip" aria-live="polite">Turn your phone sideways for the scoring console.'+
 b('Enter landscape','remote-landscape','rc-accent')+'</div>'+
 editOverlay()+endConfirmOverlay(st)+'</div>';
}
function openEdit(kind){if(kind!=='clock'&&kind!=='shot')return;edit=kind;endConfirm=false}
function openEndConfirm(){edit=null;endConfirm=true}
function closeEndConfirm(){endConfirm=false;endPending=false}
function endRequested(){endConfirm=false;endPending=true}
function closeEdit(){edit=null}
function editValue(){
 if(!edit)return null;
 const secs=Number(document.getElementById('rc-edit-seconds')?.value);
 if(!Number.isInteger(secs)||secs<0||secs>59&&edit==='clock'||secs>99&&edit==='shot')throw Error('Enter valid seconds');
 if(edit==='clock'){
  const min=Number(document.getElementById('rc-edit-minutes')?.value);
  if(!Number.isInteger(min)||min<0||min>99)return (()=>{throw Error('Enter 0–99 minutes')})();
  return {kind:'set_clock',seconds:min*60+secs};
 }
 return {kind:'set_shot',seconds:secs};
}
function tryLandscape(){
 // Browser orientation locking usually requires fullscreen and a user gesture.
 const root=document.documentElement;
 if(!document.fullscreenElement&&root.requestFullscreen){
  return root.requestFullscreen({navigationUI:'hide'}).catch(()=>{}).then(async()=>{
   try{await window.screen?.orientation?.lock?.('landscape')}catch{}
   return Boolean(window.matchMedia?.('(orientation:landscape)')?.matches);
  });
 }
 return Promise.resolve().then(async()=>{
  try{await window.screen?.orientation?.lock?.('landscape')}catch{}
  return Boolean(window.matchMedia?.('(orientation:landscape)')?.matches);
 });
}
function exitLandscape(){
 try{window.screen?.orientation?.unlock?.()}catch{}
 try{if(document.fullscreenElement)document.exitFullscreen()?.catch?.(()=>{})}catch{}
}
window.IMGRemoteConsole={render,openEdit,closeEdit,editValue,openEndConfirm,closeEndConfirm,endRequested,tryLandscape,exitLandscape};
})();