import { api, realtime } from '../api.js';
import { h, pageHead, select, listView, statusBadge, fmtTime, timeEl, compactJson } from '../ui.js';

export function commandColumns(showDevice = true) {
  return [
    { title: '创建时间', class: 'nowrap', render: (c) => h('span', { title: fmtTime(c.createdAt) }, fmtTime(c.createdAt)) },
    showDevice && { title: '设备', render: (c) => h('a', { href: `#/devices/${c.deviceId}` }, c.deviceName || String(c.deviceId)) },
    { title: '服务', render: (c) => h('code', {}, c.identifier) },
    { title: '参数', class: 'wrap', render: (c) => c.params != null && h('code', {}, compactJson(c.params, 160)) },
    { title: '状态', render: (c) => statusBadge('command', c.status) },
    { title: '应答', class: 'wrap', render: (c) => c.response != null && c.response !== '' && h('code', {}, compactJson(c.response, 160)) },
    { title: '应答时间', render: (c) => timeEl(c.repliedAt) },
    showDevice && { title: 'requestId', render: (c) => c.requestId && h('code', { class: 'muted', title: c.requestId }, String(c.requestId).slice(0, 8)) },
  ].filter(Boolean);
}

export default function mount(el, _p, ctx) {
  ctx.setTitle('指令');
  let status = '';
  const lv = listView({
    empty: '暂无指令记录',
    fetch: (page, size) => api.get('/api/commands', { status, page, size }),
    columns: commandColumns(true),
  });
  const sel = select([['', '全部状态'], ['PENDING', '待下发'], ['SENT', '已下发'], ['SUCCESS', '成功'], ['FAILED', '失败'], ['TIMEOUT', '超时']], '', { 'aria-label': '按状态筛选', onchange: (e) => { status = e.target.value; lv.reset(); } });
  el.append(pageHead('指令', '下发到设备的服务调用记录（在「设备 → 服务调用」中发起）'),
    h('div', { class: 'toolbar' }, sel, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新')),
    lv.el);
  let t;
  ctx.onCleanup(realtime.on((m) => { if (m.type === 'command') { clearTimeout(t); t = setTimeout(() => lv.reload(), 600); } }));
  ctx.onCleanup(() => clearTimeout(t));
}
