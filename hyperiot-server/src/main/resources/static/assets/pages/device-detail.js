import { api, realtime, getProducts, getThingModelByKey, productLabel } from '../api.js';
import { h, kvList, tabs, statusBadge, badge, secretText, confirmDialog, secretDialog, timeEl, fmtTime, toast, toastError } from '../ui.js';
import { propsTab, historyTab, eventsTab, servicesTab, logsTab, settingsTab } from './device-tabs.js';

export default async function mount(el, [id], ctx) {
  const dev = await api.get(`/api/devices/${id}`);
  const prods = await getProducts().catch(() => []);
  ctx.setTitle(`设备：${dev.deviceName}`);

  let tmPromise = null;
  const dd = {
    dev,
    thingModel: () => (tmPromise ||= getThingModelByKey(dev.productKey).catch((e) => { tmPromise = null; throw e; })),
    refreshHeader: () => renderStatus(),
  };

  // ----- header -----
  const statusBox = h('div');
  function renderStatus() {
    statusBox.replaceChildren(
      h('div', { class: 'row', style: { marginBottom: '14px' } }, statusBadge('device', dev.status), dev.enabled === false && badge('已禁用', 'red'),
        dev.nickname && h('span', { class: 'text-2' }, dev.nickname)),
      kvList([
        ['产品', h('a', { href: prods.find((p) => p.productKey === dev.productKey) ? `#/products/${prods.find((p) => p.productKey === dev.productKey).id}` : '#/products' }, productLabel(prods, dev.productKey))],
        ['ProductKey', h('code', {}, dev.productKey)],
        ['IP 地址', dev.ip && h('code', {}, dev.ip)],
        ['固件版本', dev.firmwareVersion],
        ['最后活跃', dev.lastSeenAt ? h('span', {}, timeEl(dev.lastSeenAt), ' ', h('span', { class: 'hint' }, fmtTime(dev.lastSeenAt))) : null],
        ['最近上线', fmtTime(dev.lastOnlineAt)],
        ['最近离线', fmtTime(dev.lastOfflineAt)],
        ['创建时间', fmtTime(dev.createdAt)],
      ], 'kv kv-2'),
      dev.tags && Object.keys(dev.tags).length ? h('div', { class: 'chips', style: { marginTop: '10px' } }, Object.entries(dev.tags).map(([k, v]) => h('span', { class: 'chip' }, `${k}: ${v}`))) : null);
  }
  renderStatus();

  const secretBox = h('div');
  function renderSecret() {
    secretBox.replaceChildren(
      h('div', { class: 'row' }, h('b', {}, 'DeviceSecret'),
        dev.deviceSecret ? secretText(() => dev.deviceSecret) : h('span', { class: 'muted' }, '不可用'),
        h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: resetSecret }, '重置密钥')),
      h('div', { class: 'hint', style: { marginTop: '4px' } }, '重置后旧密钥立即失效，设备将被踢下线并需使用新密钥重新接入。'));
  }
  async function resetSecret() {
    if (!(await confirmDialog({ title: '重置设备密钥', message: `确定重置「${dev.deviceName}」的 DeviceSecret 吗？\n旧密钥将立即失效，设备会被踢下线。`, confirmText: '重置', danger: true }))) return;
    try {
      const d = await api.post(`/api/devices/${dev.id}/reset-secret`);
      Object.assign(dev, d);
      renderSecret(); renderStatus();
      toast('密钥已重置', 'success');
      secretDialog('新的 DeviceSecret', [['DeviceName', dev.deviceName], ['DeviceSecret（请妥善保存）', dev.deviceSecret, true]]);
    } catch (e) { toastError(e); }
  }
  renderSecret();

  const t = tabs([
    { key: 'props', label: '实时属性', render: (p, tc) => propsTab(p, tc, dd) },
    { key: 'history', label: '历史曲线', render: (p, tc) => historyTab(p, tc, dd) },
    { key: 'events', label: '事件', render: (p, tc) => eventsTab(p, tc, dd) },
    { key: 'services', label: '服务调用', render: (p, tc) => servicesTab(p, tc, dd) },
    { key: 'logs', label: '设备日志', render: (p, tc) => logsTab(p, tc, dd) },
    { key: 'settings', label: '设备配置', render: (p, tc) => settingsTab(p, tc, dd) },
  ]);
  const title = h('h2', {}, dev.deviceName);
  el.append(
    h('div', { class: 'breadcrumb' }, h('a', { href: '#/devices' }, '设备'), ' / ', dev.deviceName),
    h('div', { class: 'page-head' }, h('div', { class: 'grow' }, title)),
    h('section', { class: 'layer', style: { marginBottom: '18px' } }, statusBox, h('div', { style: { borderTop: '1px dashed var(--dash)', marginTop: '14px', paddingTop: '12px' } }, secretBox)),
    t.el);
  ctx.onCleanup(() => t.destroy());

  // live status / last-seen updates
  let throttle = 0;
  ctx.onCleanup(realtime.on((m) => {
    if (Number(m.deviceId) !== Number(dev.id)) return;
    if (m.type === 'status') {
      dev.status = m.status;
      if (m.status === 'ONLINE') dev.lastOnlineAt = m.ts; else if (m.status === 'OFFLINE') dev.lastOfflineAt = m.ts;
      dev.lastSeenAt = m.ts; renderStatus();
    } else if (m.type === 'telemetry' && Date.now() - throttle > 5000) {
      throttle = Date.now(); dev.lastSeenAt = m.ts; if (dev.status !== 'ONLINE') dev.status = 'ONLINE'; renderStatus();
    }
  }));
}
