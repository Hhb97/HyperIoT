// REST client + WebSocket realtime channel. The JWT lives in sessionStorage only and is never put in a URL.
const TOKEN_KEY = 'hyperiot.token';
const USER_KEY = 'hyperiot.user';
let memToken = null, memUser = null;

export const auth = {
  get token() { try { return sessionStorage.getItem(TOKEN_KEY) || memToken; } catch { return memToken; } },
  get username() { try { return sessionStorage.getItem(USER_KEY) || memUser; } catch { return memUser; } },
  save(token, username) {
    memToken = token; memUser = username;
    try { sessionStorage.setItem(TOKEN_KEY, token); sessionStorage.setItem(USER_KEY, username || ''); } catch { /* storage unavailable */ }
  },
  clear() {
    memToken = memUser = null;
    try { sessionStorage.removeItem(TOKEN_KEY); sessionStorage.removeItem(USER_KEY); } catch { /* ignore */ }
  },
};

export class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

const NO_REDIRECT_401 = ['/api/auth/login', '/api/auth/password'];
let onUnauthorized = () => {};
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn; };

function qs(query) {
  if (!query) return '';
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  const t = p.toString();
  return t ? '?' + t : '';
}

async function parse(res, path) {
  if (res.status === 204) return null;
  const text = await res.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { /* non-JSON body */ } }
  if (!res.ok) {
    if (res.status === 401 && !NO_REDIRECT_401.includes(path)) onUnauthorized();
    throw new ApiError(res.status, data?.code || 'ERROR', data?.message || (res.status === 429 ? '请求过于频繁，请稍后再试' : `请求失败 (HTTP ${res.status})`));
  }
  return data;
}

export async function request(method, path, { query, body, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (auth.token) headers.Authorization = 'Bearer ' + auth.token;
  let payload;
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  let res;
  try {
    res = await fetch(path + qs(query), { method, headers, body: payload, signal });
  } catch (e) {
    if (e?.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', '无法连接服务器，请检查网络');
  }
  return parse(res, path);
}

export const api = {
  get: (path, query, opt) => request('GET', path, { query, ...opt }),
  post: (path, body, query) => request('POST', path, { body, query }),
  put: (path, body) => request('PUT', path, { body }),
  del: (path) => request('DELETE', path),
};

/** multipart upload with progress (fetch cannot report upload progress). */
export function upload(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', path);
    xhr.setRequestHeader('Accept', 'application/json');
    if (auth.token) xhr.setRequestHeader('Authorization', 'Bearer ' + auth.token);
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress?.(e.loaded / e.total); };
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', '上传失败：网络错误'));
    xhr.onload = () => {
      let data = null;
      try { data = xhr.responseText ? JSON.parse(xhr.responseText) : null; } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) return resolve(data);
      if (xhr.status === 401) onUnauthorized();
      reject(new ApiError(xhr.status, data?.code || 'ERROR', data?.message || `上传失败 (HTTP ${xhr.status})`));
    };
    xhr.send(formData);
  });
}

// ---------- cached lookups ----------
let productCache = null, productCacheAt = 0;
export async function getProducts(force = false) {
  if (!force && productCache && Date.now() - productCacheAt < 20000) return productCache;
  const res = await api.get('/api/products', { page: 1, size: 200 });
  productCache = res.items || []; productCacheAt = Date.now();
  return productCache;
}
export const invalidateProducts = () => { productCache = null; };
export const productLabel = (list, key) => list.find((p) => p.productKey === key)?.name || key;
export async function getThingModelByKey(productKey) {
  const prods = await getProducts();
  const p = prods.find((x) => x.productKey === productKey);
  if (!p) return { properties: [], events: [], services: [] };
  const tm = await api.get(`/api/products/${p.id}/thing-model`);
  return { properties: tm?.properties || [], events: tm?.events || [], services: tm?.services || [] };
}

// ---------- realtime (WebSocket) ----------
const listeners = new Set();
const stateListeners = new Set();
let ws = null, state = 'closed', wanted = false, retry = 0, timer = null;

function setState(s) { state = s; stateListeners.forEach((f) => f(s)); }

function connect() {
  if (!wanted || ws) return;
  setState('connecting');
  const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  let sock;
  try { sock = new WebSocket(url); } catch { return schedule(); }
  ws = sock;
  sock.onopen = () => sock.send(JSON.stringify({ type: 'auth', token: auth.token }));
  sock.onmessage = (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (!m || typeof m !== 'object') return;
    if (m.type === 'ready') { retry = 0; setState('open'); return; }
    listeners.forEach((f) => { try { f(m); } catch (e) { console.error(e); } });
  };
  sock.onclose = () => { if (ws === sock) { ws = null; setState('closed'); schedule(); } };
  sock.onerror = () => { /* onclose follows */ };
}
function schedule() {
  if (!wanted || timer) return;
  const delay = Math.min(30000, 1000 * 2 ** retry) * (0.8 + Math.random() * 0.4);
  retry = Math.min(retry + 1, 6);
  timer = setTimeout(() => { timer = null; connect(); }, delay);
}

export const realtime = {
  get state() { return state; },
  start() { wanted = true; connect(); },
  stop() {
    wanted = false; retry = 0;
    clearTimeout(timer); timer = null;
    const s = ws; ws = null;
    if (s) { s.onclose = null; try { s.close(); } catch { /* ignore */ } }
    setState('closed');
  },
  on(fn) { listeners.add(fn); return () => listeners.delete(fn); },
  onState(fn) { stateListeners.add(fn); fn(state); return () => stateListeners.delete(fn); },
};
