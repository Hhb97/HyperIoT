// Shared UI helpers. All dynamic content goes through textContent / createTextNode - there is no innerHTML anywhere.

// ---------- DOM builders ----------
const LATE_PROPS = new Set(['value', 'selected']);

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  const late = [];
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (LATE_PROPS.has(k)) { late.push([k, v]); continue; }
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'checked' || k === 'disabled' || k === 'indeterminate') el[k] = !!v;
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, String(v));
    }
  }
  append(el, children);
  for (const [k, v] of late) el[k] = v;
  return el;
}

export function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else if (c instanceof Node) el.appendChild(c);
    else el.appendChild(document.createTextNode(String(c)));
  }
  return el;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
export function s(tag, props, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  if (props) for (const [k, v] of Object.entries(props)) if (v != null && v !== false) el.setAttribute(k, String(v));
  append(el, children);
  return el;
}

const ICONS = {
  dashboard: 'M3 3h7v9H3z M14 3h7v5h-7z M14 12h7v9h-7z M3 16h7v5H3z',
  products: 'M21 8l-9-5-9 5v8l9 5 9-5z M3 8l9 5 9-5 M12 13v8',
  devices: 'M7 7h10v10H7z M10 10h4v4h-4z M9 2v3 M15 2v3 M9 19v3 M15 19v3 M2 9h3 M2 15h3 M19 9h3 M19 15h3',
  rules: 'M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M15 4v4 M7 10v4 M17 16v4',
  alarms: 'M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9 M13.7 21a2 2 0 0 1-3.4 0',
  commands: 'M4 17l6-6-6-6 M12 19h8',
  ota: 'M12 16V4 M7 9l5-5 5 5 M4 20h16',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9',
  key: 'M15 7a4 4 0 1 1-1 7.9L9 20H6v-3l5.1-5A4 4 0 0 1 15 7z',
  menu: 'M3 6h18 M3 12h18 M3 18h18',
  copy: 'M9 9h11v11H9z M5 15H4V4h11v1',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  refresh: 'M21 12a9 9 0 1 1-3-6.7 M21 4v5h-5',
  plus: 'M12 5v14 M5 12h14',
  trash: 'M3 6h18 M8 6V4h8v2 M6 6l1 14h10l1-14',
  x: 'M6 6l12 12 M18 6L6 18',
};
export function icon(name) {
  return s('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true', focusable: 'false' }, s('path', { d: ICONS[name] || '' }));
}
export function brand() {
  const logo = s('svg', { viewBox: '0 0 32 32', 'aria-hidden': 'true' },
    s('rect', { x: 1.5, y: 1.5, width: 29, height: 29, rx: 8, fill: 'none', stroke: 'var(--blue)', 'stroke-width': 2, 'stroke-dasharray': '4 3' }),
    s('circle', { cx: 16, cy: 16, r: 3.2, fill: 'var(--blue)' }),
    s('path', { d: 'M9.5 16a6.5 6.5 0 0 1 13 0 M6.5 16a9.5 9.5 0 0 1 19 0', fill: 'none', stroke: 'var(--green)', 'stroke-width': 2, 'stroke-linecap': 'round' }));
  return h('div', { class: 'brand' }, logo, h('div', {}, 'HyperIoT', h('small', {}, '物联网平台控制台')));
}
export function iconBtn(name, label, onclick, cls = '') {
  return h('button', { type: 'button', class: 'btn btn-icon ' + cls, 'aria-label': label, title: label, onclick }, icon(name));
}

// ---------- misc utils ----------
let uidN = 0;
export const uid = (p = 'id') => `${p}-${++uidN}`;
export function debounce(fn, ms = 300) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

// ---------- formatting ----------
const pad = (n) => String(n).padStart(2, '0');
export function fmtTime(ts, withDate = true) {
  if (ts == null || ts === 0 || ts === '') return '-';
  const d = new Date(Number(ts));
  if (isNaN(d)) return '-';
  const t = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return withDate ? `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${t}` : t;
}
export function fmtHM(ts) { const d = new Date(ts); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; }
export function relTime(ts) {
  if (ts == null || ts === 0 || ts === '') return '-';
  const diff = Date.now() - Number(ts);
  const a = Math.abs(diff), suf = diff >= 0 ? '前' : '后';
  if (a < 5000) return '刚刚';
  if (a < 60000) return `${Math.floor(a / 1000)} 秒${suf}`;
  if (a < 3600000) return `${Math.floor(a / 60000)} 分钟${suf}`;
  if (a < 86400000) return `${Math.floor(a / 3600000)} 小时${suf}`;
  if (a < 30 * 86400000) return `${Math.floor(a / 86400000)} 天${suf}`;
  return fmtTime(ts).slice(0, 10);
}
/** <time> element: relative text, absolute time on hover. */
export function timeEl(ts) {
  if (!ts) return h('span', { class: 'muted' }, '-');
  return h('time', { datetime: new Date(Number(ts)).toISOString(), title: fmtTime(ts) }, relTime(ts));
}
export function fmtSize(n) {
  if (n == null) return '-';
  const u = ['B', 'KB', 'MB', 'GB']; let i = 0; let v = Number(n);
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${i === 0 ? v : v.toFixed(v >= 100 ? 0 : 1)} ${u[i]}`;
}
export function fmtValue(v) {
  if (v === null || v === undefined) return '-';
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : String(Math.round(v * 10000) / 10000);
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
export function compactJson(v, max = 120) {
  if (v === null || v === undefined || v === '') return '-';
  let t; try { t = typeof v === 'string' ? v : JSON.stringify(v); } catch { t = String(v); }
  return t.length > max ? t.slice(0, max) + '…' : t;
}
export function jsonBlock(v) {
  let t;
  if (typeof v === 'string') { try { t = JSON.stringify(JSON.parse(v), null, 2); } catch { t = v; } }
  else t = JSON.stringify(v, null, 2);
  return h('pre', { class: 'json' }, t ?? '');
}

// ---------- clipboard ----------
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text);
    else throw new Error('insecure');
  } catch {
    const ta = h('textarea', { 'aria-hidden': 'true', style: { position: 'fixed', opacity: '0', top: '0' } });
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { /* ignore */ }
    ta.remove();
    if (!ok) { toast('复制失败，请手动复制', 'error'); return false; }
  }
  toast('已复制到剪贴板', 'success');
  return true;
}
export function copyBtn(getText, label = '复制') {
  return h('button', { type: 'button', class: 'btn btn-sm btn-ghost', 'aria-label': label, title: label, onclick: () => copyText(typeof getText === 'function' ? getText() : getText) }, icon('copy'));
}

// ---------- toast ----------
export function toast(message, type = 'info', ms) {
  let box = document.getElementById('toasts');
  if (!box) { box = h('div', { id: 'toasts', 'aria-live': 'polite', role: 'status' }); document.body.appendChild(box); }
  const el = h('div', { class: 'toast ' + type }, message);
  box.appendChild(el);
  setTimeout(() => el.remove(), ms ?? (type === 'error' ? 6000 : 3200));
}
export const toastError = (e) => toast(e?.message || String(e), 'error');

// ---------- badges ----------
export function badge(text, tone = '', dot = false) {
  return h('span', { class: `badge ${tone}${dot ? ' dot' : ''}` }, text);
}
const MAPS = {
  device: { ONLINE: ['在线', 'green'], OFFLINE: ['离线', ''], INACTIVE: ['未激活', 'amber'] },
  command: { PENDING: ['待下发', 'amber'], SENT: ['已下发', 'blue'], SUCCESS: ['成功', 'green'], FAILED: ['失败', 'red'], TIMEOUT: ['超时', 'amber'] },
  alarm: { ACTIVE: ['活跃', 'red'], ACKED: ['已确认', 'amber'], RECOVERED: ['已恢复', 'green'] },
  level: { INFO: ['提示', 'blue'], WARN: ['警告', 'amber'], CRITICAL: ['严重', 'red'] },
  ota: { RUNNING: ['进行中', 'blue'], COMPLETED: ['已完成', 'green'], CANCELED: ['已取消', ''] },
  otaDev: { PENDING: ['等待', ''], NOTIFIED: ['已通知', 'blue'], DOWNLOADING: ['下载中', 'blue'], SUCCESS: ['成功', 'green'], FAILED: ['失败', 'red'], CANCELED: ['已取消', ''] },
  dir: { UP: ['上行 UP', 'blue'], DOWN: ['下行 DOWN', 'green'], SYS: ['系统 SYS', ''] },
};
export function statusBadge(kind, value) {
  const [t, tone] = MAPS[kind]?.[value] || [value ?? '-', ''];
  return badge(t, tone, kind !== 'level' && kind !== 'dir');
}
export const LABELS = {
  nodeType: { DEVICE: '直连设备', GATEWAY: '网关' },
  logic: { AND: '全部满足 (AND)', OR: '任一满足 (OR)' },
  op: { GT: '>', GE: '≥', LT: '<', LE: '≤', EQ: '=', NE: '≠' },
};

// ---------- states ----------
export const loadingBox = (text = '加载中…') => h('div', { class: 'state', role: 'status' }, h('span', { class: 'spinner' }), text);
export const emptyBox = (text = '暂无数据') => h('div', { class: 'state' }, text);
export function errorBox(err, retry) {
  return h('div', { class: 'state err', role: 'alert' },
    h('div', {}, err?.message || String(err)),
    retry && h('button', { type: 'button', class: 'btn btn-sm', style: { marginTop: '10px' }, onclick: retry }, '重试'));
}
/** Fill `container` with the node returned by async `fn`, showing loading / error states. */
export async function fillAsync(container, fn) {
  container.replaceChildren(loadingBox());
  try {
    const node = await fn();
    container.replaceChildren(...(node ? [node] : []));
  } catch (e) {
    container.replaceChildren(errorBox(e, () => fillAsync(container, fn)));
  }
}

// ---------- layout helpers ----------
export function pageHead(title, sub, ...actions) {
  return h('div', { class: 'page-head' },
    h('div', { class: 'grow' }, h('h2', {}, title), sub && h('div', { class: 'sub' }, sub)),
    actions.length ? h('div', { class: 'row' }, actions) : null);
}
export function kvList(pairs, cls = 'kv') {
  const dl = h('dl', { class: cls });
  for (const [k, v] of pairs) {
    dl.append(h('dt', {}, k), h('dd', {}, v == null || v === '' ? h('span', { class: 'muted' }, '-') : v));
  }
  return dl;
}
export function progressBar(pct, tone = '') {
  const p = Math.max(0, Math.min(100, Number(pct) || 0));
  return h('div', { class: 'progress ' + tone, role: 'progressbar', 'aria-valuenow': Math.round(p), 'aria-valuemin': 0, 'aria-valuemax': 100 }, h('span', { style: { width: p + '%' } }));
}

// ---------- form controls ----------
export function field(label, control, { hint, required, cls = '' } = {}) {
  return h('label', { class: 'field ' + cls }, h('span', { class: 'field-label' + (required ? ' req' : '') }, label), control, hint && h('small', {}, hint));
}
export function options(list, selected) {
  return list.map((o) => {
    const [v, t] = Array.isArray(o) ? o : [o, o];
    return h('option', { value: v, selected: String(v) === String(selected) }, t);
  });
}
export function select(list, value, props = {}) {
  return h('select', { ...props, value }, options(list, value));
}
export function switchEl({ checked, onChange, label, disabled }) {
  const input = h('input', { type: 'checkbox', role: 'switch', checked, disabled, 'aria-label': label });
  if (onChange) input.addEventListener('change', () => onChange(input.checked, input));
  return h('label', { class: 'switch' }, input, h('span', { class: 'track' }), label && h('span', {}, label));
}
export function segmented(items, value, onChange) {
  const wrap = h('div', { class: 'seg', role: 'group' });
  const render = (cur) => wrap.replaceChildren(...items.map(([k, t]) =>
    h('button', { type: 'button', 'aria-pressed': String(k === cur), onclick: () => { render(k); onChange(k); } }, t)));
  render(value);
  return wrap;
}
export function secretText(getSecret) {
  let shown = false;
  const code = h('code', {}, '••••••••••••');
  const eye = h('button', { type: 'button', class: 'btn btn-sm btn-ghost', 'aria-label': '显示/隐藏', title: '显示/隐藏', onclick: () => {
    shown = !shown; code.textContent = shown ? getSecret() : '••••••••••••';
  } }, icon('eye'));
  return h('span', { class: 'secret' }, code, eye, copyBtn(getSecret));
}

// ---------- tables ----------
export function table(columns, rows) {
  const thead = h('thead', {}, h('tr', {}, columns.map((c) => h('th', { class: c.class || '', scope: 'col' }, c.title))));
  const tbody = h('tbody');
  rows.forEach((row, i) => {
    tbody.append(h('tr', {}, columns.map((c) => {
      const v = c.render(row, i);
      return h('td', { class: c.class || '' }, v == null || v === '' ? h('span', { class: 'muted' }, '-') : v);
    })));
  });
  return h('div', { class: 'table-wrap' }, h('table', { class: 't' }, thead, tbody));
}

export function pager({ page, size, total, onChange }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return h('div', { class: 'pager' },
    h('span', { class: 'pager-info' }, `共 ${total} 条`),
    h('button', { type: 'button', class: 'btn btn-sm', disabled: page <= 1, onclick: () => onChange(page - 1) }, '上一页'),
    h('span', {}, `${page} / ${pages}`),
    h('button', { type: 'button', class: 'btn btn-sm', disabled: page >= pages, onclick: () => onChange(page + 1) }, '下一页'));
}

/** Paginated table. fetch(page,size) -> {total, items}. */
export function listView({ fetch, columns, size = 20, empty = '暂无数据', onData, silentReload = true }) {
  const root = h('div');
  const body = h('div');
  const foot = h('div');
  root.append(body, foot);
  let page = 1, seq = 0, loaded = false;
  async function load(p = page) {
    page = p;
    const my = ++seq;
    if (!loaded || !silentReload) body.replaceChildren(loadingBox()); else body.classList.add('is-loading');
    try {
      const res = await fetch(page, size);
      if (my !== seq) return;
      const items = res.items || [];
      if (!items.length && page > 1 && res.total > 0) return load(page - 1);
      loaded = true;
      body.classList.remove('is-loading');
      body.replaceChildren(items.length ? table(columns, items) : emptyBox(empty));
      foot.replaceChildren(res.total > size ? pager({ page, size, total: res.total, onChange: load }) : h('div', { class: 'pager' }, h('span', { class: 'pager-info' }, `共 ${res.total ?? items.length} 条`)));
      onData?.(res);
    } catch (e) {
      if (my !== seq) return;
      loaded = false;
      body.classList.remove('is-loading');
      body.replaceChildren(errorBox(e, () => load(page)));
      foot.replaceChildren();
    }
  }
  load(1);
  return { el: root, reload: () => load(page), reset: () => load(1) };
}

// ---------- tabs ----------
/** items: [{key,label,render(pane, tabCtx), canLeave?()}] */
export function tabs(items, { initial, onChange } = {}) {
  const bar = h('div', { class: 'tabs', role: 'tablist' });
  const panel = h('div', { class: 'tab-panel', role: 'tabpanel' });
  let current = null, cleanups = [];
  const buttons = new Map();
  const runCleanups = () => { cleanups.forEach((f) => { try { f(); } catch { /* ignore */ } }); cleanups = []; };
  async function select(key, force = false) {
    if (key === current && !force) return;
    const prev = items.find((i) => i.key === current);
    if (prev?.canLeave && key !== current && !(await prev.canLeave())) return;
    runCleanups();
    current = key;
    for (const [k, b] of buttons) { b.setAttribute('aria-selected', String(k === key)); b.tabIndex = k === key ? 0 : -1; }
    const it = items.find((i) => i.key === key);
    const pane = h('div');
    panel.replaceChildren(pane);
    const tctx = {
      onCleanup: (f) => cleanups.push(f),
      interval: (fn, ms) => { const id = setInterval(fn, ms); cleanups.push(() => clearInterval(id)); return id; },
      reload: () => select(key, true),
    };
    onChange?.(key);
    try { await it.render(pane, tctx); } catch (e) { pane.replaceChildren(errorBox(e)); }
  }
  items.forEach((it, idx) => {
    const b = h('button', { type: 'button', role: 'tab', 'aria-selected': 'false', onclick: () => select(it.key),
      onkeydown: (e) => {
        const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
        if (!d) return;
        const n = items[(idx + d + items.length) % items.length];
        buttons.get(n.key).focus(); select(n.key);
      } }, it.label);
    buttons.set(it.key, b);
    bar.append(b);
  });
  select(initial || items[0].key);
  return { el: h('div', {}, bar, panel), select, destroy: runCleanups };
}

// ---------- dialogs ----------
/**
 * Generic modal form. onSubmit(form) may return false to keep the dialog open; throwing shows the message inline.
 * cancelText=null hides the cancel button.
 */
export function formModal({ title, body, submitText = '保存', cancelText = '取消', onSubmit, size = '', danger = false }) {
  const titleId = uid('dlg');
  const err = h('div', { class: 'form-error', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'btn ' + (danger ? 'btn-danger' : 'btn-primary') }, submitText);
  // finish() closes + removes the dialog and runs afterClose callbacks synchronously (not relying on the async 'close' event).
  let finished = false;
  const afterClose = [];
  const finish = () => {
    if (finished) return;
    finished = true;
    try { if (dlg.open) dlg.close(); } catch { /* ignore */ }
    dlg.remove();
    afterClose.forEach((f) => f());
  };
  const cancel = cancelText === null ? null : h('button', { type: 'button', class: 'btn', 'data-cancel': '', onclick: finish }, cancelText);
  const form = h('form', {
    class: 'modal-form',
    onsubmit: async (e) => {
      e.preventDefault();
      err.hidden = true;
      submit.disabled = true; submit.classList.add('is-busy');
      try {
        const r = await onSubmit?.(form);
        if (r !== false) finish();
      } catch (ex) {
        err.textContent = ex?.message || String(ex);
        err.hidden = false;
        err.scrollIntoView({ block: 'nearest' });
      } finally { submit.disabled = false; submit.classList.remove('is-busy'); }
    },
  }, h('div', { class: 'modal-head', id: titleId }, title), h('div', { class: 'modal-body' }, body), err, h('div', { class: 'modal-foot' }, cancel, submit));
  const dlg = h('dialog', { class: 'modal ' + size, 'aria-labelledby': titleId }, form);
  dlg.addEventListener('close', finish); // Esc key
  dlg.afterClose = (fn) => afterClose.push(fn);
  dlg.finish = finish;
  document.body.appendChild(dlg);
  dlg.showModal();
  const first = danger ? cancel : form.querySelector('.modal-body input:not([type=hidden]):not([disabled]), .modal-body select, .modal-body textarea');
  (first || submit).focus();
  dlg.setError = (m) => { err.textContent = m; err.hidden = !m; };
  return dlg;
}
export function confirmDialog({ title = '请确认', message, confirmText = '确定', danger = false }) {
  return new Promise((resolve) => {
    let ok = false;
    const dlg = formModal({ title, body: h('p', { class: 'confirm-msg' }, message), submitText: confirmText, danger, onSubmit: () => { ok = true; } });
    dlg.afterClose(() => resolve(ok));
  });
}
export function infoDialog(title, body, size = '') {
  return formModal({ title, body, submitText: '关闭', cancelText: null, size, onSubmit: () => {} });
}
/** Show one device secret. */
export function secretDialog(title, rows) {
  const body = h('div', {}, rows.map(([k, v, secret]) => h('div', { class: 'field' }, h('span', { class: 'field-label' }, k),
    secret ? h('span', { class: 'secret' }, h('code', {}, v), copyBtn(v)) : h('div', {}, v))));
  return infoDialog(title, body);
}

// ---------- CSV ----------
export function toCsv(rows) {
  const esc = (v) => {
    let t = String(v ?? '');
    if (/^[=+\-@\t\r]/.test(t)) t = "'" + t; // neutralise spreadsheet formula injection
    return /[",\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t;
  };
  return rows.map((r) => r.map(esc).join(',')).join('\n');
}
export function downloadText(filename, text, mime = 'text/csv') {
  const url = URL.createObjectURL(new Blob([text], { type: mime + ';charset=utf-8' }));
  const a = h('a', { href: url, download: filename });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
