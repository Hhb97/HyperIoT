import { api, getProducts, productLabel } from '../api.js';
import { h, pageHead, select, listView, statusBadge, timeEl, debounce, badge } from '../ui.js';
import { createDeviceDialog, batchDeviceDialog } from './device-dialogs.js';

export function deviceColumns(prods, { showProduct = true } = {}) {
  return [
    { title: '设备名称', render: (d) => h('a', { href: `#/devices/${d.id}` }, d.deviceName) },
    { title: '备注名', render: (d) => d.nickname },
    showProduct && { title: '产品', render: (d) => productLabel(prods, d.productKey) },
    { title: '状态', render: (d) => h('span', {}, statusBadge('device', d.status), d.enabled === false && h('span', { style: { marginLeft: '6px' } }, badge('已禁用', 'red'))) },
    { title: '固件', render: (d) => d.firmwareVersion },
    { title: 'IP', render: (d) => d.ip && h('code', {}, d.ip) },
    { title: '最后活跃', render: (d) => timeEl(d.lastSeenAt) },
  ].filter(Boolean);
}

export default async function mount(el, _p, ctx) {
  ctx.setTitle('设备');
  const prods = await getProducts();
  const f = { productKey: '', status: '', keyword: '' };
  const lv = listView({
    empty: '没有符合条件的设备',
    fetch: (page, size) => api.get('/api/devices', { ...f, page, size }),
    columns: deviceColumns(prods),
  });
  const prodSel = select([['', '全部产品'], ...prods.map((p) => [p.productKey, p.name])], '', { 'aria-label': '按产品筛选', onchange: (e) => { f.productKey = e.target.value; lv.reset(); } });
  const statusSel = select([['', '全部状态'], ['ONLINE', '在线'], ['OFFLINE', '离线'], ['INACTIVE', '未激活']], '', { 'aria-label': '按状态筛选', onchange: (e) => { f.status = e.target.value; lv.reset(); } });
  const kw = h('input', { type: 'search', placeholder: '设备名称 / 备注名…', 'aria-label': '搜索设备', oninput: debounce((e) => { f.keyword = e.target.value.trim(); lv.reset(); }, 300) });
  const done = () => lv.reload();
  el.append(
    pageHead('设备', '管理所有接入平台的设备',
      h('button', { type: 'button', class: 'btn', onclick: () => batchDeviceDialog({ onDone: done }) }, '批量创建'),
      h('button', { type: 'button', class: 'btn btn-primary', onclick: () => createDeviceDialog({ onDone: done }) }, '+ 新建设备')),
    h('div', { class: 'toolbar' }, prodSel, statusSel, kw, h('span', { class: 'spacer' }),
      h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新')),
    lv.el);
}
