// Tabs of the device detail page. Each tab: (pane, tctx, dd) where dd = {dev, thingModel(), refreshHeader()}.
import { api, realtime } from '../api.js';
import {
  h, field, select, table, listView, loadingBox, emptyBox, errorBox, statusBadge, timeEl, fmtTime, fmtValue, compactJson, jsonBlock,
  switchEl, segmented, confirmDialog, toast, toastError,
} from '../ui.js';
import { valueControl, tagsEditor, typeHint, NUMERIC } from '../forms.js';
import { lineChart } from '../charts.js';
import { commandColumns } from './commands.js';

const isThisDevice = (dev, m) => Number(m.deviceId) === Number(dev.id);
const withUnit = (v, def) => (v === undefined ? '-' : fmtValue(v) + (def?.unit ? ' ' + def.unit : ''));
const flash = (el) => { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); };

// ---------- 实时属性 ----------
export async function propsTab(pane, tctx, dd) {
  const { dev } = dd;
  pane.append(loadingBox());
  const [shadow, tm] = await Promise.all([api.get(`/api/devices/${dev.id}/shadow`), dd.thingModel()]);
  let reported = shadow.reported || {}, desired = shadow.desired || {}, version = shadow.version;
  const defs = new Map(tm.properties.map((p) => [p.identifier, p]));
  const cells = new Map();
  const box = h('div');
  const meta = h('span', { class: 'hint' });

  async function reload() {
    try {
      const s = await api.get(`/api/devices/${dev.id}/shadow`);
      reported = s.reported || {}; desired = s.desired || {}; version = s.version;
      render();
    } catch (e) { toastError(e); }
  }

  function setRow(def, id) {
    if (def?.access !== 'rw') return h('span', { class: 'muted' }, '只读');
    const ctl = valueControl(def, desired[id] ?? reported[id]?.value);
    ctl.el.style.width = '150px';
    const btn = h('button', { type: 'button', class: 'btn btn-sm btn-primary', onclick: async () => {
      let v;
      try { v = ctl.get(); } catch (e) { toast(e.message, 'warn'); return; }
      btn.disabled = true;
      try {
        await api.put(`/api/devices/${dev.id}/desired`, { params: { [id]: v } });
        desired[id] = v;
        const c = cells.get(id); if (c) { c.des.textContent = fmtValue(v); flash(c.des); }
        toast(dev.status === 'ONLINE' ? `已下发 ${id} = ${fmtValue(v)}` : `已保存期望值，设备上线后下发`, 'success');
      } catch (e) { toastError(e); } finally { btn.disabled = false; }
    } }, '下发');
    return h('div', { class: 'row', style: { flexWrap: 'nowrap' } }, ctl.el, btn);
  }

  function render() {
    cells.clear();
    const ids = [...tm.properties.map((p) => p.identifier), ...Object.keys(reported).filter((k) => !defs.has(k))];
    meta.textContent = `影子版本 ${version ?? '-'}`;
    if (!ids.length) { box.replaceChildren(emptyBox('该产品物模型尚未定义属性，设备也还没有上报数据')); return; }
    box.replaceChildren(table([
      { title: '属性', render: (r) => h('div', {}, h('div', {}, r.def?.name || r.id), h('code', { class: 'muted' }, r.id)) },
      { title: '类型', render: (r) => r.def && h('span', { class: 'hint' }, typeHint(r.def)) },
      { title: '当前值', render: (r) => { const el = h('b', {}, withUnit(reported[r.id]?.value, r.def)); cells.set(r.id, { ...(cells.get(r.id) || {}), val: el }); return el; } },
      { title: '更新时间', render: (r) => { const el = h('span', {}, reported[r.id] ? timeEl(reported[r.id].ts) : '-'); cells.set(r.id, { ...(cells.get(r.id) || {}), ts: el }); return el; } },
      { title: '期望值', render: (r) => { const el = h('span', {}, desired[r.id] !== undefined ? fmtValue(desired[r.id]) : '-'); cells.set(r.id, { ...(cells.get(r.id) || {}), des: el }); return el; } },
      { title: '设置', render: (r) => setRow(r.def, r.id) },
    ], ids.map((id) => ({ id, def: defs.get(id) }))));
  }
  pane.replaceChildren(
    h('div', { class: 'toolbar' }, h('span', { class: 'ws-ind', 'data-state': 'open' }, h('i'), '实时更新中'), meta, h('span', { class: 'spacer' }),
      h('button', { type: 'button', class: 'btn btn-sm', onclick: reload }, '刷新')),
    box);
  render();

  tctx.onCleanup(realtime.on((m) => {
    if (m.type !== 'telemetry' || !isThisDevice(dev, m)) return;
    let rebuild = false;
    for (const [k, v] of Object.entries(m.params || {})) {
      reported[k] = { value: v, ts: m.ts };
      const c = cells.get(k);
      if (!c) { rebuild = true; continue; }
      c.val.textContent = withUnit(v, defs.get(k)); flash(c.val);
      c.ts.replaceChildren(timeEl(m.ts));
    }
    if (rebuild) render();
  }));
  tctx.interval(() => { for (const [k, c] of cells) if (reported[k] && c.ts) c.ts.replaceChildren(timeEl(reported[k].ts)); }, 15000);
}

// ---------- 历史曲线 ----------
const RANGES = [['1h', '1 小时', 3600e3], ['6h', '6 小时', 6 * 3600e3], ['24h', '24 小时', 86400e3], ['7d', '7 天', 7 * 86400e3]];
const AUTO_INTERVAL = { '1h': 60000, '6h': 300000, '24h': 900000, '7d': 3600000 };
const INTERVALS = [[60000, '1 分钟'], [300000, '5 分钟'], [900000, '15 分钟'], [3600000, '1 小时'], [21600000, '6 小时']];
const AGGS = [['raw', '原始值'], ['avg', '平均'], ['max', '最大'], ['min', '最小'], ['last', '最后值']];

export async function historyTab(pane, tctx, dd) {
  const { dev } = dd;
  const tm = await dd.thingModel();
  const plottable = tm.properties;
  const firstNum = plottable.find((p) => NUMERIC.has(p.type)) || plottable[0];
  let range = '1h', agg = 'raw', interval = AUTO_INTERVAL['1h'], seq = 0, intervalTouched = false;
  const idSel = plottable.length
    ? select(plottable.map((p) => [p.identifier, `${p.name || p.identifier} (${p.identifier})`]), firstNum.identifier, { 'aria-label': '属性', onchange: load })
    : h('input', { type: 'text', placeholder: '属性标识符', 'aria-label': '属性标识符', onchange: load });
  const aggSel = select(AGGS, agg, { 'aria-label': '聚合方式', onchange: (e) => { agg = e.target.value; syncInterval(); load(); } });
  const intSel = select(INTERVALS, interval, { 'aria-label': '聚合间隔', onchange: (e) => { interval = Number(e.target.value); intervalTouched = true; load(); } });
  const syncInterval = () => { intSel.disabled = agg === 'raw'; intSel.value = String(interval); };
  const seg = segmented(RANGES.map(([k, t]) => [k, t]), range, (k) => { range = k; if (!intervalTouched) interval = AUTO_INTERVAL[k]; syncInterval(); load(); });
  const out = h('div');
  const summary = h('div', { class: 'hint', style: { marginTop: '8px' } });
  syncInterval();

  async function load() {
    const id = idSel.value.trim();
    if (!id) { out.replaceChildren(emptyBox('请选择或输入属性标识符')); return; }
    const my = ++seq;
    out.replaceChildren(loadingBox()); summary.textContent = '';
    const to = Date.now(), from = to - RANGES.find((r) => r[0] === range)[2];
    try {
      const res = await api.get(`/api/devices/${dev.id}/telemetry`, { identifier: id, from, to, agg, interval: agg === 'raw' ? undefined : interval, limit: 5000 });
      if (my !== seq) return;
      const raw = res.points || [];
      const def = plottable.find((p) => p.identifier === id);
      const pts = [];
      let numeric = true;
      for (const p of raw) {
        const v = typeof p.value === 'boolean' ? (p.value ? 1 : 0) : typeof p.value === 'number' ? p.value : (p.value !== '' && p.value !== null && isFinite(Number(p.value)) ? Number(p.value) : NaN);
        if (isNaN(v)) { numeric = false; break; }
        pts.push({ ts: p.ts, value: v });
      }
      if (!raw.length) { out.replaceChildren(emptyBox('该时间范围内没有数据')); return; }
      if (!numeric) {
        out.replaceChildren(table([{ title: '时间', render: (p) => fmtTime(p.ts) }, { title: '值', render: (p) => fmtValue(p.value) }], raw.slice(-200).reverse()));
        summary.textContent = `非数值类型，仅显示最近 ${Math.min(200, raw.length)} 条`;
        return;
      }
      pts.sort((a, b) => a.ts - b.ts);
      out.replaceChildren(lineChart(pts, { unit: def?.unit || '', name: def?.name || id }));
      const vals = pts.map((p) => p.value);
      const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
      summary.textContent = `${pts.length} 个点 · 最小 ${fmtValue(Math.min(...vals))} · 最大 ${fmtValue(Math.max(...vals))} · 平均 ${fmtValue(avg)} · 最新 ${fmtValue(vals[vals.length - 1])}${def?.unit ? ' ' + def.unit : ''}`;
    } catch (e) { if (my === seq) out.replaceChildren(errorBox(e, load)); }
  }
  pane.append(
    h('div', { class: 'toolbar' }, idSel, seg, aggSel, intSel, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: load }, '刷新')),
    h('div', { class: 'card' }, out, summary));
  await load();
}

// ---------- 事件 ----------
export async function eventsTab(pane, tctx, dd) {
  const { dev } = dd;
  const tm = await dd.thingModel();
  const names = new Map(tm.events.map((e) => [e.identifier, e.name]));
  const lv = listView({
    empty: '暂无事件', size: 20,
    fetch: (page, size) => api.get(`/api/devices/${dev.id}/events`, { page, size }),
    columns: [
      { title: '时间', class: 'nowrap', render: (e) => fmtTime(e.ts) },
      { title: '事件', render: (e) => h('span', {}, names.get(e.identifier) || '', ' ', h('code', { class: 'muted' }, e.identifier)) },
      { title: '级别', render: (e) => e.level && statusBadge('level', e.level) },
      { title: '参数', class: 'wrap', render: (e) => h('code', {}, compactJson(e.params, 300)) },
    ],
  });
  pane.append(h('div', { class: 'toolbar' }, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新')), lv.el);
  tctx.interval(() => lv.reload(), 15000);
}

// ---------- 服务调用 ----------
export async function servicesTab(pane, tctx, dd) {
  const { dev } = dd;
  const tm = await dd.thingModel();
  const left = h('section', { class: 'card' }, h('h3', {}, '调用服务'));
  const hist = listView({
    empty: '该设备暂无指令记录', size: 10,
    fetch: (page, size) => api.get(`/api/devices/${dev.id}/commands`, { page, size }),
    columns: commandColumns(false),
  });
  const right = h('section', { class: 'card' }, h('div', { class: 'card-title' }, '指令记录', h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => hist.reload() }, '刷新')), hist.el);
  pane.append(h('div', { class: 'grid-2' }, left, right));

  const result = h('div', { style: { marginTop: '14px' } });
  let lastCmdId = null;
  const showResult = (cmd) => {
    lastCmdId = cmd.id;
    result.replaceChildren(h('div', { class: 'row', style: { marginBottom: '8px' } }, h('b', {}, '调用结果'), statusBadge('command', cmd.status), h('span', { class: 'hint' }, 'requestId: ' + (cmd.requestId ?? '-'))),
      cmd.response != null && cmd.response !== '' ? jsonBlock(cmd.response) : h('div', { class: 'hint' }, cmd.status === 'PENDING' || cmd.status === 'SENT' ? '等待设备应答…（状态会实时更新）' : '无应答内容'));
  };

  if (!tm.services.length) {
    left.append(emptyBox('该产品物模型尚未定义服务，请先在产品的「物模型」中添加'));
  } else {
    const svcSel = select(tm.services.map((sv) => [sv.identifier, `${sv.name || sv.identifier} (${sv.identifier})`]), tm.services[0].identifier, { 'aria-label': '服务', onchange: buildForm });
    const formBox = h('div');
    const wait = h('input', { type: 'checkbox', checked: true });
    const timeout = h('input', { type: 'number', min: 100, step: 100, value: 5000, 'aria-label': '等待超时(ms)' });
    const ttl = h('input', { type: 'number', min: 1, value: '', placeholder: '默认', 'aria-label': 'TTL(秒)' });
    let controls = [];
    function buildForm() {
      const sv = tm.services.find((x) => x.identifier === svcSel.value);
      controls = (sv.input || []).map((p) => ({ p, ctl: valueControl(p) }));
      formBox.replaceChildren(...(controls.length ? controls.map(({ p, ctl }) => field(`${p.name || p.identifier}`, ctl.el, { hint: `${p.identifier} · ${typeHint(p)}` })) : [h('div', { class: 'hint', style: { marginBottom: '10px' } }, '该服务无输入参数')]));
    }
    buildForm();
    const send = h('button', { type: 'button', class: 'btn btn-primary', onclick: async () => {
      const params = {};
      try { for (const { p, ctl } of controls) params[p.identifier] = ctl.get(); } catch (e) { toast(e.message, 'warn'); return; }
      send.disabled = true; send.classList.add('is-busy');
      try {
        const body = { params };
        if (ttl.value) body.ttlSeconds = Number(ttl.value);
        const cmd = await api.post(`/api/devices/${dev.id}/services/${encodeURIComponent(svcSel.value)}/invoke`, body, { wait: wait.checked, timeoutMs: wait.checked ? Number(timeout.value) || 5000 : undefined });
        showResult(cmd);
        toast('指令已提交', 'success');
        hist.reload();
      } catch (e) { toastError(e); } finally { send.disabled = false; send.classList.remove('is-busy'); }
    } }, '调用');
    left.append(field('服务', svcSel), formBox,
      h('div', { class: 'field' }, h('label', { class: 'check' }, wait, '等待设备应答（同步返回结果）')),
      h('div', { class: 'form-grid' }, field('等待超时 (ms)', timeout), field('指令有效期 TTL (秒)', ttl, { hint: '留空使用平台默认值' })),
      send, result);
  }

  let t;
  tctx.onCleanup(realtime.on((m) => {
    if (m.type !== 'command' || !m.command || Number(m.command.deviceId) !== Number(dev.id)) return;
    if (m.command.id === lastCmdId) showResult(m.command);
    clearTimeout(t); t = setTimeout(() => hist.reload(), 400);
  }));
  tctx.onCleanup(() => clearTimeout(t));
}

// ---------- 设备日志 ----------
export function logsTab(pane, tctx, dd) {
  const { dev } = dd;
  const lv = listView({
    empty: '暂无日志', size: 30,
    fetch: (page, size) => api.get(`/api/devices/${dev.id}/logs`, { page, size }),
    columns: [
      { title: '时间', class: 'nowrap', render: (l) => fmtTime(l.ts) },
      { title: '方向', render: (l) => statusBadge('dir', l.direction) },
      { title: '主题', class: 'wrap', render: (l) => l.topic && h('code', {}, l.topic) },
      { title: '内容', class: 'wrap', render: (l) => h('pre', { class: 'json', title: String(l.payload ?? '') }, compactJson(l.payload, 600)) },
    ],
  });
  let auto = false;
  const sw = switchEl({ checked: false, label: '自动刷新（5 秒）', onChange: (c) => { auto = c; } });
  pane.append(h('div', { class: 'toolbar' }, sw, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新')), lv.el);
  tctx.interval(() => { if (auto) lv.reload(); }, 5000);
}

// ---------- 设备配置 ----------
export function settingsTab(pane, tctx, dd) {
  const { dev } = dd;
  const nick = h('input', { type: 'text', maxlength: 64, value: dev.nickname || '' });
  const tags = tagsEditor(dev.tags || {});
  let enabled = dev.enabled !== false;
  const sw = switchEl({ checked: enabled, label: '启用设备（禁用后将断开连接并拒绝接入）', onChange: (c) => { enabled = c; } });
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      try {
        const d = await api.put(`/api/devices/${dev.id}`, { nickname: nick.value.trim(), tags: tags.get(), enabled });
        Object.assign(dev, d || { nickname: nick.value.trim(), tags: tags.get(), enabled });
        dd.refreshHeader();
        toast('设备配置已保存', 'success');
      } catch (ex) { toastError(ex); }
    },
  }, field('备注名', nick), h('div', { class: 'field' }, h('span', { class: 'field-label' }, '标签'), tags.el), h('div', { class: 'field' }, sw), h('button', { type: 'submit', class: 'btn btn-primary' }, '保存'));
  pane.append(
    h('section', { class: 'card', style: { maxWidth: '640px' } }, h('h3', {}, '设备配置'), form),
    h('div', { class: 'danger-zone', style: { marginTop: '16px', maxWidth: '640px' } },
      h('div', { class: 'grow' }, h('b', {}, '删除设备'), h('div', { class: 'hint' }, '同时删除设备影子与全部历史数据，不可恢复。')),
      h('button', { type: 'button', class: 'btn btn-outline-danger', onclick: async () => {
        if (!(await confirmDialog({ title: '删除设备', message: `确定删除设备「${dev.deviceName}」吗？\n其影子与历史数据将一并删除，且无法恢复。`, confirmText: '删除', danger: true }))) return;
        try { await api.del(`/api/devices/${dev.id}`); toast('设备已删除', 'success'); location.hash = '#/devices'; } catch (e) { toastError(e); }
      } }, '删除设备')));
}
