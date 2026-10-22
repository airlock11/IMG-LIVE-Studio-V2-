import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {DatabaseSync} from 'node:sqlite';

const source=await readFile(new URL('./src/index.js',import.meta.url),'utf8');
const schema=await readFile(new URL('./migrations/0001_oauth_transactional.sql',import.meta.url),'utf8');
const {default:worker}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const api='https://oauth.example.test';
const appOrigin='https://appassets.androidplatform.net';
const ctype={'origin':appOrigin,'content-type':'application/json'};

function memoryD1(){
 const db=new DatabaseSync(':memory:');
 db.exec('PRAGMA foreign_keys=ON;');
 db.exec(schema);
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
   if(target==='https://oauth2.googleapis.com/token')return Response.json({access_token:'youtube-token',token_type:'Bearer'});
   if(target==='https://graph.facebook.com/v24.0/oauth/access_token')return Response.json({access_token:'facebook-token'});
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
 env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at) VALUES(?,?,?,?)',session.cookie.split('=')[1],'facebook','{}',Date.now()+600000);
 env.OAUTH_DB.execute('INSERT INTO oauth_accounts(session_id,provider,encrypted_token,expires_at) VALUES(?,?,?,?)',session.cookie.split('=')[1],'youtube','{}',Date.now()+600000);
 const response=await call('/api/connections/facebook/disconnect',{method:'POST',headers:{...ctype,cookie:session.cookie},body:'{}'},env);
 assert.equal(response.status,200);
 const view=await call('/api/connections',{headers:{cookie:session.cookie,origin:appOrigin}},env);
 assert.deepEqual(await view.json(),{facebook:{connected:false},youtube:{connected:true}});
});
test('Meta client secret never appears in token request URL',()=>{
 assert.match(source,/fetch\("https:\/\/graph\.facebook\.com\/v24\.0\/oauth\/access_token",\{method:"POST"/);
 assert.doesNotMatch(source,/endpoint\.search\s*=\s*new URLSearchParams/);
});
