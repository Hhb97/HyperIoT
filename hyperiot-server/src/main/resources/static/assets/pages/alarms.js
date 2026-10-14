import { api, realtime } from '../api.js';
import { h, pageHead, select, listView, statusBadge, fmtTime, fmtValue, timeEl, toast, toastError } from '../ui.js';

export default function mount(el, _p, ctx) {
  ctx.setTitle('告警');
  const f = { status: '', level: '' };
  const lv = listView({
    empty: '没有符合条件的告警',
    fetch: (page, size) => api.get('/api/alarms', { ...f, page, size }),
    columns: [
      { title: '级别', render: (a) => statusBadge('level', a.level) },
      { title: '状态', render: (a) => statusBadge('alarm', a.status) },
      { title: '规则', render: (a) => a.ruleName },
      { title: '设备', render: (a) => h('a', { href: `#/devices/${a.deviceId}` }, a.deviceName || String(a.deviceId)) },
      { title: '告警信息', class: 'wrap', render: (a) => a.message },
      { title: '触发值', render: (a) => (a.triggerValue == null ? null : h('code', {}, fmtValue(a.triggerValue))) },
      { title: '触发时间', class: 'nowrap', render: (a) => h('span', { title: fmtTime(a.triggeredAt) }, timeEl(a.triggeredAt)) },
      { title: '恢复时间', class: 'nowrap', render: (a) => a.recoveredAt && timeEl(a.recoveredAt) },
      { title: '', class: 'right', render: (a) => (a.status === 'ACTIVE' ? h('button', { type: 'button', class: 'btn btn-sm btn-primary', onclick: async (e) => {
        const btn = e.currentTarget;
        btn.disabled = true;
        try { await api.post(`/api/alarms/${a.id}/ack`); toast('告警已确认', 'success'); lv.reload(); } catch (ex) { toastError(ex); btn.disabled = false; }
      } }, '确认') : a.ackedAt ? h('span', { class: 'hint', title: fmtTime(a.ackedAt) }, '已确认') : null) },
    ],
  });
  const st = select([['', '全部状态'], ['ACTIVE', '活跃'], ['ACKED', '已确认'], ['RECOVERED', '已恢复']], '', { 'aria-label': '按状态筛选', onchange: (e) => { f.status = e.target.value; lv.reset(); } });
  const lvl = select([['', '全部级别'], ['INFO', '提示'], ['WARN', '警告'], ['CRITICAL', '严重']], '', { 'aria-label': '按级别筛选', onchange: (e) => { f.level = e.target.value; lv.reset(); } });
  el.append(pageHead('告警', '由规则引擎触发；条件不再满足时自动恢复'),
    h('div', { class: 'toolbar' }, st, lvl, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新')),
    lv.el);
  let t;
  ctx.onCleanup(realtime.on((m) => { if (m.type === 'alarm') { clearTimeout(t); t = setTimeout(() => lv.reload(), 600); } }));
  ctx.onCleanup(() => clearTimeout(t));
}
