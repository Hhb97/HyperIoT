import "./dom-safe.js";
// App shell: hash router, sidebar, top bar, session handling.
import { api, auth, realtime, setUnauthorizedHandler } from './api.js';
import { h, icon, brand, formModal, field, toast, errorBox, loadingBox } from './ui.js';

const ROUTES = [
  { re: /^#\/login$/, load: () => import('./pages/login.js'), bare: true },
  { re: /^#\/dashboard$/, nav: 'dashboard', load: () => import('./pages/dashboard.js') },
  { re: /^#\/products$/, nav: 'products', load: () => import('./pages/products.js') },
  { re: /^#\/products\/([^/]+)$/, nav: 'products', load: () => import('./pages/product-detail.js') },
  { re: /^#\/devices$/, nav: 'devices', load: () => import('./pages/devices.js') },
  { re: /^#\/devices\/([^/]+)$/, nav: 'devices', load: () => import('./pages/device-detail.js') },
  { re: /^#\/rules$/, nav: 'rules', load: () => import('./pages/rules.js') },
  { re: /^#\/alarms$/, nav: 'alarms', load: () => import('./pages/alarms.js') },
  { re: /^#\/commands$/, nav: 'commands', load: () => import('./pages/commands.js') },
  { re: /^#\/ota$/, nav: 'ota', load: () => import('./pages/ota.js') },
  { re: /^#\/ota\/tasks\/([^/]+)$/, nav: 'ota', load: () => import('./pages/ota-task.js') },
];
const NAV = [
  ['dashboard', '看板', '#/dashboard'], ['products', '产品与物模型', '#/products'], ['devices', '设备', '#/devices'],
  ['rules', '规则引擎', '#/rules'], ['alarms', '告警', '#/alarms'], ['commands', '指令', '#/commands'], ['ota', 'OTA 升级', '#/ota'],
];

const root = document.getElementById('app');
let shell = null;        // {content, title, nav, app}
let pageCleanups = [];
let routeGen = 0;

function buildShell() {
  const nav = h('nav', { class: 'nav', 'aria-label': '主导航' }, NAV.map(([k, t, href]) => h('a', { href, 'data-nav': k }, icon(k), t)));
  const title = h('h1', {}, '');
  const content = h('main', { id: 'content', tabindex: '-1' });
  const wsText = h('span', { class: 'ws-text' }, '实时连接');
  const ind = h('span', { class: 'ws-ind', 'data-state': 'closed', role: 'status', 'aria-live': 'polite' }, h('i'), wsText);
  const STATE_TEXT = { open: '实时已连接', connecting: '连接中…', closed: '实时已断开' };
  const offState = realtime.onState((st) => { ind.dataset.state = st; wsText.textContent = STATE_TEXT[st] || st; });
  const app = h('div', { class: 'app' },
    h('aside', { class: 'sidebar', id: 'sidebar' }, brand(), nav, h('div', { class: 'sidebar-foot' }, 'HyperIoT Console')),
    h('div', { class: 'scrim', onclick: () => app.classList.remove('nav-open') }),
    h('div', { class: 'main-col' },
      h('header', { class: 'topbar' },
        h('button', { type: 'button', class: 'btn btn-icon menu-btn', 'aria-label': '打开菜单', onclick: () => app.classList.toggle('nav-open') }, icon('menu')),
        title, ind,
        h('span', { class: 'user-chip' }, auth.username || ''),
        h('button', { type: 'button', class: 'btn btn-sm', title: '修改密码', onclick: changePasswordDialog }, icon('key'), h('span', { class: 'btn-text' }, '修改密码')),
        h('button', { type: 'button', class: 'btn btn-sm', title: '退出登录', onclick: logout }, icon('logout'), h('span', { class: 'btn-text' }, '退出'))),
      content));
  nav.addEventListener('click', () => app.classList.remove('nav-open'));
  return { app, content, title, nav, offState };
}

function changePasswordDialog() {
  const oldP = h('input', { type: 'password', autocomplete: 'current-password', required: true });
  const newP = h('input', { type: 'password', autocomplete: 'new-password', required: true, minlength: 8 });
  const conf = h('input', { type: 'password', autocomplete: 'new-password', required: true, minlength: 8 });
  formModal({
    title: '修改密码', submitText: '确认修改',
    body: h('div', {}, field('当前密码', oldP, { required: true }), field('新密码', newP, { required: true, hint: '至少 8 位' }), field('确认新密码', conf, { required: true })),
    onSubmit: async () => {
      if (newP.value.length < 8) throw new Error('新密码至少 8 位');
      if (newP.value !== conf.value) throw new Error('两次输入的新密码不一致');
      await api.post('/api/auth/password', { oldPassword: oldP.value, newPassword: newP.value });
      toast('密码已修改', 'success');
    },
  });
}

function logout() {
  auth.clear();
  realtime.stop();
  location.hash = '#/login';
}

function forceLogin() {
  if (!auth.token && location.hash === '#/login') return;
  try { sessionStorage.setItem('hyperiot.returnTo', location.hash); } catch { /* ignore */ }
  auth.clear();
  realtime.stop();
  toast('登录已失效，请重新登录', 'warn');
  location.hash = '#/login';
}
setUnauthorizedHandler(forceLogin);

function makeCtx() {
  const list = pageCleanups; // bound to this navigation; late registrations after leaving run immediately
  const add = (f) => { if (list.done) { try { f(); } catch { /* ignore */ } } else list.push(f); };
  return {
    onCleanup: add,
    interval: (fn, ms) => { const id = setInterval(fn, ms); add(() => clearInterval(id)); return id; },
    setTitle: (t) => { if (shell) shell.title.textContent = t; document.title = `${t} · HyperIoT`; },
  };
}

async function route() {
  const gen = ++routeGen;
  pageCleanups.done = true;
  pageCleanups.forEach((f) => { try { f(); } catch { /* ignore */ } });
  pageCleanups = [];
  let hash = location.hash.split('?')[0];
  if (!hash || hash === '#' || hash === '#/') { location.replace('#/dashboard'); return; }
  const hit = ROUTES.map((r) => ({ r, m: hash.match(r.re) })).find((x) => x.m);
  const loggedIn = !!auth.token;
  if (!loggedIn && !(hit && hit.r.bare)) {
    try { sessionStorage.setItem('hyperiot.returnTo', location.hash); } catch { /* ignore */ }
    location.replace('#/login'); return;
  }
  if (loggedIn && hit?.r.bare) { location.replace('#/dashboard'); return; }

  if (hit?.r.bare) {
    shell?.offState();
    shell = null;
    realtime.stop();
    const box = h('div');
    root.replaceChildren(box);
    const mod = await hit.r.load();
    if (gen !== routeGen) return;
    document.title = '登录 · HyperIoT';
    await mod.default(box, [], makeCtx());
    return;
  }

  if (!shell) { shell = buildShell(); root.replaceChildren(shell.app); }
  realtime.start();
  shell.nav.querySelectorAll('a').forEach((a) => {
    if (hit && a.dataset.nav === hit.r.nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  const content = shell.content;
  content.replaceChildren(loadingBox());
  window.scrollTo(0, 0);
  if (!hit) {
    shell.title.textContent = '页面不存在';
    document.title = '页面不存在 · HyperIoT';
    content.replaceChildren(errorBox(new Error('页面不存在：' + hash)));
    return;
  }
  const params = hit.m.slice(1).map(decodeURIComponent);
  try {
    const mod = await hit.r.load();
    if (gen !== routeGen) return;
    const view = h('div');
    content.replaceChildren(view);
    await mod.default(view, params, makeCtx());
  } catch (e) {
    if (gen !== routeGen) return;
    console.error(e);
    content.replaceChildren(errorBox(e));
  }
}

window.addEventListener('hashchange', route);
route();
