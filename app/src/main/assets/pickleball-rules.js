/* IMG LIVE Studio — USA Pickleball 2026 scoring engine.
 * Standard side-out is default; provisional rally scoring is optional.
 * Pure functions so operator controls, preview and MP4 use the same match state.
 */
(()=>{'use strict';
 const clampScore=(n)=>Math.max(0,Math.min(999,Math.floor(Number(n)||0)));
 const target=o=>[11,15,21].includes(Number(o?.pickleballTarget))?Number(o.pickleballTarget):11;
 const mode=o=>o?.pickleballScoring==='rally'?'rally':'sideout';
 const matchGames=o=>o?.pickleballMatch==='best5'?3:o?.pickleballMatch==='best3'?2:1;
 const isDoubles=o=>o?.pickleballType!=='singles';
 const resultFor=(scores,o)=>{const goal=target(o),a=clampScore(scores?.[0]),b=clampScore(scores?.[1]);
  return Math.max(a,b)>=goal&&Math.abs(a-b)>=2?(a>b?0:1):-1;};
 const call=(s,o)=>{const t=s?.scores||[0,0],serve=s?.pickleballServer===1?1:0,rec=1-serve;
  return [clampScore(t[serve]),clampScore(t[rec])]
   .concat(mode(o)==='sideout'&&isDoubles(o)?[s?.pickleballServeNumber===1?1:2]:[]).join('–');};
 const initial=(o,serving=0,game=1,previousGames=[0,0])=>{
  const side=serving===1?1:0;
  return {scores:[0,0],pickleballServer:side,
   pickleballServeNumber:mode(o)==='sideout'&&isDoubles(o)?2:1,
   pickleballGames:[...previousGames],pickleballGame:game,pickleballRallies:0,
   pickleballWinner:-1,pickleballMatchWinner:-1,pickleballRallyMessage:'Game ready · '+(side?'Right':'Left')+' serves'};
 };
 const messageSide=i=>i?'Right':'Left';
 const award=(s,side,o)=>{
  if(side!==0&&side!==1)return {error:'Choose the left or right rally winner.'};
  if(s.pickleballWinner===0||s.pickleballWinner===1)return {error:'Game finished. Use Next Game, or Undo to correct the last rally.'};
  const n={...s,scores:(s.scores||[0,0]).map(clampScore),pickleballGames:[...(s.pickleballGames||[0,0])]};
  n.pickleballRallies=Math.max(0,Math.floor(Number(s.pickleballRallies)||0))+1;
  let serving=s.pickleballServer===1?1:0,second=s.pickleballServeNumber===2?2:1;
  if(mode(o)==='rally'){
   n.scores[side]++;
   if(side!==serving){n.pickleballServer=side;n.pickleballServeNumber=1;n.pickleballRallyMessage='Side out · '+messageSide(side)+' gains point and serve';}
   else n.pickleballRallyMessage=messageSide(side)+' scores and keeps serve';
  }else if(side===serving){
   n.scores[side]++;
   n.pickleballRallyMessage=messageSide(side)+' scores and keeps serve';
  }else if(isDoubles(o)&&second===1){
   n.pickleballServeNumber=2;
   n.pickleballRallyMessage='Second server · '+messageSide(serving)+' keeps serve (no point)';
  }else{
   n.pickleballServer=side;n.pickleballServeNumber=1;
   n.pickleballRallyMessage='Side out · '+messageSide(side)+' serves (no point)';
  }
  n.pickleballWinner=resultFor(n.scores,o);
  if(n.pickleballWinner!==-1){
   n.pickleballGames[n.pickleballWinner]=(n.pickleballGames[n.pickleballWinner]||0)+1;
   n.pickleballMatchWinner=n.pickleballGames[n.pickleballWinner]>=matchGames(o)?n.pickleballWinner:-1;
   n.pickleballRallyMessage=messageSide(n.pickleballWinner)+(n.pickleballMatchWinner!==-1?' wins the match!':' wins the game!');
  }
  return {state:n};
 };
 const correct=(s,side,delta,o)=>{
  if(side!==0&&side!==1||![-1,1].includes(delta))return {error:'Invalid correction.'};
  const n={...s,scores:(s.scores||[0,0]).map(clampScore),pickleballGames:[...(s.pickleballGames||[0,0])]};
  const before=n.pickleballWinner===0||n.pickleballWinner===1?n.pickleballWinner:resultFor(n.scores,o);
  n.scores[side]=clampScore(n.scores[side]+delta);
  const after=resultFor(n.scores,o);
  if(before!==after){
   if(before>=0)n.pickleballGames[before]=Math.max(0,(n.pickleballGames[before]||0)-1);
   if(after>=0)n.pickleballGames[after]=(n.pickleballGames[after]||0)+1;
  }
  n.pickleballWinner=after;
  n.pickleballMatchWinner=(n.pickleballGames||[]).findIndex(n=>n>=matchGames(o));
  n.pickleballRallyMessage='Score corrected · '+messageSide(side)+' '+(delta>0?'+1':'−1');
  return {state:n};
 };
 const next=(s,o)=>{
  if(s.pickleballMatchWinner===0||s.pickleballMatchWinner===1)return {error:'Match completed. Stop the broadcast to finish.'};
  if(s.pickleballWinner!==0&&s.pickleballWinner!==1)return {error:'Finish the current game before starting the next game.'};
  const n=initial(o,1-(s.pickleballServer===1?1:0),(s.pickleballGame||1)+1,s.pickleballGames||[0,0]);
  n.pickleballRallyMessage='Game '+n.pickleballGame+' ready · '+messageSide(n.pickleballServer)+' serves';
  return {state:n};
 };
 window.IMGPickleballRules={initial,award,correct,next,call,target,mode,isDoubles,resultFor,matchGames};
})();
