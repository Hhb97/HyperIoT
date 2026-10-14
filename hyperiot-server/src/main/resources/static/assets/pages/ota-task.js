import { api, getProducts, productLabel } from '../api.js';
import { h, kvList, table, statusBadge, progressBar, confirmDialog, emptyBox, timeEl, fmtTime, toast, toastError } from '../ui.js';
import { taskBar } from './ota.js';

export default async function mount(el, [id], ctx) {
  const prods = await getProducts().catch(() => []);
  let task = await api.get(`/api/ota/tasks/${id}`);
  ctx.setTitle(`升级任务：${task.name}`);
  const box = h('div');
  const livePill = h('span', { class: 'ws-ind', 'data-state': 'open' }, h('i'), '每 3 秒自动刷新');
  let timer = null, busy = false;

  function render() {
    const running = task.status === 'RUNNING';
    const devs = task.devices || [];
    livePill.hidden = !running;
    box.replaceChildren(
      h('div', { class: 'breadcrumb' }, h('a', { href: '#/ota?tab=tasks' }, 'OTA 升级'), ' / ', task.name),
      h('div', { class: 'page-head' },
        h('div', { class: 'grow' }, h('h2', {}, task.name, ' ', statusBadge('ota', task.status))),
        livePill,
        running && h('button', { type: 'button', class: 'btn btn-outline-danger', onclick: cancel }, '取消任务')),
      h('section', { class: 'layer', style: { marginBottom: '16px' } },
        kvList([
          ['产品', productLabel(prods, task.productKey)],
          ['目标版本', h('code', {}, task.firmwareVersion)],
          ['创建时间', fmtTime(task.createdAt)],
          ['总体进度', h('div', {}, taskBar(task), h('div', { class: 'hint' }, `成功 ${task.success ?? 0} · 失败 ${task.failed ?? 0} · 共 ${task.total ?? 0}`))],
        ], 'kv kv-2')),
      h('section', { class: 'card flush' }, devs.length ? table([
        { title: '设备', render: (d) => h('a', { href: `#/devices/${d.deviceId}` }, d.deviceName || String(d.deviceId)) },
        { title: '状态', render: (d) => statusBadge('otaDev', d.status) },
        { title: '进度', render: (d) => h('div', { style: { minWidth: '160px', display: 'flex', alignItems: 'center', gap: '8px' } }, h('div', { style: { flex: '1' } }, progressBar(d.progress, d.status === 'SUCCESS' ? 'green' : d.status === 'FAILED' ? 'red' : '')), h('span', { class: 'hint' }, `${Math.round(d.progress || 0)}%`)) },
        { title: '信息', class: 'wrap', render: (d) => d.message },
        { title: '更新时间', render: (d) => timeEl(d.updatedAt) },
      ], devs) : emptyBox('该任务没有设备明细')));
  }
  async function poll() {
    if (busy) return;
    busy = true;
    try { task = await api.get(`/api/ota/tasks/${id}`); render(); if (task.status !== 'RUNNING') stop(); } catch { /* keep trying */ } finally { busy = false; }
  }
  function stop() { clearInterval(timer); timer = null; }
  async function cancel() {
    if (!(await confirmDialog({ title: '取消升级任务', message: `确定取消任务「${task.name}」吗？未完成的设备将不再升级。`, confirmText: '取消任务', danger: true }))) return;
    try { await api.post(`/api/ota/tasks/${id}/cancel`); toast('任务已取消', 'success'); await poll(); } catch (e) { toastError(e); }
  }
  render();
  el.append(box);
  if (task.status === 'RUNNING') timer = setInterval(poll, 3000);
  ctx.onCleanup(stop);
}
