import { api, upload, getProducts, productLabel } from '../api.js';
import { h, pageHead, field, select, tabs, listView, formModal, confirmDialog, statusBadge, copyBtn, timeEl, fmtSize, toast, toastError, debounce } from '../ui.js';

export default async function mount(el, _p, ctx) {
  ctx.setTitle('OTA 升级');
  const prods = await getProducts();
  const want = new URLSearchParams(location.hash.split('?')[1] || '').get('tab');
  const t = tabs([
    { key: 'firmware', label: '固件', render: (p) => firmwareTab(p, prods) },
    { key: 'tasks', label: '升级任务', render: (p, tc) => tasksTab(p, tc, prods) },
  ], { initial: want === 'tasks' ? 'tasks' : 'firmware' });
  el.append(pageHead('OTA 升级', '上传固件并向设备批量下发升级任务'), t.el);
  ctx.onCleanup(() => t.destroy());
}

// ---------- firmware ----------
function firmwareTab(pane, prods) {
  let productKey = '';
  const lv = listView({
    empty: '暂无固件，点击「上传固件」',
    fetch: (page, size) => api.get('/api/firmwares', { productKey, page, size }),
    columns: [
      { title: '名称', render: (f) => h('b', {}, f.name) },
      { title: '版本', render: (f) => h('code', {}, f.version) },
      { title: '产品', render: (f) => productLabel(prods, f.productKey) },
      { title: '大小', render: (f) => fmtSize(f.size) },
      { title: 'SHA-256', render: (f) => f.sha256 && h('span', { class: 'secret' }, h('code', { title: f.sha256 }, f.sha256.slice(0, 12) + '…'), copyBtn(f.sha256, '复制 SHA-256')) },
      { title: '说明', class: 'wrap', render: (f) => f.description },
      { title: '上传时间', render: (f) => timeEl(f.createdAt) },
      { title: '', class: 'right', render: (f) => h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: async () => {
        if (!(await confirmDialog({ title: '删除固件', message: `确定删除固件「${f.name} ${f.version}」吗？\n被升级任务引用的固件无法删除。`, confirmText: '删除', danger: true }))) return;
        try { await api.del(`/api/firmwares/${f.id}`); toast('固件已删除', 'success'); lv.reload(); } catch (e) { toastError(e); }
      } }, '删除') },
    ],
  });
  function uploadDialog() {
    if (!prods.length) { toast('请先创建产品', 'warn'); return; }
    const prod = select(prods.map((p) => [p.productKey, `${p.name} (${p.productKey})`]), productKey || prods[0].productKey);
    const name = h('input', { type: 'text', required: true, maxlength: 64 });
    const version = h('input', { type: 'text', required: true, maxlength: 32, placeholder: '1.0.1' });
    const desc = h('textarea', { rows: 2 });
    const file = h('input', { type: 'file', required: true });
    const bar = h('span');
    const label = h('span', { class: 'hint' });
    const prog = h('div', { class: 'upload-progress', hidden: true }, h('div', { class: 'progress' }, bar), label);
    file.addEventListener('change', () => { if (file.files[0]) { if (!name.value) name.value = file.files[0].name.replace(/\.[^.]+$/, ''); } });
    formModal({
      title: '上传固件', submitText: '上传',
      body: h('div', {}, field('所属产品', prod, { required: true }),
        h('div', { class: 'form-grid' }, field('名称', name, { required: true }), field('版本号', version, { required: true })),
        field('说明', desc), field('固件文件', file, { required: true }), prog),
      onSubmit: async () => {
        const fd = new FormData();
        fd.append('file', file.files[0]);
        fd.append('productKey', prod.value); fd.append('version', version.value.trim());
        fd.append('name', name.value.trim()); fd.append('description', desc.value.trim());
        prog.hidden = false; bar.style.width = '0%';
        try {
          await upload('/api/firmwares', fd, (r) => { bar.style.width = `${Math.round(r * 100)}%`; label.textContent = r >= 1 ? '上传完成，服务器处理中…' : `上传中 ${Math.round(r * 100)}%`; });
        } catch (e) { prog.hidden = true; throw e; }
        toast('固件已上传', 'success');
        lv.reload();
      },
    });
  }
  const sel = select([['', '全部产品'], ...prods.map((p) => [p.productKey, p.name])], '', { 'aria-label': '按产品筛选', onchange: (e) => { productKey = e.target.value; lv.reset(); } });
  pane.append(h('div', { class: 'toolbar' }, sel, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-primary', onclick: uploadDialog }, '+ 上传固件')), lv.el);
}

// ---------- tasks ----------
function tasksTab(pane, tctx, prods) {
  let anyRunning = false;
  const lv = listView({
    empty: '暂无升级任务',
    onData: (res) => { anyRunning = (res.items || []).some((x) => x.status === 'RUNNING'); },
    fetch: (page, size) => api.get('/api/ota/tasks', { page, size }),
    columns: [
      { title: '任务名称', render: (x) => h('a', { href: `#/ota/tasks/${x.id}` }, x.name) },
      { title: '目标版本', render: (x) => h('code', {}, x.firmwareVersion) },
      { title: '产品', render: (x) => productLabel(prods, x.productKey) },
      { title: '状态', render: (x) => statusBadge('ota', x.status) },
      { title: '进度', render: (x) => h('div', { style: { minWidth: '150px' } }, taskBar(x), h('div', { class: 'hint' }, `成功 ${x.success ?? 0} · 失败 ${x.failed ?? 0} · 共 ${x.total ?? 0}`)) },
      { title: '创建时间', render: (x) => timeEl(x.createdAt) },
      { title: '', class: 'right', render: (x) => h('div', { class: 'actions' },
        h('a', { class: 'btn btn-sm', href: `#/ota/tasks/${x.id}` }, '详情'),
        x.status === 'RUNNING' && h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: () => cancelTask(x) }, '取消')) },
    ],
  });
  async function cancelTask(x) {
    if (!(await confirmDialog({ title: '取消升级任务', message: `确定取消任务「${x.name}」吗？未完成的设备将不再升级。`, confirmText: '取消任务', danger: true }))) return;
    try { await api.post(`/api/ota/tasks/${x.id}/cancel`); toast('任务已取消', 'success'); lv.reload(); } catch (e) { toastError(e); }
  }
  async function createDialog() {
    let fws;
    try { fws = (await api.get('/api/firmwares', { page: 1, size: 200 })).items || []; } catch (e) { toastError(e); return; }
    if (!fws.length) { toast('请先上传固件', 'warn'); return; }
    const name = h('input', { type: 'text', required: true, maxlength: 64 });
    const fw = select(fws.map((f) => [f.id, `${f.name} v${f.version} · ${productLabel(prods, f.productKey)}`]), fws[0].id, { onchange: loadDevices });
    let scope = 'all';
    const checks = new Map();
    const list = h('div', { class: 'list-box' });
    const filter = h('input', { type: 'search', placeholder: '过滤设备…', 'aria-label': '过滤设备', oninput: debounce(() => applyFilter(), 150) });
    const pickBox = h('div', { hidden: true }, h('div', { class: 'row', style: { margin: '8px 0' } }, filter,
      h('button', { type: 'button', class: 'btn btn-sm', onclick: () => setAll(true) }, '全选'), h('button', { type: 'button', class: 'btn btn-sm', onclick: () => setAll(false) }, '全不选')), list);
    const radio = (val, text) => h('label', { class: 'check', style: { marginRight: '18px' } }, h('input', { type: 'radio', name: 'scope', value: val, checked: val === scope, onchange: () => { scope = val; pickBox.hidden = scope === 'all'; if (scope === 'pick') loadDevices(); } }), text);
    function applyFilter() {
      const q = filter.value.trim().toLowerCase();
      for (const [, c] of checks) c.row.hidden = !!q && !c.text.includes(q);
    }
    function setAll(v) { for (const [, c] of checks) if (!c.row.hidden) c.input.checked = v; }
    async function loadDevices() {
      if (scope !== 'pick') return;
      const f = fws.find((x) => String(x.id) === String(fw.value));
      list.replaceChildren(h('span', { class: 'hint' }, '加载中…')); checks.clear();
      try {
        const res = await api.get('/api/devices', { productKey: f.productKey, page: 1, size: 200 });
        const items = res.items || [];
        list.replaceChildren(...(items.length ? items.map((d) => {
          const input = h('input', { type: 'checkbox', value: d.id });
          const row = h('label', { class: 'check' }, input, `${d.deviceName}${d.nickname ? ' · ' + d.nickname : ''}`);
          checks.set(d.id, { input, row, text: `${d.deviceName} ${d.nickname || ''}`.toLowerCase() });
          return row;
        }) : [h('span', { class: 'hint' }, '该产品下暂无设备')]));
        if (res.total > items.length) list.append(h('div', { class: 'hint' }, `仅显示前 ${items.length} 台（共 ${res.total} 台）；如需全部请选择「全部设备」`));
      } catch (e) { list.replaceChildren(h('span', { class: 'hint' }, e.message)); }
    }
    formModal({
      title: '创建升级任务', submitText: '创建并开始', size: 'wide',
      body: h('div', {}, field('任务名称', name, { required: true }), field('固件', fw, { required: true }),
        h('div', { class: 'field' }, h('span', { class: 'field-label' }, '升级范围'), h('div', {}, radio('all', '该产品下的全部设备'), radio('pick', '指定设备')), pickBox)),
      onSubmit: async () => {
        let deviceIds = [];
        if (scope === 'pick') {
          deviceIds = [...checks.values()].filter((c) => c.input.checked).map((c) => Number(c.input.value));
          if (!deviceIds.length) throw new Error('请至少选择一台设备，或选择「全部设备」');
        }
        const task = await api.post('/api/ota/tasks', { name: name.value.trim(), firmwareId: Number(fw.value), deviceIds });
        toast('升级任务已创建', 'success');
        if (task?.id != null) location.hash = `#/ota/tasks/${task.id}`; else lv.reload();
      },
    });
  }
  pane.append(h('div', { class: 'toolbar' }, h('span', { class: 'spacer' }),
    h('button', { type: 'button', class: 'btn btn-sm', onclick: () => lv.reload() }, '刷新'),
    h('button', { type: 'button', class: 'btn btn-primary', onclick: createDialog }, '+ 创建任务')), lv.el);
  tctx.interval(() => { if (anyRunning) lv.reload(); }, 5000);
}

/** stacked success / failed / remaining bar */
export function taskBar(x) {
  const total = Math.max(x.total || 0, 1);
  const ok = ((x.success || 0) / total) * 100, bad = ((x.failed || 0) / total) * 100;
  return h('div', { class: 'progress multi', role: 'progressbar', 'aria-valuenow': Math.round(ok + bad), 'aria-valuemin': 0, 'aria-valuemax': 100, title: `成功 ${x.success || 0} / 失败 ${x.failed || 0} / 共 ${x.total || 0}` },
    h('span', { style: { width: ok + '%', background: 'var(--green)' } }), h('span', { style: { width: bad + '%', background: 'var(--red)' } }));
}
