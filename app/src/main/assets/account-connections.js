/* IMG account authorization controller. No credentials or stream keys are stored in WebView.
 * Requires a deployed IMG OAuth backend and approved provider credentials.
 * Backend contract: GET /api/connections, POST /api/connections/{provider}/authorize
 * returns {authorizationUrl}; authorization URL must be an HTTPS URL on the
 * official provider's authorization host. Tokens stay on the backend.
 */
(function () {
  "use strict";
  const providers = Object.freeze({
    facebook: "www.facebook.com",
    youtube: "accounts.google.com"
  });
  const state = {facebook: "disconnected", youtube: "disconnected"};
  const listeners = new Set();
  let backend = null;
  let refreshPending = null;
  const pairingCodes = {};
  function publish() {
    const snapshot = Object.freeze({...state});
    listeners.forEach(fn => { try { fn(snapshot); } catch (_) {} });
    window.dispatchEvent(new CustomEvent("img-account-status", {detail:snapshot}));
  }
  function configure(url) {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash)
      throw new Error("A trusted HTTPS IMG backend URL is required");
    backend = parsed.origin;
  }
  function requireBackend() {
    if (!backend) throw new Error("Account login is not configured. IMG must deploy its OAuth backend first.");
    return backend;
  }
  async function refresh() {
    if (refreshPending) return refreshPending;
    const origin = requireBackend();
    refreshPending = (async () => {
    const response = await fetch(origin + "/api/connections", {credentials:"include",cache:"no-store"});
    if (!response.ok) throw new Error("Unable to check account connections");
    const data = await response.json();
    for (const name of Object.keys(providers)) {
      const confirmed = data[name]?.connected === true;
      state[name] = confirmed ? "connected" : state[name] === "authorization-pending" ? "authorization-pending" : "disconnected";
    }
    publish();
    return {...state};
    })();
    try { return await refreshPending; } finally { refreshPending = null; }
  }
  async function connect(provider) {
    if (!Object.prototype.hasOwnProperty.call(providers, provider)) throw new Error("Unsupported platform");
    const origin = requireBackend();
    state[provider] = "connecting";
    publish();
    try {
      const response = await fetch(origin + "/api/connections/" + provider + "/authorize",
        {method:"POST",credentials:"include",headers:{"Content-Type":"application/json"},body:"{}"});
      if (!response.ok) throw new Error("Could not start secure authorization");
      const data = await response.json();
      if (typeof data.pairingCode !== "string" || !/^[-_A-Za-z0-9]{43}$/.test(data.pairingCode)) throw new Error("Missing secure pairing code");
      pairingCodes[provider] = data.pairingCode;
      const auth = new URL(data.authorizationUrl);
      if (auth.protocol !== "https:" || auth.hostname !== providers[provider] ||
          auth.username || auth.password) throw new Error("Invalid authorization destination");
      // A native shell opens this in the system browser, not an embedded login WebView.
      if (!window.IMGNativeChannel?.postMessage) throw new Error("Secure account login requires the Android app.");
      window.IMGNativeChannel.postMessage("oauth-open:" + auth.href);
      state[provider] = "authorization-pending";
      publish();
      return true;
    } catch (error) {
      state[provider] = "disconnected";
      publish();
      throw error;
    }
  }
  async function verifyPairing(provider) {
    if (!Object.prototype.hasOwnProperty.call(providers,provider)) throw new Error("Unsupported platform");
    const code=pairingCodes[provider];
    if (!code) return "not_started";
    const response=await fetch(requireBackend()+"/api/connections/"+provider+"/pairing",{method:"POST",credentials:"include",headers:{"Content-Type":"application/json"},body:JSON.stringify({pairingCode:code})});
    if (!response.ok) throw new Error("Unable to verify account pairing");
    const result=await response.json();
    if(result.status==="complete"){delete pairingCodes[provider];await refresh();}
    if(result.status==="expired"){delete pairingCodes[provider];state[provider]="disconnected";publish();}
    return result.status;
  }
  function cancelPending(provider) {
    if (!Object.prototype.hasOwnProperty.call(providers,provider)) throw new Error("Unsupported platform");
    if(state[provider]!=="authorization-pending") return false;
    delete pairingCodes[provider];
    state[provider]="disconnected";
    publish();
    return true;
  }
  async function disconnect(provider) {
    if (!Object.prototype.hasOwnProperty.call(providers, provider)) throw new Error("Unsupported platform");
    const origin = requireBackend();
    const response = await fetch(origin + "/api/connections/" + provider + "/disconnect", {
      method:"POST",credentials:"include",headers:{"Content-Type":"application/json"},body:"{}"
    });
    if (!response.ok) throw new Error("Could not disconnect account");
    const result = await response.json();
    if (result.disconnected !== true || result.provider !== provider) throw new Error("Unverified disconnect");
    state[provider] = "disconnected";
    publish();
    return true;
  }
  // Reconcile only with the backend; returning from the external browser is not proof of login.
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && backend) refresh().catch(() => {});
  });
  window.addEventListener("pageshow", () => { if (backend) refresh().catch(() => {}); });
  window.IMGAccounts = Object.freeze({
    configure, refresh, connect, disconnect, verifyPairing, cancelPending,
    getStatus: () => ({...state}),
    subscribe(fn) { listeners.add(fn); fn({...state}); return () => listeners.delete(fn); }
  });
})();