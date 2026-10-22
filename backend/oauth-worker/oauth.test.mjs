import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';

const source = await readFile(new URL('./src/index.js', import.meta.url), 'utf8');
const {default: worker} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const appOrigin = 'https://appassets.androidplatform.net';
const api = 'https://oauth.example.test';
const configured = {
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
