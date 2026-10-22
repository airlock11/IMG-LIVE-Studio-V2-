import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';

const source = await readFile(new URL('./src/index.js', import.meta.url), 'utf8');
const {default: worker} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const appOrigin = 'https://appassets.androidplatform.net';
const api = 'https://oauth.example.test';
const configured = {
  OAUTH_ENABLED: 'true',
  OAUTH_STATE: {get: async () => null, put: async () => {}, delete: async () => {}},
  ACCOUNT_SESSIONS: {get: async () => null, put: async () => {}, delete: async () => {}},
  TOKEN_ENCRYPTION_KEY: 'A'.repeat(43),
  PUBLIC_ORIGIN: api,
  GOOGLE_CLIENT_ID: 'test-google-id',
  GOOGLE_CLIENT_SECRET: 'test-google-secret',
  FACEBOOK_APP_ID: 'test-facebook-id',
  FACEBOOK_APP_SECRET: 'test-facebook-secret'
};
const call = (path, opts = {}, env = configured) => worker.fetch(new Request(api + path, opts), env);

test('health fails closed when provider credentials are missing', async () => {
  const response = await call('/health', {}, {...configured, FACEBOOK_APP_SECRET: undefined});
  assert.equal(response.status, 503);
  assert.equal((await response.json()).configured, false);
});
test('health reports configured only with all bindings', async () => {
  const response = await call('/health');
  assert.equal(response.status, 200);
  assert.equal((await response.json()).configured, true);
});
test('preflight rejects untrusted origins', async () => {
  const response = await call('/api/connections/facebook/authorize', {method:'OPTIONS', headers:{origin:'https://attacker.example'}});
  assert.equal(response.status, 403);
  assert.equal(response.headers.get('access-control-allow-origin'), null);
});
test('preflight accepts only the app origin', async () => {
  const response = await call('/api/connections/facebook/authorize', {method:'OPTIONS', headers:{origin:appOrigin}});
  assert.equal(response.status, 204);
  assert.equal(response.headers.get('access-control-allow-origin'), appOrigin);
});
test('authorization rejects forged origins', async () => {
  const response = await call('/api/connections/facebook/authorize', {method:'POST', headers:{origin:'https://attacker.example','content-type':'application/json'}, body:'{}'});
  assert.equal(response.status, 403);
});
test('authorization requires JSON', async () => {
  const response = await call('/api/connections/facebook/authorize', {method:'POST', headers:{origin:appOrigin,'content-type':'text/plain'}, body:'{}'});
  assert.equal(response.status, 415);
});
test('pairing requires a valid code', async () => {
  const response = await call('/api/connections/facebook/pairing', {method:'POST', headers:{origin:appOrigin,'content-type':'application/json'}, body:'{"pairingCode":"short"}'});
  assert.equal(response.status, 400);
});
test('callback rejects absent state', async () => {
  const response = await call('/api/connections/youtube/callback?code=abc');
  assert.equal(response.status, 400);
});
test('unknown routes return 404', async () => {
  assert.equal((await call('/api/not-found')).status, 404);
});

test('Meta OAuth token exchange must not embed app secrets in URLs', () => {
  assert.match(source, /fetch\("https:\/\/graph\.facebook\.com\/v24\.0\/oauth\/access_token",\{method:"POST"/);
  assert.doesNotMatch(source, /endpoint\.search\s*=\s*new URLSearchParams/);
});

test('session cookie uses secure host-only prefix and strict attributes', () => {
  assert.match(source, /__Host-img_session/);
  assert.match(source, /HttpOnly; Secure; SameSite=None; Path=\//);
  assert.doesNotMatch(source, /"img_session="\+session/);
});

test('pairing cannot be read by another device session', async () => {
  const pairingCode = 'B'.repeat(43);
  const session = 'C'.repeat(43);
  const otherSession = 'D'.repeat(43);
  const hashBytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(pairingCode)));
  const hash = Buffer.from(hashBytes).toString('base64url');
  const stateStore = new Map([['pair:' + hash, JSON.stringify({session, provider:'facebook', status:'complete'})]]);
  const sessionStore = new Map([
    ['session:' + session, '{}'],
    ['session:' + otherSession, '{}']
  ]);
  const env = {
    ...configured,
    OAUTH_STATE: {get: async key => stateStore.get(key) ?? null},
    ACCOUNT_SESSIONS: {get: async key => sessionStore.get(key) ?? null}
  };
  const response = await call('/api/connections/facebook/pairing', {
    method:'POST',
    headers:{origin:appOrigin,'content-type':'application/json',cookie:'__Host-img_session=' + otherSession},
    body:JSON.stringify({pairingCode})
  },env);
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error, 'pairing_session_mismatch');
});
test('pairing cannot complete without an authenticated session', async () => {
  const response = await call('/api/connections/youtube/pairing', {
    method:'POST',
    headers:{origin:appOrigin,'content-type':'application/json'},
    body:JSON.stringify({pairingCode:'B'.repeat(43)})
  });
  assert.equal((await response.json()).status,'expired');
});

test('OAuth API fails closed with malformed public origin', async () => {
  const response = await call('/api/connections', {headers:{origin:appOrigin}}, {...configured, PUBLIC_ORIGIN:'http://untrusted.example'});
  assert.equal(response.status,503);
  assert.equal((await response.json()).error,'invalid_service_origin');
});

test('health rejects insecure public origin', async () => {
  const response = await call('/health', {}, {...configured, PUBLIC_ORIGIN:'http://oauth.example.test'});
  assert.equal(response.status,503);
  assert.equal((await response.json()).configured,false);
});
test('health rejects malformed encryption key', async () => {
  const response = await call('/health', {}, {...configured, TOKEN_ENCRYPTION_KEY:'not-a-valid-key'});
  assert.equal(response.status,503);
  assert.equal((await response.json()).configured,false);
});

test('new deployments deny OAuth API until explicitly enabled',async()=>{
  const env={...configured,OAUTH_ENABLED:'false'};
  const response=await call('/api/connections',{},env);
  assert.equal(response.status,503);
  assert.equal((await response.json()).error,'oauth_not_enabled');
  const health=await call('/health',{},env);
  assert.equal(health.status,503);
  assert.equal((await health.json()).configured,false);
});
test('OAuth public origin cannot contain embedded credentials or custom ports',async()=>{
  for(const url of ['https://user:pass@oauth.example.test','https://oauth.example.test:8443']){
    const response=await call('/health',{}, {...configured,PUBLIC_ORIGIN:url});
    assert.equal(response.status,503);
  }
});
