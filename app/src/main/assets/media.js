/* IMG LIVE Studio: opt-in camera preview + browser-local video recording.
 * All media stays in this browser/device. No API uploads or streaming.
 */
(() => {
 'use strict';
 let cameraStream=null, microphoneStream=null, sourceVideo=null, recorder=null, recorderStream=null;
 let cameraEpoch=0; // Invalidates an in-flight getUserMedia when leaving the recording screen.
 let drawCanvas=null, chunks=[], startedAt=0, latestClip=null, stateReader=null, overlayReader=null, previewRaf=0, lastPreviewFrame=0, liveCapture=null, broadcastPreviewRaf=0, lastBroadcastPreviewFrame=0;
 // The WebView monitor asks Android for the REAL bitmap that RootEncoder uses.
 // Only request a new bitmap when game data or artwork changes (no 60fps
 // PNG encoding). Preview and browser recording share the same bitmap.
 let nativeScoreImage=null, nativeScoreKey='', nativeScorePending='';
 const logoThumbs=new Map();
 function previewLogo(source){
  if(typeof source!=='string'||!/^data:image\/(png|jpeg|webp);base64,/i.test(source))return '';
  if(source.length<=35000&&!source.startsWith('data:image/webp;'))return source;
  const key=source.length+':'+source.slice(-32);
  if(logoThumbs.has(key))return logoThumbs.get(key)||'';
  logoThumbs.set(key,'');
  const img=new Image();
  img.onload=()=>{
   try{
    const c=document.createElement('canvas');c.width=64;c.height=64;
    const ctx=c.getContext('2d');
    if(!ctx)return;
    const f=Math.min(60/img.naturalWidth,60/img.naturalHeight);
    ctx.drawImage(img,32-img.naturalWidth*f/2,32-img.naturalHeight*f/2,
     img.naturalWidth*f,img.naturalHeight*f);
    const small=c.toDataURL('image/png');
    if(small.length<=35000)logoThumbs.set(key,small);
    nativeScorePending='';
    paintBroadcastPreview();
   }catch(_){}
  };
  img.onerror=()=>{nativeScorePending='';};
  img.src=source;
  if(logoThumbs.size>6){
   const first=logoThumbs.keys().next().value;
   if(first!==key)logoThumbs.delete(first);
  }
  return '';
 }
 const nativeGraphicsEnabled=()=>typeof window.IMGNativeChannel?.postMessage==='function';
 window.addEventListener('img-native-graphics-frame',event=>{
  const detail=event?.detail;
  if(!detail||detail.key!==nativeScorePending||typeof detail.src!=='string'||
     !detail.src.startsWith('data:image/png;base64,'))return;
  const picture=new Image();
  picture.onload=()=>{
   if(nativeScorePending!==detail.key)return;
   nativeScoreImage=picture;nativeScoreKey=detail.key;
   paintBroadcastPreview();
   paintSetupScoreboard();
  };
  picture.src=detail.src;
 });
 function syncNativeGraphic(s){
  if(!nativeGraphicsEnabled())return false;
  const o=s.options||{},l=s.live||{},t=s.teams||[];
  const home=t[0]||{},away=t[1]||{};
  const styleIds=['prime-tv','courtside-a','courtside-b','arena-glass',
   'rivalry-plate','center-dial','broadcast-classic','crown-pill',
   'split-box','match-cube','edge-box','legacy-tv'];
  const style=Math.max(0,styleIds.indexOf(o.overlay));
  const logoId=v=>String(v||'').length+':'+String(v||'').slice(-24);
  const leftLogo=previewLogo(home.logo),rightLogo=previewLogo(away.logo);
  const key=[style,home.name,away.name,l.scores?.[0],l.scores?.[1],
   l.fouls?.[0],l.fouls?.[1],l.quarter,Math.floor(l.seconds??600),
   l.shot,o.overlayOpacity,logoId(home.logo),logoId(leftLogo),
   logoId(away.logo),logoId(rightLogo)].join('|').slice(0,350);
  if(nativeScoreKey===key||nativeScorePending===key)return true;
  nativeScorePending=key;
  // Keep the previous frame visible until the new native bitmap decodes.
  const game={
   key,style,home:home.name||'HOME',away:away.name||'AWAY',
   homeScore:Math.max(0,Math.min(999,Math.round(Number(l.scores?.[0])||0))),
   awayScore:Math.max(0,Math.min(999,Math.round(Number(l.scores?.[1])||0))),
   homeFouls:Math.max(0,Math.min(99,Math.round(Number(l.fouls?.[0])||0))),
   awayFouls:Math.max(0,Math.min(99,Math.round(Number(l.fouls?.[1])||0))),
   period:Math.max(1,Math.min(9,Math.round(Number(l.quarter)||1))),
   clock:formatClock(l.seconds??600),shot:Math.max(0,Math.min(99,Math.ceil(Number(l.shot??24)))),
   opacity:clamp(o.overlayOpacity??100,30,100)/100,
   homeLogo:leftLogo,awayLogo:rightLogo
  };
  try{window.IMGNativeChannel.postMessage('native-graphics-frame:'+encodeURIComponent(JSON.stringify(game)));}
  catch(_){nativeScorePending='';}
  return true;
 }
 function paintSetupScoreboard(){
  const canvases=document.querySelectorAll('canvas[data-setup-scoreboard]');
  if(!canvases.length)return;
  let state;try{state=overlayReader?.()}catch(_){return}
  if(!state)return;
  // Use the exact Android on-air scoreboard bitmap, not a placeholder.
  syncNativeGraphic(state);
  for(const canvas of canvases){
   if(canvas.width!==1280)canvas.width=1280;
   if(canvas.height!==720)canvas.height=720;
   const ctx=canvas.getContext('2d');
   if(!ctx)continue;
   ctx.clearRect(0,0,canvas.width,canvas.height);
   if(!nativeScoreImage||!nativeScoreImage.naturalWidth)continue;
   // Use exactly the same output-space placement and scale as the live
   // compositor. Both the normal and the sticky 16:9 previews call this.
   drawNativeGraphic(ctx,state,canvas.width,canvas.height);
   canvas.parentElement?.classList.add('img-wizard-score-ready');
  }
 }
 function drawNativeGraphic(ctx,s,w,h){
  if(!syncNativeGraphic(s))return false;
  // When a bitmap is loading, never show an unrelated legacy scoreboard.
  if(!nativeScoreImage)return true;
  const o=s.options||{},id=['prime-tv','courtside-a','courtside-b','arena-glass',
   'rivalry-plate','center-dial','broadcast-classic','crown-pill',
   'split-box','match-cube','edge-box','legacy-tv'].indexOf(o.overlay);
  const compact=id>=8;
  // One size slider with locked natural proportions and automatic placement.
 const size=clamp(o.overlaySize??60,15,115)/60;
 let pw=(compact?31:84)*size;
 let ph=pw*(960/540)*(compact?1:144/960);
 const factor=Math.min(1,85/ph,95/pw);pw*=factor;ph*=factor;
 const safe=3;
 const px=compact?100-pw-safe:(100-pw)/2;
 const py=100-ph-safe;
 ctx.drawImage(nativeScoreImage,w*px/100,h*py/100,w*pw/100,h*ph/100);
  return true;
 }
 const DB='img-live-offline-clips', STORE='clips';
 const canCamera=()=>Boolean(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia);
 const isCameraOn=()=>Boolean(cameraStream&&cameraStream.getVideoTracks().some(t=>t.readyState==='live'));
 let cameraOpening=null;
 const isRecording=()=>Boolean(recorder&&recorder.state==='recording');
 const readableError=e=>e?.name==='NotAllowedError'?'Camera permission was denied. Allow access in Chrome site settings.':
  e?.name==='NotFoundError'?'No camera was found on this device.':
  e?.name==='NotReadableError'?'The camera is currently in use by another app.':
  e?.name==='OverconstrainedError'?'This camera does not support the requested resolution.':
  e?.message||'Camera or recording could not start.';
 async function openCamera({quality='720p',fps=30,facingMode='environment'}={}){
   if(isCameraOn()){attach();return cameraStream;}
   if(!canCamera())throw new Error('Camera is unavailable. Open IMG LIVE Studio in HTTPS Chrome and allow camera access.');
   const high=quality==='1080p',rate=Number(fps)===60?60:30;
   const requested={audio:false,video:{facingMode:{ideal:facingMode},
    width:{min:high?1920:1280,ideal:high?1920:1280},
    height:{min:high?1080:720,ideal:high?1080:720},
    frameRate:{min:rate===60?59:29,ideal:rate}}};
   if(cameraOpening){await cameraOpening;if(isCameraOn())return cameraStream;}
   const attempt=++cameraEpoch;
   const pending=navigator.mediaDevices.getUserMedia(requested);
   cameraOpening=pending;
   let opened;
   try{opened=await pending;}finally{if(cameraOpening===pending)cameraOpening=null;}
   if(attempt!==cameraEpoch){
    opened.getTracks().forEach(track=>track.stop());
    throw new Error('Camera setup was cancelled.');
   }
   if(isCameraOn()){
    opened.getTracks().forEach(track=>track.stop());
    return cameraStream;
   }
   cameraStream=opened;
   const measured=cameraStream.getVideoTracks()[0]?.getSettings?.()||{};
   const width=Math.max(Number(measured.width)||0,Number(measured.height)||0);
   const height=Math.min(Number(measured.width)||0,Number(measured.height)||0);
   if(width<(high?1920:1280)||height<(high?1080:720)||
    (Number(measured.frameRate)>0&&Number(measured.frameRate)<(rate===60?58:28))){
    cameraStream.getTracks().forEach(track=>track.stop());cameraStream=null;
    throw new Error('This camera cannot capture '+quality+' at '+rate+' FPS. Select a supported profile.');
   }
   sourceVideo=document.createElement('video');sourceVideo.muted=true;sourceVideo.autoplay=true;
  sourceVideo.playsInline=true;sourceVideo.srcObject=cameraStream;
  try{sourceVideo.play().catch(()=>{});}catch(e){/* Playback can resume after a user interaction. */}
  cameraStream.getVideoTracks().forEach(t=>t.addEventListener('ended',()=>{if(!isRecording())stopCamera();}));
  attach();return cameraStream;
 }
 function attach(readState){
  if(typeof readState==='function')overlayReader=readState;
  const active=isCameraOn();
  document.querySelectorAll('video[data-img-camera]').forEach(v=>{
   if(active&&v.srcObject!==cameraStream){v.srcObject=cameraStream;v.muted=true;v.playsInline=true;v.autoplay=true;v.play().catch(()=>{});}
   else if(active&&v.srcObject===cameraStream&&v.paused){
    // Android Chrome can pause mounted video during keyboard viewport changes.
    // Resume playback on the existing cameraStream without requesting new access.
    v.play().catch(()=>{});
   }
   else if(!active&&v.srcObject)v.srcObject=null;
  });
  document.querySelectorAll('.camera').forEach(el=>el.classList.toggle('camera-real',active));
  paintStreetballPreview();
  paintBroadcastPreview();
  paintSetupScoreboard();
  if(active){startStreetballPreviewLoop();startBroadcastPreviewLoop();}
 }
 function stopCamera(){
  if(isRecording()||liveCapture)return;
  cameraEpoch++; // Do not attach delayed camera permission results after leaving.
  if(cameraStream)cameraStream.getTracks().forEach(t=>t.stop());
  cameraStream=null;
  if(sourceVideo){sourceVideo.pause();sourceVideo.srcObject=null;sourceVideo=null;}
  attach();
 }
 function openDB(){
  return new Promise((resolve,reject)=>{
   if(!('indexedDB' in window))return reject(new Error('Browser-local clip storage is unavailable.'));
   const req=indexedDB.open(DB,1);
   req.onupgradeneeded=()=>{const db=req.result;if(!db.objectStoreNames.contains(STORE))db.createObjectStore(STORE,{keyPath:'id'});};
   req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error||new Error('Unable to open offline recordings.'));
  });
 }
 async function saveClip(clip){
  const db=await openDB();
  return new Promise((resolve,reject)=>{
   const tx=db.transaction(STORE,'readwrite');tx.objectStore(STORE).put(clip);
   tx.oncomplete=()=>{db.close();resolve(true);};
   tx.onerror=()=>{db.close();reject(tx.error||new Error('Insufficient local device storage.'));};
   tx.onabort=()=>{db.close();reject(tx.error||new Error('Offline save was interrupted.'));};
  });
 }
 async function listClips(){
  try{const db=await openDB();return await new Promise((resolve,reject)=>{
   const tx=db.transaction(STORE,'readonly'),r=tx.objectStore(STORE).getAll();
   r.onsuccess=()=>{db.close();resolve(r.result.map(x=>({id:x.id,name:x.name,size:x.size,mime:x.mime,createdAt:x.createdAt,duration:x.duration,metadata:x.metadata||null,saved:true})).sort((a,b)=>b.createdAt-a.createdAt));};
   r.onerror=()=>{db.close();reject(r.error);};
  });}catch(e){return [];}
 }
 async function getClip(id){
  if(latestClip?.id===id)return latestClip;
  const db=await openDB();return new Promise((resolve,reject)=>{
   const tx=db.transaction(STORE,'readonly'),r=tx.objectStore(STORE).get(id);
   r.onsuccess=()=>{db.close();resolve(r.result||null);};r.onerror=()=>{db.close();reject(r.error);};
  });
 }
 async function deleteClip(id){
  const db=await openDB();return new Promise((resolve,reject)=>{
   const tx=db.transaction(STORE,'readwrite');tx.objectStore(STORE).delete(id);
   tx.oncomplete=()=>{db.close();if(latestClip?.id===id)latestClip=null;resolve();};
   tx.onerror=()=>{db.close();reject(tx.error);};
  });
 }
 function downloadClip(clip){
  if(!clip||!(clip.blob instanceof Blob))throw new Error('Recording file could not be found.');
  const url=URL.createObjectURL(clip.blob),anchor=document.createElement('a');
  anchor.href=url;anchor.download=clip.name;anchor.style.display='none';document.body.appendChild(anchor);
  anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),120000);
 }
 const clamp=(v,min,max)=>Math.min(max,Math.max(min,Number(v)||0));
 function color(hex,alpha=1){
  const safe=/^#[0-9a-f]{6}$/i.test(hex||'')?hex:'#e66d48';
  const n=parseInt(safe.slice(1),16);return 'rgba('+((n>>16)&255)+','+((n>>8)&255)+','+(n&255)+','+alpha+')';
 }
 function text(ctx,str,x,y,px,weight='700',align='left',fill='#fff',maxWidth){
  ctx.save();ctx.fillStyle=fill;ctx.textBaseline='middle';ctx.textAlign=align;
  ctx.font=weight+' '+Math.max(9,Math.round(px))+'px system-ui,Arial,sans-serif';
  const msg=String(str==null?'':str);
  if(maxWidth&&ctx.measureText(msg).width>maxWidth){
   let temp=msg;while(temp.length>1&&ctx.measureText(temp+'…').width>maxWidth)temp=temp.slice(0,-1);
   ctx.fillText(temp+'…',x,y);
  }else ctx.fillText(msg,x,y);
  ctx.restore();
 }
 function block(ctx,x,y,w,h,fill,r=0){
  ctx.fillStyle=fill;ctx.beginPath();
  if(typeof ctx.roundRect==='function')ctx.roundRect(x,y,w,h,r);
  else ctx.rect(x,y,w,h);
  ctx.fill();
 }
 const imageCache=new Map();
 function imageFor(uri){
  if(!uri||(!uri.startsWith('data:image/')&&!uri.startsWith('blob:')))return null;
  if(!imageCache.has(uri)){
   const i=new Image();i.onload=()=>paintStreetballPreview();i.src=uri;imageCache.set(uri,i);
   if(imageCache.size>20)imageCache.delete(imageCache.keys().next().value);
  }
  const img=imageCache.get(uri);return img.complete&&img.naturalWidth?img:null;
 }
 function drawBadge(ctx,logo,x,y,size,accent,initial){
  block(ctx,x,y,size,size,color(accent,.9),size*.2);
  const img=imageFor(logo);
  if(img)ctx.drawImage(img,x+2,y+2,size-4,size-4);
  else text(ctx,initial,x+size/2,y+size/2,size*.54,'900','center');
 }
 function drawVideo(ctx,video,w,h){
  ctx.fillStyle='#06191c';ctx.fillRect(0,0,w,h);
  if(!video||video.readyState<2||!video.videoWidth)return;
  const vw=video.videoWidth,vh=video.videoHeight,scale=Math.max(w/vw,h/vh);
  const dw=vw*scale,dh=vh*scale;ctx.drawImage(video,(w-dw)/2,(h-dh)/2,dw,dh);
 }
 function formatClock(sec){
  const n=Math.max(0,Number(sec)||0),ms=Math.round(n*1000);
  if(ms<60000)return Math.floor(ms/1000)+'.'+String(ms%1000).padStart(3,'0');
  return String(Math.floor(n/60)).padStart(2,'0')+':'+String(Math.floor(n%60)).padStart(2,'0');
 }
 function drawGameBar(ctx,s,w,h){
  const o=s.options||{},l=s.live||{},accent=o.overlayColor||'#E66D48',opacity=clamp(o.overlayOpacity??100,30,100)/100;
  const size=clamp(o.overlaySize??45,15,115)/100,style=o.overlay||'simple';
  const isCorner=style.startsWith('corner-'),isNarrow=style==='narrow';
  const baseW=isCorner?w*.75:w*.94,baseH=isCorner?h*.24:isNarrow?h*.105:h*.15;
  const bw=baseW*size,bh=baseH*size;
  let x=(w-bw)/2,y=h-bh-h*.028;
  if(style==='corner-left')x=w*.018;
  if(style==='corner-right')x=w-bw-w*.018;
  const home=s.teams?.[0]||{name:''},away=s.teams?.[1]||{name:''};
  ctx.save();ctx.globalAlpha=opacity;
  block(ctx,x,y,bw,bh,'rgba(7,16,24,.92)',bh*.13);
  if(style==='pro-arena'||style==='pro-league'){
   block(ctx,x,y,bw*.36,bh,color(accent,style==='pro-league'?.7:.48),bh*.08);
   block(ctx,x+bw*.65,y,bw*.35,bh,color('#235cb8',.64),bh*.08);
  }else if(style==='simple'){block(ctx,x,y+bh-3*size,bw,3*size,color(accent,.96));}
  else if(isCorner){block(ctx,x,y,4*size,bh,color(accent,.95));}
  else if(isNarrow){block(ctx,x,y+bh-2*size,bw,2*size,color(accent,.95));}
  const pad=bw*.028,logo=clamp(bh*.46,13,Math.max(13,bh*.6)),fontBase=clamp(bh*.25,11,35);
  const scoreFont=clamp(bh*.39,13,47);
  const nameMax=bw*.205;
  drawBadge(ctx,home.logo,x+pad,y+bh*.24,logo,accent,(home.name||'').slice(0,1));
  text(ctx,home.name,x+pad+logo+bw*.012,y+bh*.44,fontBase*.77,'850','left','#fff',nameMax);
  text(ctx,String(l.scores?.[0]??0),x+bw*.40,y+bh*.40,scoreFont,'900','right');
  text(ctx,String(l.scores?.[1]??0),x+bw*.60,y+bh*.40,scoreFont,'900','left');
  drawBadge(ctx,away.logo,x+bw-pad-logo,y+bh*.24,logo,'#285bb4',(away.name||'').slice(0,1));
  text(ctx,away.name,x+bw-pad-logo-bw*.012,y+bh*.44,fontBase*.77,'850','right','#fff',nameMax);
  block(ctx,x+bw*.425,y+bh*.10,bw*.15,bh*.80,'rgba(0,0,0,.58)',bh*.12);
  const p=l.quarter<=4?'Q'+l.quarter:l.quarter===5?'OT':'OT'+(l.quarter-4);
  text(ctx,p,x+bw*.5,y+bh*.27,fontBase*.55,'900','center','#ffdda1');
  text(ctx,formatClock(l.seconds),x+bw*.5,y+bh*.51,fontBase*.85,'850','center');
  if(o.shotVisible)text(ctx,'SHOT '+Math.ceil(l.shot??24),x+bw*.5,y+bh*.72,fontBase*.55,'850','center','#f6cda3');
  text(ctx,'FOULS '+(l.fouls?.[0]??0)+(Number(l.fouls?.[0]||0)>=Number(o.penalty||5)?' PENALTY':''),x+pad,y+bh*.81,fontBase*.5,'700','left','#ffe6b7');
  text(ctx,'FOULS '+(l.fouls?.[1]??0)+(Number(l.fouls?.[1]||0)>=Number(o.penalty||5)?' PENALTY':''),x+bw-pad,y+bh*.81,fontBase*.5,'700','right','#ffe6b7');
  ctx.restore();
 }


 /* The preview and exported MP4 use the SAME drawStreetball() function.
    Controls stay outside the camera canvas and are never composited into files. */

 /* Standard basketball: the monitor displays the EXACT same composited
    pixels as the Facebook feed and local recording. No second HTML-style
    scoreboard is overlaid on a live camera. During streaming/recording, copy
    the encoded source canvas instead of compositing an extra 720p frame. */
 function paintBroadcastPreview(){
  // Show the selected ACTUAL native scoreboard even before camera permission;
  // no invented court/silhouette or stale HTML scorebar on the V2 monitor.
  if(!overlayReader)return;
  const canvases=document.querySelectorAll('canvas[data-broadcast-preview]');
  if(!canvases.length)return;
  let state;try{state=overlayReader()}catch(_){return}
  if(state?.sport!=='basketball'||state.options?.basketballMode==='streetball')return;
  syncNativeGraphic(state);
  const high=state.options?.quality==='1080p';
  const portrait=state.options?.orientation==='portrait';
  const w=portrait?(high?1080:720):(high?1920:1280);
  const h=portrait?(high?1920:1280):(high?1080:720);
  const composed=liveCapture?.canvas||(isRecording()?drawCanvas:null);
  canvases.forEach(canvas=>{
   if(canvas.width!==w)canvas.width=w;
   if(canvas.height!==h)canvas.height=h;
   const ctx=canvas.getContext('2d',{alpha:false});
   if(!ctx)return;
   if(composed&&composed.width===w&&composed.height===h)
    ctx.drawImage(composed,0,0,w,h);
   else drawFrame(ctx,state,w,h);
  });
 }
 function startBroadcastPreviewLoop(){
  if(broadcastPreviewRaf||typeof requestAnimationFrame!=='function')return;
  const step=now=>{
   if(!isCameraOn()||!document.querySelector('canvas[data-broadcast-preview]')){
    broadcastPreviewRaf=0;return;
   }
   if(now-lastBroadcastPreviewFrame>=32){
    lastBroadcastPreviewFrame=now;
    paintBroadcastPreview();
   }
   broadcastPreviewRaf=requestAnimationFrame(step);
  };
  broadcastPreviewRaf=requestAnimationFrame(step);
 }
 function paintStreetballPreview(){
  if(!overlayReader)return;
  const canvases=document.querySelectorAll('canvas[data-street-overlay]');
  if(!canvases.length)return;
  let state;try{state=overlayReader()}catch(e){return}
  if(!state||state.sport!=='basketball'||state.options?.basketballMode!=='streetball')return;
  const high=state.options?.quality==='1080p';
  const w=high?1080:720,h=high?1920:1280;
  canvases.forEach(canvas=>{
   if(canvas.width!==w)canvas.width=w;
   if(canvas.height!==h)canvas.height=h;
   const ctx=canvas.getContext('2d',{alpha:false});
   if(!ctx)return;
   // WYSIWYG: display the complete recorded frame, not an overlay stretched
   // over a differently cropped device screen.
   if(drawCanvas&&isRecording()&&drawCanvas.width===w&&drawCanvas.height===h){
    ctx.drawImage(drawCanvas,0,0,w,h);
   }else drawFrame(ctx,state,w,h);
  });
 }
 function startStreetballPreviewLoop(){
  if(previewRaf||typeof requestAnimationFrame!=='function')return;
  const step=now=>{
   if(!isCameraOn()||!document.querySelector('canvas[data-street-overlay]')){
    previewRaf=0;return;
   }
   if(now-lastPreviewFrame>=40){
    lastPreviewFrame=now;
    paintStreetballPreview();
   }
   previewRaf=requestAnimationFrame(step);
  };
  previewRaf=requestAnimationFrame(step);
 }
 window.addEventListener?.('resize',()=>{
  if(typeof requestAnimationFrame==='function')requestAnimationFrame(()=>{paintStreetballPreview();paintBroadcastPreview()});
  else {paintStreetballPreview();paintBroadcastPreview()}
 });

 // On-air Streetball score typography, shared by preview and MP4 compositor.
 // Preserve every digit (15, 100, 1000…) with measurement-based font fitting;
 // never call text(), which deliberately abbreviates long labels with an ellipsis.
 function drawStreetballScore(ctx,raw,x,y,maxWidth,preferredSize){
  const digits=String(Math.max(0,Math.floor(Number(raw)||0)));
  ctx.save();
  ctx.textAlign='center';ctx.textBaseline='middle';
  let fontSize=Math.max(14,preferredSize);
  const applyFont=()=>{ctx.font='900 '+Math.round(fontSize)+'px system-ui,Arial,sans-serif'};
  applyFont();
  // Retain bold numerals while fitting inside the camera's safe margins.
  while(fontSize>13 && ctx.measureText(digits).width>maxWidth){
   fontSize=Math.max(13,fontSize-2);
   applyFont();
  }
  const outline=Math.max(3,fontSize*.09);
  ctx.lineJoin='round';ctx.lineWidth=outline;
  ctx.strokeStyle='rgba(4,12,17,.94)';
  ctx.shadowColor='rgba(0,0,0,.72)';
  ctx.shadowBlur=Math.max(5,fontSize*.20);
  ctx.shadowOffsetY=Math.max(2,fontSize*.065);
  // Canvas maxWidth is a last-resort fit for exceptionally large scores:
  // it compresses text but does not substitute "..." for any digit.
  ctx.strokeText(digits,x,y,maxWidth);
  ctx.shadowColor='transparent';ctx.shadowBlur=0;ctx.shadowOffsetY=0;
  const shine=ctx.createLinearGradient(0,y-fontSize*.54,0,y+fontSize*.54);
  shine.addColorStop(0,'#ffffff');
  shine.addColorStop(.67,'#ffffff');
  shine.addColorStop(1,'#dbece8');
  ctx.fillStyle=shine;
  ctx.fillText(digits,x,y,maxWidth);
  ctx.restore();
 }
 function drawStreetball(ctx,s,w,h){
  const o=s.options||{},l=s.live||{},count=o.streetFormat==='1v1'?1:o.streetFormat==='2v2'?2:0;
  const scale=Math.max(.32,Math.min(1.45,(Number(o.streetOverlaySize)||45)/60)),
   alpha=clamp(o.streetOverlayOpacity??100,30,100)/100,accent=o.streetOverlayColor||'#E66D48';
  const names=s.teams||[{name:''},{name:''}],players=o.streetPlayers||[[],[]];
  // Dimensions are based on FRAME WIDTH, not mixed CSS px / video-frame height.
  // This keeps the appearance proportional in the camera preview and MP4 output.
  ctx.save();ctx.globalAlpha=alpha;
  const scoreH=w*(count?0.145:0.17)*scale;
  const radius=w*(count===2?0.051:0.066)*scale;
  // Share one mirrored horizontal edge distance and a common vertical anchor.
  const positions=Array.isArray(o.streetPositions)?o.streetPositions:[{x:.14,y:.52},{x:.86,y:.52}];
  const savedEdge=Number(positions[0]?.x);
  const requestedEdge=clamp(Number.isFinite(savedEdge)?savedEdge:.14,.10,.24);
  const outline=Math.max(2,w*.004*scale)+Math.max(1,w*.002*scale);
  const edgeDistance=Math.max(w*requestedEdge,radius+outline+w*.006);
  const savedY=Number(positions[0]?.y);
  const requestedY=clamp(Number.isFinite(savedY)?savedY:.52,.23,.70);
  const nameOffset=(!count||o.streetShowTeams)&&names.some(t=>String(t?.name||'').trim())?w*.038*scale:0;
  const rowStep=radius*3.4+w*.025*scale;
  // Keep the second (lower) 2v2 portrait and name inside the 9:16 frame.
  const lastGroupTail=scoreH*.5+w*.018*scale+nameOffset+radius*2+
   (count===2?rowStep:0)+w*.021*scale+w*.017*scale;
  const centerY=Math.min(h*requestedY,h*.97-lastGroupTail);
  for(let i=0;i<2;i++){
   const cx=i?w-edgeDistance:edgeDistance;
   const scoreY=centerY-scoreH*.5;
   // Fit all digits inside the video when the score is on-screen.
   // If the operator deliberately chooses off-edge placement, honor the crop.
   const inwardSpace=2*Math.min(cx,w-cx)-w*.012;
   const scoreSafeWidth=Math.max(w*.025,Math.min(w*.18,inwardSpace));
   // Score numerals float directly over the camera: NO box or rectangle.
   // Unlike the normal label helper, scores NEVER get shortened to an ellipsis.
   drawStreetballScore(ctx,l.scores?.[i]??0,cx,scoreY+scoreH*.48,
    scoreSafeWidth,scoreH*.97);
   let faceTop=scoreY+scoreH+w*.018*scale;
   const teamName=String(names[i]?.name||'').trim();
   if((!count||o.streetShowTeams)&&teamName){
    text(ctx,teamName,cx,faceTop,w*.029*scale,'900','center','#fff',w*(count?.20:.23)*scale);
    faceTop+=w*.038*scale;
   }
   if(count){
    // Each 2v2 side is a vertical column of two circular player portraits.
    for(let j=0;j<count;j++){
     const p=players?.[i]?.[j]||{name:'Player'},px=cx;
     const py=faceTop+radius+j*rowStep;
     ctx.save();ctx.beginPath();ctx.arc(px,py,radius,0,Math.PI*2);ctx.clip();
     const img=imageFor(p.photo||'');
     if(img){
      const crop=Math.min(img.width,img.height);
      ctx.drawImage(img,(img.width-crop)/2,(img.height-crop)/2,crop,crop,px-radius,py-radius,radius*2,radius*2);
     }else{
      ctx.fillStyle='rgba(30,60,65,.92)';ctx.fillRect(px-radius,py-radius,radius*2,radius*2);
      ctx.beginPath();ctx.arc(px,py-radius*.23,radius*.34,0,Math.PI*2);ctx.fillStyle='#bcd5d2';ctx.fill();
      ctx.beginPath();ctx.ellipse?.(px,py+radius*.67,radius*.63,radius*.54,0,0,Math.PI*2);ctx.fill();
     }
     ctx.restore();
     ctx.beginPath();ctx.arc(px,py,radius,0,Math.PI*2);
     ctx.lineWidth=Math.max(2,w*.004*scale);ctx.strokeStyle='#fff';ctx.stroke();
     ctx.beginPath();ctx.arc(px,py,radius+Math.max(1,w*.002*scale),0,Math.PI*2);
     ctx.lineWidth=Math.max(1,w*.002*scale);ctx.strokeStyle=accent;ctx.stroke();
     const playerNameWidth=Math.max(w*.03,Math.min(count===2?w*.16:w*.24,2*edgeDistance-w*.015));
     text(ctx,p.name||'Player',px,py+radius+w*.021*scale,w*(count===2?.023:.028)*scale,'850','center','#fff',playerNameWidth);
    }
   }
  }
  ctx.restore();
  // Winning-score graphic is drawn into both the live preview and saved MP4.
  // Never render it through the operator UI, which is intentionally not recorded.
  const winTarget=clamp(Math.round(Number(o.streetWinTarget)||21),1,999);
  const a=Math.max(0,Number(l.scores?.[0])||0),b=Math.max(0,Number(l.scores?.[1])||0);
  const winSide=((a>=winTarget||b>=winTarget)&&a!==b)?(a>b?0:1):-1;
  const winName=winSide<0?'':count
   ?(o.streetPlayers?.[winSide]?.[0]?.name||'Player')
   :(names[winSide]?.name?.trim()||(winSide===0?'LEFT SIDE':'RIGHT SIDE'));
  const tag=winSide>=0?'WINNER  ·  '+winName:'FIRST TO '+winTarget;
  const tagW=w*(winSide>=0?.62:.40),tagH=w*.068;
  // The target / winner banner belongs at the top of the ACTUAL video frame.
  // Use a small video-relative top inset; preview and MP4 share this renderer.
  const tagY=Math.max(h*.012,w*.012)+tagH*.5;
  ctx.save();
  block(ctx,(w-tagW)/2,tagY-tagH/2,tagW,tagH,winSide>=0?'rgba(22,112,80,.90)':'rgba(6,26,34,.83)',tagH*.50);
  text(ctx,tag,w/2,tagY,Math.max(14,w*.026),'850','center','#fff',tagW*.88);
  ctx.restore();
  // Include on-air graphics here, never in the operator UI, so preview and export match.
  if(l.timeout&&!l.slides){
   const bw=w*.24,bh=w*.056;
   block(ctx,(w-bw)/2,h*.055,bw,bh,'rgba(173,65,53,.91)',bh/2);
   text(ctx,'TIMEOUT',w*.5,h*.055+bh*.5,bh*.43,'900','center','#fff');
  }
  if(o.broadcast?.showLeagueName&&o.broadcast?.leagueName){
   text(ctx,o.broadcast.leagueName,w*.5,h*.105,w*.04,'900','center','#fff',w*.72);
  }
 }
 function drawPickleballBar(ctx,s,w,h){
  const o=s.options||{},l=s.live||{},sz=clamp(o.overlaySize??45,15,115)/100,alpha=clamp(o.overlayOpacity??100,30,100)/100;
  const ww=w*.53*sz,hh=h*.21*sz,x=(w-ww)/2,y=h-hh-h*.03;
  const teams=s.teams||[{name:''},{name:''}],serve=l.pickleballServer===1?1:0;
  const rally=o.pickleballScoring==='rally',doubles=o.pickleballType!=='singles';
  const call=[Math.max(0,Number(l.scores?.[serve])||0),Math.max(0,Number(l.scores?.[1-serve])||0)]
   .concat(!rally&&doubles?[l.pickleballServeNumber===1?1:2]:[]).join('–');
  ctx.save();ctx.globalAlpha=alpha;
  block(ctx,x,y,ww,hh,'rgba(5,20,27,.94)',7);
  block(ctx,x,y+hh-3,ww,3,color(o.overlayColor||'#E66D48'));
  for(let i=0;i<2;i++){
   const yy=y+hh*(i?.60:.29);
   const team=teams[i]?.name?.trim();
   if(team)text(ctx,team,x+ww*.05,yy,hh*.16,'750','left','#fff',ww*.43);
   if(serve===i)text(ctx,'SERVE',x+ww*.67,yy,hh*.12,'750','center','#9df9d8');
   text(ctx,String(l.scores?.[i]||0),x+ww*.94,yy,hh*.28,'900','right');
  }
  const won=l.pickleballWinner===0||l.pickleballWinner===1;
  const status=won?'GAME WON · '+(l.pickleballWinner===0?'LEFT':'RIGHT'):'GAME '+(l.pickleballGame||1)+' · '+(rally?'RALLY':'SIDE-OUT');
  text(ctx,status,x+ww*.05,y+hh*.91,hh*.105,'850','left','#cddbd3',ww*.57);
  text(ctx,call,x+ww*.95,y+hh*.91,hh*.14,'900','right','#fff',ww*.37);
  ctx.restore();
 }
 const sponsorVideos=new Map();
 function videoFor(uri){
  if(typeof uri!=='string'||!uri.startsWith('blob:'))return null;
  if(!sponsorVideos.has(uri)){
   const v=document.createElement('video');v.muted=true;v.defaultMuted=true;v.volume=0;
   v.autoplay=true;v.loop=true;v.playsInline=true;v.preload='auto';
   v.src=uri;v.addEventListener('loadeddata',()=>{v.play().catch(()=>{});}, {once:true});
   v.play().catch(()=>{});
   sponsorVideos.set(uri,v);
  }
  return sponsorVideos.get(uri);
 }
 function releaseSponsorVideo(uri){
  const video=sponsorVideos.get(uri);
  if(!video)return;
  video.pause();
  video.removeAttribute('src');
  try{video.load()}catch(_){}
  sponsorVideos.delete(uri);
 }
 function drawSponsor(ctx,s,w,h){
  const live=s.live||{},sp=s.sponsors?.[live.slideIndex]||{name:'SPONSOR'};
  ctx.fillStyle='#102c2b';ctx.fillRect(0,0,w,h);
  const accent=s.options?.overlayColor||'#e66d48';
  block(ctx,w*.08,h*.15,w*.84,h*.7,color(accent,.2),Math.min(35,w*.03));
  const img=imageFor(sp.img);
  if(img){const limW=w*.24,limH=h*.25,m=Math.min(limW/img.width,limH/img.height);ctx.drawImage(img,(w-img.width*m)/2,h*.22,img.width*m,img.height*m);}
   const sponsorVideo=videoFor(sp.video);
   if(sponsorVideo&&sponsorVideo.readyState>=2&&sponsorVideo.videoWidth){
    const limW=w*.62,limH=h*.37,m=Math.min(limW/sponsorVideo.videoWidth,limH/sponsorVideo.videoHeight);
    ctx.drawImage(sponsorVideo,(w-sponsorVideo.videoWidth*m)/2,h*.24,sponsorVideo.videoWidth*m,sponsorVideo.videoHeight*m);
   }
  text(ctx,'IMG LIVE STUDIO  •  OFFICIAL PARTNER',w/2,h*.20,Math.max(16,w*.015),'850','center','#e6faef');
  text(ctx,sp.name||'SPONSOR',w/2,h*.58,Math.max(24,w*.055),'900','center','#fff',w*.87);
 }
 function drawFrame(ctx,s,w,h){
  // Local recording and livestreams use the same real scoreboard graphics.
  if(s.live?.slides)drawSponsor(ctx,s,w,h);
  else{
   drawVideo(ctx,sourceVideo,w,h);
   if(s.sport==='pickleball')drawPickleballBar(ctx,s,w,h);
   else if(s.sport==='basketball'&&s.options?.basketballMode==='streetball')drawStreetball(ctx,s,w,h);
   else if(!drawNativeGraphic(ctx,s,w,h))drawGameBar(ctx,s,w,h);
   if(!(s.sport==='basketball'&&s.options?.basketballMode==='streetball')){
    /* Timeout is an operator control only; never burn a timeout banner into the outgoing video. */
    if(s.options?.broadcast?.showLeagueName&&s.options.broadcast.leagueName)text(ctx,s.options.broadcast.leagueName,w/2,h*.08,Math.max(15,w*.015),'900','center','#fff',w*.7);
   }
  }
 }

 function chooseMime(withAudio=false){
  if(!window.MediaRecorder||typeof MediaRecorder.isTypeSupported!=='function')return '';
  const types=withAudio?
   ['video/mp4;codecs="avc1.42E01E,mp4a.40.2"','video/mp4;codecs=h264,aac','video/mp4']:
   ['video/mp4;codecs="avc1.42E01E"','video/mp4;codecs=h264','video/mp4'];
  return types.find(type=>MediaRecorder.isTypeSupported(type))||'';
 }
 async function storeMp4(blob,metadata={}){
  if(!(blob instanceof Blob)||blob.size<1)throw new Error('No encoded MP4 video was produced.');
  const createdAt=Date.now(),name='IMG-LIVE-'+new Date(createdAt).toISOString().replace(/[:.]/g,'-')+'.mp4';
  const clip={id:String(createdAt)+'-'+Math.random().toString(36).slice(2,7),name,createdAt,blob,mime:'video/mp4',size:blob.size,
   duration:Math.max(0,Math.round((createdAt-startedAt)/1000)),metadata};
  latestClip=clip;let saved=false,error='';
  try{await saveClip(clip);saved=true}catch(e){error=readableError(e);}
  return {...clip,saved,error};
 }
 async function startRecording(readState,{quality='720p',orientation='landscape',fps=30,microphone=false,forceWebCodecs=false}={}){
  if(isRecording())throw new Error('A recording is already running.');
  if(!isCameraOn())throw new Error('Enable the camera first to record.');
  const high=quality==='1080p',portrait=orientation==='portrait',rate=clamp(fps,15,60);
  const outputW=portrait?(high?1080:720):(high?1920:1280);
  const outputH=portrait?(high?1920:1280):(high?1080:720);
  const canvas=document.createElement('canvas');canvas.width=outputW;canvas.height=outputH;
  const ctx=canvas.getContext('2d',{alpha:false});if(!ctx)throw new Error('Video canvas unavailable.');
  drawCanvas=canvas;stateReader=readState;
  const mime=chooseMime(microphone);
  const nativeMp4=Boolean(!forceWebCodecs&&mime&&HTMLCanvasElement.prototype.captureStream);
  // Never claim a recording includes commentary when the device can only
  // produce video-only MP4. A false-success silent MP4 cannot be repaired.
  if(microphone&&!nativeMp4){
   drawCanvas=null;stateReader=null;
   throw new Error('This device cannot record MP4 with microphone audio at the selected quality. Try a supported phone or change audio settings before recording.');
  }
  if(nativeMp4){
   const stream=canvas.captureStream(rate);recorderStream=stream;
   if(microphone){
    try{
     for(let attempt=0;attempt<3;attempt++){
      try{microphoneStream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});break;}
      catch(error){if(error?.name!=='NotReadableError'||attempt===2)throw error;await new Promise(resolve=>setTimeout(resolve,500*(attempt+1)));}
     }
     for(const track of microphoneStream.getAudioTracks())stream.addTrack(track);}
    catch(e){
     stream.getTracks().forEach(t=>{try{t.stop()}catch(_){}});
     microphoneStream=null;recorderStream=null;drawCanvas=null;stateReader=null;
     throw new Error('Microphone access failed. Offline recording was not started: '+(e?.name==='NotReadableError'?'Microphone is busy. Close other recording apps, then retry.':readableError(e)));
    }
    if(!microphoneStream?.getAudioTracks().some(track=>track.readyState==='live')){
     stream.getTracks().forEach(t=>{try{t.stop()}catch(_){}});
     microphoneStream=null;recorderStream=null;drawCanvas=null;stateReader=null;
     throw new Error('No active microphone audio track. Recording cancelled to prevent a silent MP4.');
    }
   }
   try{recorder=new MediaRecorder(stream,{mimeType:mime,videoBitsPerSecond:high?(rate>=60?12000000:8000000):(rate>=60?5500000:4000000)});}
   catch(e){stream.getTracks().forEach(t=>t.stop());recorderStream=null;
    if(microphoneStream){microphoneStream.getTracks().forEach(t=>t.stop());microphoneStream=null;}
    drawCanvas=null;stateReader=null;
    if(microphone)throw new Error('MP4 encoder with microphone could not start: '+readableError(e));
    return startRecording(readState,{quality,orientation,fps,microphone:false,forceWebCodecs:true});}
   chunks=[];startedAt=Date.now();let running=true,drawFrameId=0;
   const draw=()=>{
    if(!running)return;
    try{drawFrame(ctx,stateReader(),canvas.width,canvas.height)}catch(e){}
    drawFrameId=requestAnimationFrame(draw);
   };
   try{draw();recorder.start(1000)}catch(e){running=false;cancelAnimationFrame(drawFrameId);
    stream.getTracks().forEach(t=>t.stop());recorder=null;recorderStream=null;
    microphoneStream=null;drawCanvas=null;stateReader=null;throw e;}
   recorder.addEventListener('dataavailable',e=>{if(e.data?.size)chunks.push(e.data);});
   recorder._cleanup=()=>{
    running=false;cancelAnimationFrame(drawFrameId);
    stream.getTracks().forEach(t=>t.stop());recorderStream=null;drawCanvas=null;stateReader=null;
    if(microphoneStream){microphoneStream.getTracks().forEach(t=>t.stop());microphoneStream=null;}
   };
   return {mime:'video/mp4',fps:rate,audio:Boolean(microphoneStream),method:'native-mp4'};
  }
  // Video-only H.264 MP4 fallback, encoded and multiplexed wholly on device.
  if(!window.Mp4Muxer||!window.VideoEncoder||!window.VideoFrame)
   throw new Error('MP4 encoding is unavailable in this browser. Try an updated Android Chrome. No WebM recording will be created.');
  const muxerLib=window.Mp4Muxer,VideoEncoderImpl=window.VideoEncoder,VideoFrameImpl=window.VideoFrame;
  let config=null;
  const levelCandidates=high?['avc1.64002a','avc1.42002a','avc1.42E01F']:['avc1.42E01F','avc1.42002a','avc1.64002a'];
  for(const codec of levelCandidates){
   const test={codec,width:outputW,height:outputH,bitrate:high?(rate>=60?12000000:8000000):(rate>=60?5500000:4000000),framerate:rate,hardwareAcceleration:'prefer-hardware'};
   try{if((await VideoEncoderImpl.isConfigSupported(test)).supported){config=test;break}}catch(e){}
  }
  if(!config){drawCanvas=null;stateReader=null;throw new Error('This phone cannot encode H.264 MP4 at '+quality+' / '+rate+' FPS. Try 720p / 30 FPS.');}
  const muxer=new muxerLib.Muxer({target:new muxerLib.ArrayBufferTarget(),
   video:{codec:'avc',width:outputW,height:outputH},fastStart:'in-memory',firstTimestampBehavior:'offset'});
  let encoderProblem=null;
  const encoder=new VideoEncoderImpl({output:(chunk,meta)=>muxer.addVideoChunk(chunk,meta),error:e=>{encoderProblem=e;}});
  try{encoder.configure(config)}catch(e){encoder.close();throw new Error('MP4 encoder could not start: '+readableError(e))}
  startedAt=Date.now();let running=true,frameIndex=0,lastFrame=-Infinity,drawFrameId=0;
  const recording={state:'recording',mimeType:'video/mp4',_webCodecs:true,encoder,muxer,
   _stop(){running=false;cancelAnimationFrame(drawFrameId)},
   _close(){try{encoder.close()}catch(e){}drawCanvas=null;stateReader=null},
   _error(){return encoderProblem}};
  recorder=recording;
  const origin=performance.now();
  const draw=now=>{
   if(!running)return;
   try{
    if(now-lastFrame>=1000/rate-2){
     drawFrame(ctx,stateReader(),canvas.width,canvas.height);
     if(encoder.encodeQueueSize<5){
      const frame=new VideoFrameImpl(canvas,{timestamp:Math.max(0,Math.round((now-origin)*1000))});
      encoder.encode(frame,{keyFrame:frameIndex%Math.max(1,rate*3)===0});
      frame.close();frameIndex++;
     }
     lastFrame=now;
    }
   }catch(e){encoderProblem=e;running=false}
   if(running)drawFrameId=requestAnimationFrame(draw);
  };
  drawFrameId=requestAnimationFrame(draw);
  return {mime:'video/mp4',fps:rate,audio:false,method:'webcodecs-mp4'};
 }
 async function stopRecording(metadata={}){
  const r=recorder;if(!r)return null;
  recorder=null;
  if(r._webCodecs){
   r.state='inactive';r._stop();
   try{
    await r.encoder.flush();
    if(r._error())throw r._error();
    r.muxer.finalize();
    const buffer=r.muxer.target.buffer;
    r._close();
    return await storeMp4(new Blob([buffer],{type:'video/mp4'}),metadata);
   }catch(e){r._close();return {saved:false,error:'MP4 recording could not finish: '+readableError(e)};}
  }
  return new Promise(resolve=>{
   r.addEventListener('stop',async()=>{
    r._cleanup?.();
    const data=chunks;chunks=[];
    try{resolve(await storeMp4(new Blob(data,{type:'video/mp4'}),metadata));}
    catch(e){resolve({saved:false,error:readableError(e)});}
   },{once:true});
   try{if(r.state!=='inactive'){r.requestData();r.stop();}else r.dispatchEvent(new Event('stop'));}
   catch(e){r._cleanup?.();resolve({saved:false,error:readableError(e)});}
  });
 }
 window.addEventListener?.('beforeunload',e=>{if(isRecording()){e.preventDefault();e.returnValue='';}});

 // A separate real-time WebM capture for a self-hosted relay. This reuses the
 // exact scoreboard compositor as local MP4 recording, without touching MP4.
 async function startLiveCapture(readState,{quality='720p',orientation='landscape',fps=30,microphone=true,onError=null}={},onChunk){
  if(isRecording()||liveCapture)throw new Error('Another video capture is already running.');
  if(!isCameraOn())throw new Error('Enable the broadcaster camera first.');
  if(typeof onChunk!=='function')throw new Error('Live video receiver is missing.');
  if(!HTMLCanvasElement.prototype.captureStream||typeof MediaRecorder==='undefined')
   throw new Error('This browser cannot capture live video; update Chrome on Android.');
  const mime=['video/webm;codecs=vp8,opus','video/webm;codecs=vp9,opus','video/webm']
   .find(m=>MediaRecorder.isTypeSupported(m));
  if(!mime)throw new Error('Chrome WebM live encoding is unavailable on this device.');
  const portrait=orientation==='portrait',high=quality==='1080p';
  const rate=Number(fps)===60?60:30;
  const canvas=document.createElement('canvas');
  canvas.width=portrait?(high?1080:720):(high?1920:1280);
  canvas.height=portrait?(high?1920:1280):(high?1080:720);
  const ctx=canvas.getContext('2d',{alpha:false});
  if(!ctx)throw new Error('Unable to create live scoreboard video.');
  let mic=null,stream=null,mediaRecorder=null,raf=0,active=true;
  const draw=()=>{
   if(!active)return;
   try{drawFrame(ctx,readState(),canvas.width,canvas.height)}catch(e){}
   raf=requestAnimationFrame(draw);
  };
  try{
   if(microphone){
    mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
   }
   stream=canvas.captureStream(rate);
   for(const track of mic?.getAudioTracks()||[])stream.addTrack(track);
   mediaRecorder=new MediaRecorder(stream,{mimeType:mime,
    videoBitsPerSecond:high?(rate===60?12000000:8000000):(rate===60?6000000:4000000),
    audioBitsPerSecond:128000});
   mediaRecorder.addEventListener('dataavailable',e=>{if(e.data?.size)onChunk(e.data)});
   mediaRecorder.addEventListener('error',e=>{if(active){const err=e?.error||new Error('Live video encoder failed.');if(typeof onError==='function')onError(err);else console.warn('Live capture failed',err?.name||'encoding error')}});
   liveCapture={canvas,stop:()=>{
    if(!active)return;
    active=false;cancelAnimationFrame(raf);
    try{if(mediaRecorder?.state!=='inactive')mediaRecorder.stop()}catch(e){}
    for(const track of stream?.getTracks()||[])try{track.stop()}catch(e){}
    for(const track of mic?.getTracks()||[])try{track.stop()}catch(e){}
    liveCapture=null;
   },hasAudio:!!mic?.getAudioTracks().length};
   draw();
   mediaRecorder.start(500);
   return {mime,hasAudio:liveCapture.hasAudio,stop:()=>stopLiveCapture()};
  }catch(e){
   active=false;cancelAnimationFrame(raf);
   for(const track of stream?.getTracks()||[])try{track.stop()}catch(_){}
   for(const track of mic?.getTracks()||[])try{track.stop()}catch(_){}
   liveCapture=null;throw e;
  }
 }
 function stopLiveCapture(){liveCapture?.stop();}

 window.IMGMedia={canCamera,isCameraOn,isRecording,openCamera,attach,paintSetupScoreboard,paintStreetballPreview,releaseSponsorVideo,stopCamera,startLiveCapture,stopLiveCapture,startRecording,stopRecording,downloadClip,listClips,getClip,deleteClip,readableError,latestClip:()=>latestClip};
})();