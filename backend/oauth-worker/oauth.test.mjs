import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {DatabaseSync} from 'node:sqlite';

const source=await readFile(new URL('./src/index.js',import.meta.url),'utf8');
const schema=await readFile(new URL('./migrations/0001_oauth_transactional.sql',import.meta.url),'utf8');
const schema2=await readFile(new URL('./migrations/0002_access_token_lifecycle.sql',import.meta.url),'utf8');
const schema3=await readFile(new URL('./migrations/0003_youtube_private_drafts.sql',import.meta.url),'utf8');
const schema5=await readFile(new URL('./migrations/0005_youtube_private_ingest.sql',import.meta.url),'utf8');
const schema6=await readFile(new URL('./migrations/0006_youtube_ingest_diagnostics.sql',import.meta.url),'utf8');
const {default:worker}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const api='https://oauth.example.test';
const appOrigin='https://appassets.androidplatform.net';
const ctype={'origin':appOrigin,'content-type':'application/json'};

function memoryD1(){
 const db=new DatabaseSync(':memory:');
 db.exec('PRAGMA foreign_keys=ON;');
 db.exec(schema);
 db.exec(schema2);
 db.exec(schema3);
 db.exec(schema5);
 db.exec(schema6);
 const make=(sql,args=[])=>({
  sql,args,
  bind(...values){return make(sql,values)},
  async first(){return db.prepare(sql).get(...args)||null},
  async all(){return {success:true,results:db.prepare(sql).all(...args)}},
  async run(){const result=db.prepare(sql).run(...args);return {success:true,meta:{changes:result.changes}}}
 });
 return {
  prepare:sql=>make(sql),
  async batch(stmts){
   db.exec('BEGIN IMMEDIATE');
   try{
    const result=stmts.map(({sql,args})=>{
     const x=db.prepare(sql).run(...args);
     return {success:true,meta:{changes:x.changes}};
    });
    db.exec('COMMIT');
    return result;
   }catch(error){db.exec('ROLLBACK');throw error;}
  },
  execute(sql,...args){return db.prepare(sql).run(...args)},
  query(sql,...args){return db.prepare(sql).all(...args)}
 };
}
function makeEnv(overrides={}){
 return {
  OAUTH_ENABLED:'true',
  OAUTH_DB:memoryD1(),
  TOKEN_ENCRYPTION_KEY:'A'.repeat(43),
  PUBLIC_ORIGIN:api,
  GOOGLE_CLIENT_ID:'test-google-id',
  GOOGLE_CLIENT_SECRET:'test-google-secret',
  FACEBOOK_APP_ID:'test-facebook-id',
  FACEBOOK_APP_SECRET:'test-facebook-secret',
  ...overrides
 };
}
const call=(path,opts={},env)=>worker.fetch(new Request(api+path,opts),env);

async function authorize(env,provider='facebook',cookie){
 const headers={...ctype};
 if(cookie)headers.cookie=cookie;
 const response=await call('/api/connections/'+provider+'/authorize',{method:'POST',headers,body:'{}'},env);
 assert.equal(response.status,200,'OAuth authorize must succeed');
 const data=await response.json();
 const state=new URL(data.authorizationUrl).searchParams.get('state');
 const sessionCookie=response.headers.get('set-cookie').split(';')[0];
 assert.match(sessionCookie,/^__Host-img_session=/);
 return {state,pairingCode:data.pairingCode,cookie:sessionCookie,authorizationUrl:data.authorizationUrl};
}

test('deployment is disabled by default until explicitly enabled',async()=>{
 const env=makeEnv({OAUTH_ENABLED:'false'});
 const health=await call('/health',{},env);
 assert.equal(health.status,503);
 assert.equal((await health.json()).configured,false);
 const response=await call('/api/connections',{},env);
 assert.equal(response.status,503);
 assert.equal((await response.json()).error,'oauth_not_enabled');
});
test('health requires provider credentials and D1',async()=>{
 for(const overrides of [{FACEBOOK_APP_SECRET:undefined},{OAUTH_DB:undefined},{TOKEN_ENCRYPTION_KEY:'bad'}, {PUBLIC_ORIGIN:'http://oauth.example.test'}, {PUBLIC_ORIGIN:'https://oauth.example.test:8443'}]){
  const response=await call('/health',{},makeEnv(overrides));
  assert.equal(response.status,503);
 }
 const response=await call('/health',{},makeEnv());
 assert.equal(response.status,200);
 assert.equal((await response.json()).configured,true);
});
test('configured API fails closed without the D1 binding',async()=>{
 const response=await call('/api/connections',{},makeEnv({OAUTH_DB:undefined}));
 assert.equal(response.status,503);
});
test('preflight rejects cross-origin requests',async()=>{
 const env=makeEnv();
 const denied=await call('/api/connections/facebook/authorize',{method:'OPTIONS',headers:{origin:'https://attacker.example'}},env);
 assert.equal(denied.status,403);
 assert.equal(denied.headers.get('access-control-allow-origin'),null);
 const allowed=await call('/api/connections/facebook/authorize',{method:'OPTIONS',headers:{origin:appOrigin}},env);
 assert.equal(allowed.status,204);
 assert.equal(allowed.headers.get('access-control-allow-origin'),appOrigin);
});
test('authorization rejects forged origin and non-JSON bodies',async()=>{
 const env=makeEnv();
 assert.equal((await call('/api/connections/facebook/authorize',{method:'POST',headers:{origin:'https://attacker.example','content-type':'application/json'},body:'{}'},env)).status,403);
 assert.equal((await call('/api/connections/facebook/authorize',{method:'POST',headers:{origin:appOrigin,'content-type':'text/plain'},body:'{}'},env)).status,415);
});
test('pairing code must be cryptographically random format',async()=>{
 const response=await call('/api/connections/facebook/pairing',{method:'POST',headers:ctype,body:'{"pairingCode":"bad"}'},makeEnv());
 assert.equal(response.status,400);
});
test('invalid callback state and unknown routes return safe errors',async()=>{
 const env=makeEnv();
 assert.equal((await call('/api/connections/facebook/callback?code=x',{},env)).status,400);
 assert.equal((await call('/api/nope',{},env)).status,404);
});
test('OAuth state is hashed, never saved in plaintext',async()=>{
 const env=makeEnv(),attempt=await authorize(env);
 assert.equal(env.OAUTH_DB.query('SELECT state_hash FROM oauth_states').length,1);
 assert.notEqual(env.OAUTH_DB.query('SELECT state_hash FROM oauth_states')[0].state_hash,attempt.state);
});
test('provider session cookie is secure and Android-only',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const response=await call('/api/connections',{headers:{cookie:start.cookie,origin:appOrigin}},env);
 assert.deepEqual(await response.json(),{facebook:{connected:false},youtube:{connected:false}});
 assert.match(source,/__Host-img_session/);
 assert.match(source,/HttpOnly; Secure; SameSite=None; Path=\//);
});
test('pairing cannot be read or cancelled with a different session',async()=>{
 const env=makeEnv(),a=await authorize(env),b=await authorize(env);
 const response=await call('/api/connections/facebook/pairing',{method:'POST',headers:{...ctype,cookie:b.cookie},body:JSON.stringify({pairingCode:a.pairingCode})},env);
 assert.equal(response.status,403);
 const cancel=await call('/api/connections/facebook/cancel',{method:'POST',headers:{...ctype,cookie:b.cookie},body:JSON.stringify({pairingCode:a.pairingCode})},env);
 assert.equal(cancel.status,403);
 const verified=await call('/api/connections/facebook/pairing',{method:'POST',headers:{...ctype,cookie:a.cookie},body:JSON.stringify({pairingCode:a.pairingCode})},env);
 assert.equal((await verified.json()).status,'pending');
});
test('cancel and callback compete atomically: cancelled state cannot connect',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const cancel=await call('/api/connections/facebook/cancel',{method:'POST',headers:{...ctype,cookie:start.cookie},body:JSON.stringify({pairingCode:start.pairingCode})},env);
 assert.equal(cancel.status,200);
 assert.equal((await cancel.json()).cancelled,true);
 const callback=await call('/api/connections/facebook/callback?state='+start.state+'&code=abc',{},env);
 assert.equal(callback.status,400);
 assert.equal((await callback.json()).error,'authorization_cancelled');
 assert.equal(env.OAUTH_DB.query('SELECT * FROM oauth_accounts').length,0);
});
test('callback claim is single-use under simultaneous replay',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const url='/api/connections/facebook/callback?state='+start.state+'&error=access_denied';
 const results=await Promise.all([call(url,{},env),call(url,{},env)]);
 const errors=await Promise.all(results.map(async x=>(await x.json()).error));
 assert.deepEqual(errors.sort(),['authorization_declined','expired_or_replayed_authorization']);
 assert.equal(env.OAUTH_DB.query('SELECT status FROM oauth_pairings')[0].status,'declined');
});
test('provider token exchange uses POST and stores encrypted token only',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const fetchOriginal=globalThis.fetch;
 try{
  globalThis.fetch=async (target,options)=>{
   assert.equal(target,'https://graph.facebook.com/v24.0/oauth/access_token');
   assert.equal(options.method,'POST');
   assert.equal(options.body.get('client_secret'),'test-facebook-secret');
   return new Response(JSON.stringify({access_token:'private-facebook-token',expires_in:3600}),{status:200,headers:{'content-type':'application/json'}});
  };
  const callback=await call('/api/connections/facebook/callback?state='+start.state+'&code=abc',{},env);
  assert.equal(callback.status,200,'OAuth callback must succeed');
  const records=env.OAUTH_DB.query('SELECT provider,encrypted_token FROM oauth_accounts');
  assert.equal(records.length,1);
  assert.equal(records[0].provider,'facebook');
  assert.doesNotMatch(records[0].encrypted_token,/private-facebook-token/);
  assert.equal(JSON.parse(records[0].encrypted_token).version,1);
  const connections=await call('/api/connections',{headers:{cookie:start.cookie,origin:appOrigin}},env);
  assert.equal((await connections.json()).facebook.connected,true);
  assert.equal((await call('/api/connections/facebook/callback?state='+start.state+'&code=abc',{},env)).status,400);
 }finally{globalThis.fetch=fetchOriginal;}
});
test('simultaneous different-provider callbacks preserve both accounts',async()=>{
 const env=makeEnv(),facebook=await authorize(env,'facebook'),youtube=await authorize(env,'youtube',facebook.cookie);
 const fetchOriginal=globalThis.fetch;
 try{
  globalThis.fetch=async target=>{
   if(target==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true')return Response.json({items:[{id:'UC1234567890123456789012'}]});
   if(target==='https://oauth2.googleapis.com/token')return Response.json({access_token:'youtube-token',refresh_token:'youtube-refresh-token',token_type:'Bearer',expires_in:3600});
   if(target==='https://graph.facebook.com/v24.0/oauth/access_token')return Response.json({access_token:'facebook-token',expires_in:3600});
   throw Error('unexpected target');
  };
  const results=await Promise.all([
   call('/api/connections/facebook/callback?state='+facebook.state+'&code=abc',{},env),
   call('/api/connections/youtube/callback?state='+youtube.state+'&code=def',{},env)
  ]);
  assert.deepEqual(results.map(x=>x.status),[200,200]);
  const view=await call('/api/connections',{headers:{cookie:facebook.cookie,origin:appOrigin}},env);
  assert.deepEqual(await view.json(),{facebook:{connected:true},youtube:{connected:true}});
 }finally{globalThis.fetch=fetchOriginal;}
});
test('disconnect removes only the selected provider',async()=>{
 const env=makeEnv(),session=await authorize(env);
 env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at) VALUES(?,?,?,?,?)',session.cookie.split('=')[1],'facebook','{}',Date.now()+600000,Date.now()+600000);
 env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at) VALUES(?,?,?,?,?)',session.cookie.split('=')[1],'youtube','{}',Date.now()+600000,Date.now()+600000);
 const response=await call('/api/connections/facebook/disconnect',{method:'POST',headers:{...ctype,cookie:session.cookie},body:'{}'},env);
 assert.equal(response.status,200);
 const view=await call('/api/connections',{headers:{cookie:session.cookie,origin:appOrigin}},env);
 assert.deepEqual(await view.json(),{facebook:{connected:false},youtube:{connected:true}});
});
test('Meta client secret never appears in token request URL',()=>{
 assert.match(source,/fetch\("https:\/\/graph\.facebook\.com\/v24\.0\/oauth\/access_token",\{method:"POST"/);
 assert.doesNotMatch(source,/endpoint\.search\s*=\s*new URLSearchParams/);
});

test('global logout revokes session, provider tokens and all pending OAuth states atomically',async()=>{
 const env=makeEnv();
 const facebook=await authorize(env,'facebook');
 const youtube=await authorize(env,'youtube',facebook.cookie);
 const id=facebook.cookie.split('=')[1];
 for(const provider of ['facebook','youtube']){
  env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at) VALUES(?,?,?,?,?)',id,provider,'{}',Date.now()+600000,Date.now()+600000);
 }
 const logout=await call('/api/connections/logout',{method:'POST',headers:{...ctype,cookie:facebook.cookie},body:'{}'},env);
 assert.equal(logout.status,200);
 assert.equal((await logout.json()).signedOut,true);
 assert.match(logout.headers.get('set-cookie'),/^__Host-img_session=;/);
 assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
 for(const table of ['oauth_sessions','oauth_accounts','oauth_pairings','oauth_states'])
  assert.equal(env.OAUTH_DB.query('SELECT * FROM '+table).length,0,table+' should be empty');
 const after=await call('/api/connections',{headers:{...ctype,cookie:facebook.cookie}},env);
 assert.deepEqual(await after.json(),{facebook:{connected:false},youtube:{connected:false}});
 for(const state of [facebook.state,youtube.state]){
  const denied=await call('/api/connections/facebook/callback?state='+state+'&code=invalid',{},env);
  assert.equal(denied.status,400);
 }
 const denied=await call('/api/connections/youtube/cancel',{method:'POST',headers:{...ctype,cookie:facebook.cookie},body:JSON.stringify({pairingCode:youtube.pairingCode})},env);
 assert.equal(denied.status,401);
});
test('logout rejects forged origin and wrong media type, and is idempotent',async()=>{
 const env=makeEnv();
 const forged=await call('/api/connections/logout',{method:'POST',headers:{origin:'https://attacker.example','content-type':'application/json'},body:'{}'},env);
 assert.equal(forged.status,403);
 const wrongType=await call('/api/connections/logout',{method:'POST',headers:{origin:appOrigin,'content-type':'text/plain'},body:'{}'},env);
 assert.equal(wrongType.status,415);
 const empty=await call('/api/connections/logout',{method:'POST',headers:ctype,body:'{}'},env);
 assert.equal(empty.status,200);
 assert.equal((await empty.json()).signedOut,true);
});

test('Facebook is not reported connected after its real access token expiry',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const original=globalThis.fetch;
 try{
  globalThis.fetch=async()=>Response.json({access_token:'meta-temporary',expires_in:3600});
  assert.equal((await call('/api/connections/facebook/callback?state='+start.state+'&code=abc',{},env)).status,200);
  const before=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
  assert.equal((await before.json()).facebook.connected,true);
  env.OAUTH_DB.execute("UPDATE oauth_accounts SET access_expires_at=? WHERE provider='facebook'",Date.now()-1000);
  const after=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
  assert.equal((await after.json()).facebook.connected,false);
 }finally{globalThis.fetch=original;}
});
test('expired YouTube access token refreshes server-side without exposing credentials',async()=>{
 const env=makeEnv(),start=await authorize(env,'youtube');
 const original=globalThis.fetch;
 let refreshes=0;
 try{
  globalThis.fetch=async(target,options)=>{
   if(target==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true') {
    assert.equal(options.headers.Authorization,'Bearer '+(refreshes?'initial-access':'old-youtube-access'));
    return Response.json({items:[{id:'UC1234567890123456789012'}]});
   }
   assert.equal(target,'https://oauth2.googleapis.com/token');
   if(options.body.get('grant_type')==='authorization_code')
    return Response.json({access_token:'old-youtube-access',refresh_token:'private-youtube-refresh',token_type:'Bearer',expires_in:3600});
   assert.equal(options.body.get('grant_type'),'refresh_token');
   assert.equal(options.body.get('refresh_token'),'private-youtube-refresh');
   assert.equal(options.body.get('client_secret'),env.GOOGLE_CLIENT_SECRET);
   refreshes++;
   return Response.json({access_token:'new-youtube-access',token_type:'Bearer',expires_in:7200});
  };
  assert.equal((await call('/api/connections/youtube/callback?state='+start.state+'&code=abc',{},env)).status,200);
  const prior=env.OAUTH_DB.query("SELECT encrypted_token FROM oauth_accounts WHERE provider='youtube'")[0].encrypted_token;
  env.OAUTH_DB.execute("UPDATE oauth_accounts SET access_expires_at=? WHERE provider='youtube'",Date.now()-1000);
  const view=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
  assert.equal((await view.json()).youtube.connected,true);
  const after=env.OAUTH_DB.query("SELECT encrypted_token,access_expires_at,refresh_retry_after FROM oauth_accounts WHERE provider='youtube'")[0];
  assert.notEqual(after.encrypted_token,prior);
  assert.doesNotMatch(after.encrypted_token,/private-youtube-refresh|new-youtube-access/);
  assert.ok(after.access_expires_at>Date.now()+3600000);
  assert.equal(after.refresh_retry_after,0);
  assert.equal(refreshes,1);
  const again=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
  assert.equal((await again.json()).youtube.connected,true);
  assert.equal(refreshes,1);
 }finally{globalThis.fetch=original;}
});
test('failed YouTube refresh is disconnected and cooldown prevents repeated token traffic',async()=>{
 const env=makeEnv(),start=await authorize(env,'youtube');
 const original=globalThis.fetch;
 let refreshes=0;
 try{
  globalThis.fetch=async(target,options)=>{
   if(target==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true'){
    assert.equal(options.headers.Authorization,'Bearer initial-access');
    return Response.json({items:[{id:'UC1234567890123456789012'}]});
   }
   assert.equal(target,'https://oauth2.googleapis.com/token');
   if(options.body.get('grant_type')==='authorization_code')
    return Response.json({access_token:'initial-access',refresh_token:'initial-refresh',token_type:'Bearer',expires_in:3600});
   refreshes++;
   return Response.json({error:'invalid_grant'},{status:400});
  };
  assert.equal((await call('/api/connections/youtube/callback?state='+start.state+'&code=abc',{},env)).status,200);
  env.OAUTH_DB.execute("UPDATE oauth_accounts SET access_expires_at=? WHERE provider='youtube'",Date.now()-1000);
  for(let i=0;i<3;i++){
   const response=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
   assert.equal((await response.json()).youtube.connected,false);
  }
  assert.equal(refreshes,1);
  const row=env.OAUTH_DB.query("SELECT refresh_retry_after FROM oauth_accounts WHERE provider='youtube'")[0];
  assert.ok(row.refresh_retry_after>Date.now());
 }finally{globalThis.fetch=original;}
});
test('token response with unknown lifetime fails closed and does not create a connection',async()=>{
 const env=makeEnv(),start=await authorize(env);
 const original=globalThis.fetch;
 try{
  globalThis.fetch=async()=>Response.json({access_token:'token-without-expiry'});
  const callback=await call('/api/connections/facebook/callback?state='+start.state+'&code=abc',{},env);
  assert.equal(callback.status,502);
  assert.equal((await callback.json()).error,'invalid_provider_token_lifetime');
  assert.equal(env.OAUTH_DB.query('SELECT * FROM oauth_accounts').length,0);
 }finally{globalThis.fetch=original;}
});

test('daily scheduled D1 cleanup removes expired records and preserves live sessions',async()=>{
 const env=makeEnv({OAUTH_ENABLED:'false'});
 const old=Date.now()-120000,future=Date.now()+3600000;
 for(const [id,expiry] of [['expired-device',old],['active-device',future]]){
  env.OAUTH_DB.execute('INSERT INTO oauth_sessions(session_id,expires_at) VALUES(?,?)',id,expiry);
  env.OAUTH_DB.execute("INSERT INTO oauth_pairings(pairing_hash,session_id,provider,status,expires_at) VALUES(?,?,?,'pending',?)",'pair-'+id,id,'youtube',expiry);
  env.OAUTH_DB.execute('INSERT INTO oauth_states(state_hash,session_id,provider,verifier,pairing_hash,expires_at) VALUES(?,?,?,?,?,?)','state-'+id,id,'youtube','mock-verifier','pair-'+id,expiry);
  env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at) VALUES(?,?,?,?,?)',id,'youtube','{}',expiry,expiry);
 }
 // Even with the public login switch disabled, expiration cleanup still runs.
 await worker.scheduled({scheduledTime:Date.now()},env,{});
 for(const table of ['oauth_sessions','oauth_pairings','oauth_states','oauth_accounts']){
  const rows=env.OAUTH_DB.query('SELECT * FROM '+table);
  assert.equal(rows.length,1,table+' should have retained one active row');
 }
 assert.equal(env.OAUTH_DB.query('SELECT session_id FROM oauth_sessions')[0].session_id,'active-device');
 assert.equal(env.OAUTH_DB.query('SELECT session_id FROM oauth_accounts')[0].session_id,'active-device');
 await worker.scheduled({scheduledTime:Date.now()},env,{});
 assert.equal(env.OAUTH_DB.query('SELECT * FROM oauth_sessions').length,1,'cleanup is idempotent');
});

test('YouTube-only test window blocks Facebook without enabling full production OAuth',async()=>{
 const env=makeEnv({
  OAUTH_ENABLED:'false',
  FACEBOOK_APP_ID:undefined,
  FACEBOOK_APP_SECRET:undefined,
  YOUTUBE_TEST_ENABLED:'true',
  YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()+3600000).toISOString()
 });
 const health=await call('/health',{},env);
 assert.equal(health.status,200);
 const h=await health.json();
 assert.equal(h.mode,'youtube_testing');
 assert.deepEqual(h.providers,{youtube:true,facebook:false});
 const denied=await call('/api/connections/facebook/authorize',{method:'POST',headers:ctype,body:'{}'},env);
 assert.equal(denied.status,503);
 assert.equal((await denied.json()).error,'provider_not_enabled');
 assert.equal((await call('/api/connections/facebook/callback?state='+('X'.repeat(43))+'&code=fake',{},env)).status,503);
 const start=await authorize(env,'youtube');
 const statuses=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
 assert.deepEqual(await statuses.json(),{facebook:{connected:false},youtube:{connected:false}});
});
test('expired YouTube test window always closes and rejects authorization',async()=>{
 for(const exp of ['invalid',new Date(Date.now()-120000).toISOString(),new Date(Date.now()+72*3600000).toISOString()]){
  const env=makeEnv({OAUTH_ENABLED:'false',YOUTUBE_TEST_ENABLED:'true',YOUTUBE_TEST_EXPIRES_AT:exp});
  assert.equal((await call('/health',{},env)).status,503);
  const response=await call('/api/connections/youtube/authorize',{method:'POST',headers:ctype,body:'{}'},env);
  assert.equal(response.status,503);
  assert.equal((await response.json()).error,'oauth_not_enabled');
 }
});
test('YouTube test callback verifies real YouTube channel before storing provider token',async()=>{
 const env=makeEnv({OAUTH_ENABLED:'false',FACEBOOK_APP_ID:undefined,FACEBOOK_APP_SECRET:undefined,YOUTUBE_TEST_ENABLED:'true',YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()+3600000).toISOString()});
 const start=await authorize(env,'youtube');
 const previous=globalThis.fetch;
 try{
  globalThis.fetch=async url=>{
   if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'test-access',token_type:'Bearer',expires_in:3600});
   if(url==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true')return Response.json({items:[{id:'UC1234567890123456789012'}]});
   throw Error('Unexpected provider call');
  };
  const response=await call('/api/connections/youtube/callback?state='+start.state+'&code=abc',{},env);
  assert.equal(response.status,200);
  const status=await call('/api/connections',{headers:{...ctype,cookie:start.cookie}},env);
  assert.equal((await status.json()).youtube.connected,true);
 }finally{globalThis.fetch=previous;}
});
test('Google token for account without YouTube channel is never considered connected',async()=>{
 const env=makeEnv({OAUTH_ENABLED:'false',YOUTUBE_TEST_ENABLED:'true',YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()+3600000).toISOString()});
 const start=await authorize(env,'youtube');
 const previous=globalThis.fetch;
 try{
  globalThis.fetch=async url=>{
   if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'google-no-channel',token_type:'Bearer',expires_in:3600});
   if(url==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true')return Response.json({items:[]});
   throw Error('Unexpected provider call');
  };
  const response=await call('/api/connections/youtube/callback?state='+start.state+'&code=abc',{},env);
  assert.equal(response.status,403);
  assert.equal((await response.json()).error,'youtube_channel_not_found');
  assert.equal(env.OAUTH_DB.query('SELECT * FROM oauth_accounts').length,0);
 }finally{globalThis.fetch=previous;}
});

test('YouTube Live readiness is read-only, authenticated, and strips all ingress credentials',async()=>{
 const env=makeEnv({
  OAUTH_ENABLED:'false',
  FACEBOOK_APP_ID:undefined,
  FACEBOOK_APP_SECRET:undefined,
  YOUTUBE_TEST_ENABLED:'true',
  YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()+3600000).toISOString()
 });
 const notAuthorized=await call('/api/connections/youtube/live-readiness',{headers:{origin:appOrigin}},env);
 assert.equal(notAuthorized.status,401);
 const forged=await call('/api/connections/youtube/live-readiness',{headers:{origin:'https://attacker.example'}},env);
 assert.equal(forged.status,403);
 const account=await authorize(env,'youtube');
 const old=globalThis.fetch;
 let requests=0;
 try{
  globalThis.fetch=async (target,options)=>{
   if(target==='https://oauth2.googleapis.com/token')
    return Response.json({access_token:'never-leak-youtube-access',refresh_token:'never-leak-refresh',token_type:'Bearer',expires_in:3600});
   if(target==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true')
    return Response.json({items:[{id:'UC1234567890123456789012'}]});
   if(target.startsWith('https://www.googleapis.com/youtube/v3/liveBroadcasts?')){
    requests++;
    assert.equal(options.method,'GET');
    assert.equal(options.headers.Authorization,'Bearer never-leak-youtube-access');
    return Response.json({items:[
     {id:'future-video',snippet:{title:'Practice live event',scheduledStartTime:'2026-10-23T09:00:00Z',internalSecret:'private'},status:{lifeCycleStatus:'ready',privacyStatus:'unlisted'},contentDetails:{boundStreamId:'stream-123',ingestionInfo:{streamName:'secret-live-key'}}},
     {id:'completed-video',snippet:{title:'Old'},status:{lifeCycleStatus:'complete',privacyStatus:'public'}},
     {id:'live-video',snippet:{title:'Court A'},status:{lifeCycleStatus:'live',privacyStatus:'private'},contentDetails:{}}
    ],cdn:{ingestionInfo:{streamName:'secret-live-key'}}});
   }
   throw Error('Unexpected YouTube URL: '+target);
  };
  const callback=await call('/api/connections/youtube/callback?state='+account.state+'&code=abc',{},env);
  assert.equal(callback.status,200);
  const response=await call('/api/connections/youtube/live-readiness',{headers:{origin:appOrigin,cookie:account.cookie}},env);
  assert.equal(response.status,200);
  assert.equal(response.headers.get('cache-control'),'no-store');
  const ready=await response.json();
  assert.equal(ready.liveEnabled,true);
  assert.equal(ready.liveApiAccessible,true);
  assert.equal(ready.publishingConfigured,false);
  assert.equal(ready.broadcasts.length,2);
  assert.deepEqual(ready.broadcasts.map(x=>x.title),['Practice live event','Court A']);
  assert.equal(ready.broadcasts[0].hasStream,true);
  const serialized=JSON.stringify(ready);
  assert.doesNotMatch(serialized,/secret-live-key|never-leak-youtube-access|never-leak-refresh|ingestionInfo|streamName|cdn/);
  assert.equal(requests,1);
 }finally{globalThis.fetch=old;}
});
test('YouTube Live eligibility error is conveyed without sharing Google API errors',async()=>{
 const env=makeEnv();
 const account=await authorize(env,'youtube');
 const old=globalThis.fetch;
 try{
  globalThis.fetch=async target=>{
   if(target==='https://oauth2.googleapis.com/token')
    return Response.json({access_token:'test-youtube-token',token_type:'Bearer',expires_in:3600});
   if(target==='https://www.googleapis.com/youtube/v3/channels?part=id&mine=true')
    return Response.json({items:[{id:'UC1234567890123456789012'}]});
   if(target.startsWith('https://www.googleapis.com/youtube/v3/liveBroadcasts?'))
    return Response.json({error:{errors:[{reason:'liveStreamingNotEnabled',message:'Private Google diagnostic!'}]}},{status:403});
   throw Error('Unknown provider');
  };
  assert.equal((await call('/api/connections/youtube/callback?state='+account.state+'&code=abc',{},env)).status,200);
  const response=await call('/api/connections/youtube/live-readiness',{headers:{origin:appOrigin,cookie:account.cookie}},env);
  assert.equal(response.status,200);
  const payload=await response.json();
  assert.deepEqual(payload,{liveApiAccessible:false,liveEnabled:false,reason:'live_streaming_not_enabled',broadcasts:[],publishingConfigured:false,canCreatePrivateDraft:false});
  assert.doesNotMatch(JSON.stringify(payload),/Private Google diagnostic/);
 }finally{globalThis.fetch=old;}
});
test('YouTube Live readiness is blocked after the temporary OAuth window expires',async()=>{
 const env=makeEnv({
  OAUTH_ENABLED:'false',
  YOUTUBE_TEST_ENABLED:'true',
  YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()-60000).toISOString()
 });
 const response=await call('/api/connections/youtube/live-readiness',{headers:{origin:appOrigin}},env);
 assert.equal(response.status,503);
 assert.equal((await response.json()).error,'oauth_not_enabled');
});

async function seedConnectedYoutube(env){
 const authorization=await authorize(env,'youtube');
 const session=authorization.cookie.split('=')[1];
 const key='A'.repeat(43);
 // AES-GCM encrypted mock OAuth grant exactly as stored by the callback.
 const bytes=new Uint8Array(32);
 const cryptoKey=await crypto.subtle.importKey('raw',bytes,'AES-GCM',false,['encrypt']);
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const grant={access_token:'private-test-access',channel_id:'UC1234567890123456789012',expires_at:Date.now()+3600000};
 const cipher=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv},cryptoKey,new TextEncoder().encode(JSON.stringify(grant))));
 const encode=b=>Buffer.from(b).toString('base64url');
 const encrypted=JSON.stringify({version:1,iv:encode(iv),ciphertext:encode(cipher)});
 env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at,access_expires_at) VALUES(?,?,?,?,?)',session,'youtube',encrypted,Date.now()+3600000,Date.now()+3600000);
 return authorization;
}
const draftPayload=(id='a'.repeat(32),overrides={})=>({
 title:'IMG LIVE Studio Private Camera Test',
 scheduledStartTime:new Date(Date.now()+3600000).toISOString(),
 madeForKids:false,
 operatorConfirmed:true,
 requestId:id,
 ...overrides
});
const activePrivateDraftEnv=()=>makeEnv({
 OAUTH_ENABLED:'false',
 YOUTUBE_TEST_ENABLED:'true',
 YOUTUBE_DRAFT_ENABLED:'true',
 YOUTUBE_TEST_EXPIRES_AT:new Date(Date.now()+3600000).toISOString()
});
test('YouTube private draft endpoint is disabled without explicit staging gate',async()=>{
 const env=makeEnv({YOUTUBE_DRAFT_ENABLED:'false'});
 const res=await call('/api/connections/youtube/private-draft',{method:'POST',headers:ctype,body:JSON.stringify(draftPayload())},env);
 assert.equal(res.status,503);
 assert.equal((await res.json()).error,'private_drafts_disabled');
});
test('private YouTube draft creation requires Android origin and explicit confirmation',async()=>{
 const env=activePrivateDraftEnv();
 const attack=await call('/api/connections/youtube/private-draft',{method:'POST',headers:{...ctype,origin:'https://evil.test'},body:JSON.stringify(draftPayload())},env);
 assert.equal(attack.status,403);
 const start=await seedConnectedYoutube(env);
 for(const changed of [{operatorConfirmed:false},{madeForKids:null},{title:'X'},{requestId:'bad'},{scheduledStartTime:new Date(Date.now()+5000).toISOString()}]){
  const res=await call('/api/connections/youtube/private-draft',{method:'POST',headers:{...ctype,cookie:start.cookie},body:JSON.stringify(draftPayload('b'.repeat(32),changed))},env);
  assert.equal(res.status,400);
 }
 assert.equal(env.OAUTH_DB.query('SELECT * FROM youtube_private_draft_requests').length,0);
});
test('GO LIVE NOW accepts a private broadcast without a client schedule and enables auto-start',async()=>{
 const env=activePrivateDraftEnv();
 const start=await seedConnectedYoutube(env);
 const payload=draftPayload('e'.repeat(32),{startNow:true,scheduledStartTime:null});
 const headers={...ctype,cookie:start.cookie};
 const original=globalThis.fetch;
 let insertCalls=0;
 try{
  globalThis.fetch=async (url,request)=>{
   assert.equal(url,'https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet%2Cstatus%2CcontentDetails');
   insertCalls++;
   const body=JSON.parse(request.body);
   assert.equal(body.status.privacyStatus,'private');
   assert.equal(body.contentDetails.enableAutoStart,true);
   assert.equal(body.contentDetails.enableAutoStop,true);
   assert.ok(Math.abs(Date.parse(body.snippet.scheduledStartTime)-Date.now())<4*60000);
   return Response.json({id:'newPrivateNow123',snippet:{channelId:'UC1234567890123456789012'},status:{privacyStatus:'private'}});
  };
  const create=await call('/api/connections/youtube/private-draft',{
   method:'POST',headers,body:JSON.stringify(payload)
  },env);
  assert.equal(create.status,201);
  const data=await create.json();
  assert.equal(data.privacy,'private');
  assert.equal(insertCalls,1);
  const retry=await call('/api/connections/youtube/private-draft',{
   method:'POST',headers,body:JSON.stringify(payload)
  },env);
  assert.equal(retry.status,200);
  assert.equal((await retry.json()).alreadyCreated,true);
  assert.equal(insertCalls,1);
  assert.equal(env.OAUTH_DB.query('SELECT * FROM youtube_private_draft_requests').length,1);
 }finally{globalThis.fetch=original;}
});
test('private YouTube broadcast draft is strictly private, idempotent, and token-free',async()=>{
 const env=activePrivateDraftEnv();
 const start=await seedConnectedYoutube(env);
 const body=draftPayload();
 const original=globalThis.fetch;
 let created=0;
 try{
  globalThis.fetch=async (target,options)=>{
   if(target!=='https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet%2Cstatus%2CcontentDetails')
    throw Error('Unexpected YouTube endpoint '+target);
   created++;
   assert.equal(options.method,'POST');
   assert.equal(options.redirect,'manual');
   assert.equal(options.headers.Authorization,'Bearer private-test-access');
   const payload=JSON.parse(options.body);
   assert.equal(payload.status.privacyStatus,'private');
   assert.equal(payload.status.selfDeclaredMadeForKids,false);
   assert.equal(payload.contentDetails.enableAutoStart,false);
   assert.ok(!JSON.stringify(payload).includes('streamName'));
   return Response.json({id:'privateVideo123',snippet:{channelId:'UC1234567890123456789012'},status:{privacyStatus:'private'}});
  };
  const headers={...ctype,cookie:start.cookie};
  const one=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(body)},env);
  assert.equal(one.status,201);
  const createdBody=await one.json();
  assert.equal(createdBody.created,true);
  assert.equal(createdBody.broadcast.title,body.title);
  assert.equal(createdBody.privacy,'private');
  assert.equal(createdBody.publishingConfigured,false);
  assert.ok(!JSON.stringify(createdBody).includes('private-test-access'));
  const retry=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(body)},env);
  assert.equal(retry.status,200);
  assert.equal((await retry.json()).alreadyCreated,true);
  assert.equal(created,1);
  const conflict=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify({...body,title:'Different Title'})},env);
  assert.equal(conflict.status,409);
  assert.equal((await conflict.json()).error,'request_id_conflict');
 }finally{globalThis.fetch=original;}
});
test('private YouTube draft creates no duplicates on failed or simultaneous requests',async()=>{
 const env=activePrivateDraftEnv();
 const start=await seedConnectedYoutube(env),body=draftPayload();
 const original=globalThis.fetch;
 let finish;
 const gate=new Promise(resolve=>finish=resolve);
 let calls=0;
 try{
  globalThis.fetch=async()=>{calls++;await gate;return Response.json({id:'privateVideo2',snippet:{channelId:'UC1234567890123456789012'},status:{privacyStatus:'private'}})};
  const headers={...ctype,cookie:start.cookie};
  const first=call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(body)},env);
  // Allow the first request to atomically claim the key before another tries.
  for(let i=0;i<100&&calls===0;i++)await new Promise(resolve=>setTimeout(resolve,1));
  assert.equal(calls,1);
  const second=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(body)},env);
  assert.equal(second.status,409);
  assert.equal((await second.json()).error,'draft_request_processing');
  finish();
  assert.equal((await first).status,201);
  assert.equal(calls,1);
 }finally{finish?.();globalThis.fetch=original;}
});
test('private draft refuses YouTube responses that are public or belong to another channel',async()=>{
 const env=activePrivateDraftEnv();
 const start=await seedConnectedYoutube(env);
 const original=globalThis.fetch;
 try{
  globalThis.fetch=async()=>Response.json({id:'unexpectedVideo',snippet:{channelId:'UCanotherchannel99999'},status:{privacyStatus:'public'}});
  const result=await call('/api/connections/youtube/private-draft',{method:'POST',headers:{...ctype,cookie:start.cookie},body:JSON.stringify(draftPayload())},env);
  assert.equal(result.status,502);
  assert.equal((await result.json()).error,'youtube_draft_confirmation_invalid');
  assert.equal(env.OAUTH_DB.query("SELECT state FROM youtube_private_draft_requests")[0].state,'failed');
 }finally{globalThis.fetch=original;}
});
test('draft rate limit prevents more than three user initiated private drafts per day',async()=>{
 const env=activePrivateDraftEnv();
 const start=await seedConnectedYoutube(env);
 const original=globalThis.fetch;
 let sent=0;
 try{
  globalThis.fetch=async()=>Response.json({id:'privateVideo'+(++sent),snippet:{channelId:'UC1234567890123456789012'},status:{privacyStatus:'private'}});
  const headers={...ctype,cookie:start.cookie};
  for(let i=0;i<3;i++){
   const r=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(draftPayload(String(i).repeat(32)))},env);
   assert.equal(r.status,201);
  }
  const final=await call('/api/connections/youtube/private-draft',{method:'POST',headers,body:JSON.stringify(draftPayload('f'.repeat(32)))},env);
  assert.equal(final.status,429);
  assert.equal((await final.json()).error,'private_draft_limit_reached');
  assert.equal(sent,3);
 }finally{globalThis.fetch=original;}
});

test('YouTube Live readiness distinguishes upstream permission, quota, invalid request and temporary failures',async()=>{
 const env=makeEnv();
 const auth=await seedConnectedYoutube(env);
 const prev=globalThis.fetch;
 const cases=[
  {status:403,reasons:['insufficientLivePermissions'],expected:'insufficient_live_permissions',enabled:null},
  {status:403,reasons:['livePermissionBlocked'],expected:'live_permission_blocked',enabled:false},
  {status:403,reasons:['quotaExceeded'],expected:'youtube_api_quota_exceeded',enabled:null},
  {status:403,reasons:['rateLimitExceeded'],expected:'youtube_api_rate_limited',enabled:null},
  {status:403,reasons:['accessNotConfigured'],expected:'youtube_api_not_enabled',enabled:null},
  {status:403,reasons:['someUndocumentedGoogleFailure'],expected:'youtube_api_forbidden',enabled:null},
  {status:400,reasons:['invalidFilters'],expected:'youtube_api_bad_request',enabled:null},
  {status:429,reasons:[],expected:'youtube_api_rate_limited',enabled:null},
  {status:500,reasons:[],expected:'youtube_api_temporarily_unavailable',enabled:null}
 ];
 try {
  for(const item of cases){
   globalThis.fetch=async (url,options)=>{
    assert.ok(String(url).startsWith('https://www.googleapis.com/youtube/v3/liveBroadcasts?'));
    assert.equal(options.headers.Authorization,'Bearer private-test-access');
    return Response.json({error:{code:item.status,errors:item.reasons.map(reason=>({reason,message:'Secret Google response and account data!'})),
      message:'This secret diagnostic must never enter the app'}},{status:item.status});
   };
   const response=await call('/api/connections/youtube/live-readiness',{headers:{...ctype,cookie:auth.cookie}},env);
   assert.equal(response.status,200,item.expected);
   const body=await response.json();
   assert.deepEqual(body,{liveApiAccessible:false,liveEnabled:item.enabled,reason:item.expected,broadcasts:[],publishingConfigured:false,canCreatePrivateDraft:false},item.expected);
   assert.doesNotMatch(JSON.stringify(body),/Secret Google|private-test-access|account data/);
  }
  globalThis.fetch=async ()=>{throw Error('Sensitive upstream network error');};
  const network=await call('/api/connections/youtube/live-readiness',{headers:{...ctype,cookie:auth.cookie}},env);
  assert.equal(network.status,200);
  assert.equal((await network.json()).reason,'youtube_api_network_unavailable');
 }finally{globalThis.fetch=prev;}
});

test('Cloudflare YouTube Live check refuses redirects without leaking OAuth tokens',async()=>{
 const env=activePrivateDraftEnv(),auth=await seedConnectedYoutube(env);
 const original=globalThis.fetch;
 let calls=0;
 try{
  globalThis.fetch=async (target,options)=>{
   calls++;
   assert.equal(target,'https://www.googleapis.com/youtube/v3/liveBroadcasts?part=id%2Csnippet%2Cstatus%2CcontentDetails&mine=true&broadcastType=all&maxResults=20');
   assert.equal(options.redirect,'manual','Cloudflare Workers cannot use redirect error mode');
   assert.equal(options.headers.Authorization,'Bearer private-test-access');
   return new Response(null,{status:302,headers:{Location:'https://attacker.example/collect-secret'}});
  };
  const response=await call('/api/connections/youtube/live-readiness',{headers:{...ctype,cookie:auth.cookie}},env);
  assert.equal(response.status,200);
  const body=await response.json();
  assert.deepEqual(body,{liveApiAccessible:false,liveEnabled:null,reason:'youtube_api_redirect_rejected',broadcasts:[],publishingConfigured:false,canCreatePrivateDraft:false});
  assert.doesNotMatch(JSON.stringify(body),/attacker|collect-secret|private-test-access/);
  assert.equal(calls,1,'No redirect or retry may be followed');
 }finally{globalThis.fetch=original;}
});
test('Private draft creation refuses redirects and never forwards the access token',async()=>{
 const env=activePrivateDraftEnv(),auth=await seedConnectedYoutube(env);
 const original=globalThis.fetch;
 let calls=0;
 try{
  globalThis.fetch=async (target,options)=>{
   calls++;
   assert.equal(target,'https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet%2Cstatus%2CcontentDetails');
   assert.equal(options.redirect,'manual','Cloudflare Workers only supports manual or follow');
   assert.equal(options.headers.Authorization,'Bearer private-test-access');
   return new Response(null,{status:307,headers:{Location:'https://attacker.example/private'}});
  };
  const result=await call('/api/connections/youtube/private-draft',{
   method:'POST',headers:{...ctype,cookie:auth.cookie},body:JSON.stringify(draftPayload('d'.repeat(32)))
  },env);
  assert.equal(result.status,502);
  const body=await result.json();
  assert.equal(body.error,'youtube_draft_redirect_rejected');
  assert.doesNotMatch(JSON.stringify(body),/attacker|private-test-access/);
  assert.equal(env.OAUTH_DB.query('SELECT state FROM youtube_private_draft_requests')[0].state,'failed');
  assert.equal(calls,1);
 }finally{globalThis.fetch=original;}
});

test('private ingest fails closed, never automatically enabled by working OAuth and drafts',async()=>{
 const env=activePrivateDraftEnv();
 const authorization=await seedConnectedYoutube(env);
 let googleCalls=0;
 const old=globalThis.fetch;
 try {
  globalThis.fetch=async()=>{googleCalls++;throw Error('Google must never be called')};
  const payload={draftRequestId:'a'.repeat(32),operatorConfirmed:true};
  const response=await call('/api/connections/youtube/private-ingest',{
   method:'POST',headers:{...ctype,cookie:authorization.cookie},body:JSON.stringify(payload)
  },env);
  assert.equal(response.status,503);
  assert.deepEqual(await response.json(),{error:'youtube_private_ingest_disabled'});
  assert.equal(googleCalls,0);
  assert.equal(env.OAUTH_DB.query('SELECT * FROM youtube_private_ingest_attempts').length,0);
 }finally{globalThis.fetch=old}
});

test('private ingest demands native origin, explicit confirmation, and session-owned draft',async()=>{
 const env=activePrivateDraftEnv();
 env.YOUTUBE_INGEST_TEST_ENABLED='true';
 const authorization=await seedConnectedYoutube(env);
 const valid={draftRequestId:'a'.repeat(32),operatorConfirmed:true};
 const noOrigin=await call('/api/connections/youtube/private-ingest',{
  method:'POST',headers:{...ctype,origin:'https://other.example',cookie:authorization.cookie},
  body:JSON.stringify(valid)},env);
 assert.equal(noOrigin.status,403);
 const noConfirm=await call('/api/connections/youtube/private-ingest',{
  method:'POST',headers:{...ctype,cookie:authorization.cookie},
  body:JSON.stringify({...valid,operatorConfirmed:false})},env);
 assert.equal(noConfirm.status,400);
 const notOwned=await call('/api/connections/youtube/private-ingest',{
  method:'POST',headers:{...ctype,cookie:authorization.cookie},
  body:JSON.stringify(valid)},env);
 assert.equal(notOwned.status,404);
 assert.equal((await notOwned.json()).error,'private_draft_not_owned');
 assert.equal(env.OAUTH_DB.query('SELECT * FROM youtube_private_ingest_attempts').length,0);
});

test('private ingest binds only verified private draft and never transitions live',async()=>{
 const env=activePrivateDraftEnv();
 env.YOUTUBE_INGEST_TEST_ENABLED='true';
 const authorized=await seedConnectedYoutube(env);
 const session=authorized.cookie.split('=')[1];
 env.OAUTH_DB.execute("INSERT INTO youtube_private_draft_requests(session_id,request_id,payload_hash,state,broadcast_id,title,scheduled_start,created_at) VALUES(?,?,?,'complete',?,?,?,?)",
  session,'a'.repeat(32),'hash','privateVideo123','Practice',new Date(Date.now()+3600000).toISOString(),Date.now());
 const prior=globalThis.fetch;
 const called=[];
 try{
  globalThis.fetch=async (url,options)=>{
   called.push({url,method:options.method,redirect:options.redirect,body:options.body});
   assert.equal(options.redirect,'manual');
   assert.equal(options.headers.Authorization,'Bearer private-test-access');
   if(url.includes('liveBroadcasts?part=')){
    return Response.json({items:[{id:'privateVideo123',snippet:{channelId:'UC1234567890123456789012'},
     status:{privacyStatus:'private',lifeCycleStatus:'created'},contentDetails:{}}]});
   }
   if(url.includes('/liveStreams?part=')){
    const data=JSON.parse(options.body);
    assert.equal(data.cdn.ingestionType,'rtmp');
    return Response.json({id:'youtubeStream1',cdn:{ingestionInfo:{
     rtmpsIngestionAddress:'rtmps://a.rtmps.youtube.com/live2',streamName:'private_stream_123'
    }}});
   }
   if(url.includes('liveBroadcasts/bind?')){
    return Response.json({id:'privateVideo123',contentDetails:{boundStreamId:'youtubeStream1'}});
   }
   throw Error('Unexpected YouTube route');
  };
  const body=JSON.stringify({draftRequestId:'a'.repeat(32),operatorConfirmed:true});
  const opts={method:'POST',headers:{...ctype,cookie:authorized.cookie},body};
  const response=await call('/api/connections/youtube/private-ingest',opts,env);
  assert.equal(response.status,200);
  const data=await response.json();
  assert.equal(data.privacy,'private');
  assert.equal(data.autoStart,false);
  assert.equal(data.rtmpsUrl,'rtmps://a.rtmps.youtube.com/live2/private_stream_123');
  assert.deepEqual(called.map(x=>x.method),['GET','POST','POST']);
  assert.equal(called.some(x=>x.url.includes('transition')),false);
  assert.equal(env.OAUTH_DB.query('SELECT state FROM youtube_private_ingest_attempts')[0].state,'complete');
  // Resumption is deliberately read-only: recover the existing bound feed
  // from Google without creating/binding additional YouTube resources.
  globalThis.fetch=async(url,options)=>{
   called.push({url,method:options.method,redirect:options.redirect});
   assert.equal(options.method,'GET');
   assert.equal(options.redirect,'manual');
   if(url.includes('liveBroadcasts?part='))return Response.json({items:[{
    id:'privateVideo123',
    snippet:{channelId:'UC1234567890123456789012'},
    status:{privacyStatus:'private',lifeCycleStatus:'ready'},
    contentDetails:{boundStreamId:'youtubeStream1'}
   }]});
   if(url.includes('liveStreams?part='))return Response.json({items:[{
    id:'youtubeStream1',snippet:{channelId:'UC1234567890123456789012'},
    cdn:{ingestionType:'rtmp',ingestionInfo:{
     rtmpsIngestionAddress:'rtmps://a.rtmps.youtube.com/live2',streamName:'private_stream_123'
    }},status:{streamStatus:'inactive'}
   }]});
   throw Error('Unexpected resume path');
  };
  const resumed=await call('/api/connections/youtube/private-ingest',opts,env);
  assert.equal(resumed.status,200);
  const restored=await resumed.json();
  assert.equal(restored.resumed,true);
  assert.equal(restored.autoStart,false);
  assert.equal(restored.privacy,'private');
  assert.equal(restored.rtmpsUrl,data.rtmpsUrl);
  assert.deepEqual(called.map(x=>x.method),['GET','POST','POST','GET','GET']);
  assert.equal(called.some(x=>x.url.includes('transition')),false);
  assert.equal(env.OAUTH_DB.query('SELECT COUNT(*) AS n FROM youtube_private_ingest_attempts')[0].n,1);

  // If the Google event becomes public, returning its ingest key is forbidden.
  globalThis.fetch=async(url,options)=>{
   called.push({url,method:options.method});
   assert.equal(options.method,'GET');
   return Response.json({items:[{id:'privateVideo123',
    snippet:{channelId:'UC1234567890123456789012'},
    status:{privacyStatus:'public',lifeCycleStatus:'ready'},
    contentDetails:{boundStreamId:'youtubeStream1'}}]});
  };
  const publicEvent=await call('/api/connections/youtube/private-ingest',opts,env);
  assert.equal(publicEvent.status,409);
  assert.equal((await publicEvent.json()).error,'private_ingest_resume_not_private_ready');
 }finally{globalThis.fetch=prior}
});

test('failed ingest stores safe stage code and excludes attempted draft from readiness',async()=>{
 const env=activePrivateDraftEnv();
 env.YOUTUBE_INGEST_TEST_ENABLED='true';
 const authorized=await seedConnectedYoutube(env);
 const session=authorized.cookie.split('=')[1];
 const ids=['a'.repeat(32),'b'.repeat(32)];
 for(const draftId of ids)env.OAUTH_DB.execute(
  "INSERT INTO youtube_private_draft_requests(session_id,request_id,payload_hash,state,broadcast_id,title,scheduled_start,created_at) VALUES(?,?,?,'complete',?,?,?,?)",
  session,draftId,'hash','privateVideo123','Practice',new Date(Date.now()+3600000).toISOString(),Date.now()
 );
 const old=globalThis.fetch;
 try {
  // The Google private broadcast check fails before any stream write.
  globalThis.fetch=async(target,options)=>{
   assert.equal(options.method,'GET');
   return Response.json({error:{errors:[{reason:'livePermissionBlocked'}],message:'Do not log Google error text'}},{status:403});
  };
  const result=await call('/api/connections/youtube/private-ingest',{
   method:'POST',headers:{...ctype,cookie:authorized.cookie},
   body:JSON.stringify({draftRequestId:ids[0],operatorConfirmed:true})
  },env);
  assert.equal(result.status,502);
  assert.equal((await result.json()).error,'youtube_private_broadcast_check_failed');
  const rows=env.OAUTH_DB.query('SELECT state,failure_code FROM youtube_private_ingest_attempts');
  assert.equal(rows.length,1);
  assert.equal(rows[0].state,'failed');
  assert.equal(rows[0].failure_code,'youtube_private_broadcast_check_failed');
  assert.ok(!JSON.stringify(rows).includes('Do not log Google'));
  // Live readiness is read-only and must not make attempted draft available again.
  globalThis.fetch=async(target,options)=>{
   assert.equal(options.method,'GET');
   return Response.json({items:[{id:'privateVideo123',snippet:{title:'Practice'},
    status:{lifeCycleStatus:'ready',privacyStatus:'private'}}]});
  };
  const check=await call('/api/connections/youtube/live-readiness',{
   headers:{origin:appOrigin,cookie:authorized.cookie}
  },env);
  assert.equal(check.status,200);
  const ready=await check.json();
  assert.equal(ready.privateDrafts.length,1);
  assert.equal(ready.privateDrafts[0].requestId,ids[1]);
 }finally{globalThis.fetch=old}
});
