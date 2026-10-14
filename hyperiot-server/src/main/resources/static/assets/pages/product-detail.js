import { api, getProducts, invalidateProducts } from '../api.js';
import { h, pageHead, field, kvList, tabs, listView, confirmDialog, copyBtn, fmtTime, toast, toastError, LABELS, badge } from '../ui.js';
import { thingModelEditor, emptyModel } from '../thing-model-editor.js';
import { deviceColumns } from './devices.js';
import { createDeviceDialog, batchDeviceDialog } from './device-dialogs.js';

export default async function mount(el, [id], ctx) {
  const prod = await api.get(`/api/products/${id}`);
  ctx.setTitle(`产品：${prod.name}`);
  const state = { tm: emptyModel(), loaded: false, raw: null };

  async function ensureTm() {
    if (state.loaded) return;
    const tm = await api.get(`/api/products/${id}/thing-model`);
    state.tm = { ...emptyModel(), ...(tm || {}) };
    state.loaded = true;
  }

  async function saveTm(tm, errBox) {
    errBox.hidden = true;
    try {
      await api.put(`/api/products/${id}/thing-model`, tm);
      state.tm = tm; state.raw = null; state.loaded = true;
      toast('物模型已保存', 'success');
    } catch (e) {
      errBox.textContent = e.message; errBox.hidden = false;
      errBox.scrollIntoView({ block: 'nearest' });
      toastError(e);
    }
  }

  // ----- tabs -----
  const infoTab = (pane) => {
    const name = h('input', { type: 'text', required: true, value: prod.name, maxlength: 64 });
    const desc = h('textarea', { rows: 3, value: prod.description || '' });
    const form = h('form', {
      onsubmit: async (e) => {
        e.preventDefault();
        try {
          const p = await api.put(`/api/products/${id}`, { name: name.value.trim(), description: desc.value.trim() });
          Object.assign(prod, p || { name: name.value.trim(), description: desc.value.trim() });
          invalidateProducts(); title.textContent = prod.name; ctx.setTitle(`产品：${prod.name}`);
          toast('已保存', 'success');
        } catch (ex) { toastError(ex); }
      },
    }, field('产品名称', name, { required: true }), field('描述', desc), h('button', { type: 'submit', class: 'btn btn-primary' }, '保存修改'));
    pane.append(h('div', { class: 'grid-2' },
      h('section', { class: 'card' }, h('h3', {}, '基本信息'), kvList([
        ['ProductKey', h('span', { class: 'secret' }, h('code', {}, prod.productKey), copyBtn(prod.productKey, '复制 ProductKey'))],
        ['节点类型', LABELS.nodeType[prod.nodeType] || prod.nodeType],
        ['接入协议', badge(prod.protocol, 'blue')],
        ['设备数量', String(prod.deviceCount ?? 0)],
        ['创建时间', fmtTime(prod.createdAt)],
        ['更新时间', fmtTime(prod.updatedAt)],
      ])),
      h('section', { class: 'card' }, h('h3', {}, '编辑'), form)),
    h('div', { class: 'danger-zone', style: { marginTop: '16px' } },
      h('div', { class: 'grow' }, h('b', {}, '删除产品'), h('div', { class: 'hint' }, '仍有设备的产品无法删除。')),
      h('button', { type: 'button', class: 'btn btn-outline-danger', onclick: async () => {
        if (!(await confirmDialog({ title: '删除产品', message: `确定删除产品「${prod.name}」吗？此操作不可恢复。`, confirmText: '删除', danger: true }))) return;
        try { await api.del(`/api/products/${id}`); invalidateProducts(); toast('产品已删除', 'success'); location.hash = '#/products'; } catch (e) { toastError(e); }
      } }, '删除产品')));
  };

  let editor = null;
  const tmTab = async (pane) => {
    await ensureTm();
    editor = thingModelEditor(state.tm);
    const err = h('div', { class: 'form-error', role: 'alert', hidden: true, style: { margin: '12px 0 0' } });
    pane.append(
      h('div', { class: 'toolbar' }, h('span', { class: 'hint' }, '定义产品的属性（上报数据）、事件和可调用的服务。'), h('span', { class: 'spacer' }),
        h('button', { type: 'button', class: 'btn btn-primary', onclick: async () => {
          let tm;
          try { tm = editor.get(); } catch (e) { err.textContent = e.message; err.hidden = false; return; }
          await saveTm(tm, err);
        } }, '保存物模型')),
      editor.el, err);
  };
  let rawArea = null;
  const jsonTab = async (pane) => {
    await ensureTm();
    rawArea = h('textarea', { class: 'code', rows: 24, spellcheck: 'false', 'aria-label': '物模型 JSON', value: state.raw ?? JSON.stringify(state.tm, null, 2) });
    const err = h('div', { class: 'form-error', role: 'alert', hidden: true, style: { margin: '12px 0 0' } });
    pane.append(
      h('div', { class: 'toolbar' }, h('span', { class: 'hint' }, '直接编辑 ThingModel JSON；保存时由服务端校验。'), h('span', { class: 'spacer' }),
        h('button', { type: 'button', class: 'btn', onclick: () => { try { rawArea.value = JSON.stringify(JSON.parse(rawArea.value), null, 2); err.hidden = true; } catch (e) { err.textContent = 'JSON 格式错误：' + e.message; err.hidden = false; } } }, '格式化'),
        h('button', { type: 'button', class: 'btn btn-primary', onclick: async () => {
          let tm;
          try { tm = JSON.parse(rawArea.value); } catch (e) { err.textContent = 'JSON 格式错误：' + e.message; err.hidden = false; return; }
          await saveTm(tm, err);
          if (err.hidden) rawArea.value = JSON.stringify(tm, null, 2);
        } }, '保存物模型')),
      rawArea, err);
  };
  const devTab = (pane) => {
    const lv = listView({ empty: '该产品下暂无设备', fetch: (page, size) => api.get('/api/devices', { productKey: prod.productKey, page, size }), columns: deviceColumns([], { showProduct: false }) });
    pane.append(
      h('div', { class: 'toolbar' }, h('span', { class: 'spacer' }),
        h('button', { type: 'button', class: 'btn', onclick: () => batchDeviceDialog({ productKey: prod.productKey, onDone: () => lv.reload() }) }, '批量创建'),
        h('button', { type: 'button', class: 'btn btn-primary', onclick: () => createDeviceDialog({ productKey: prod.productKey, onDone: () => lv.reload() }) }, '+ 新建设备')),
      lv.el);
  };

  const t = tabs([
    { key: 'info', label: '基本信息', render: infoTab },
    {
      key: 'tm', label: '物模型', render: tmTab,
      canLeave: async () => {
        try { state.tm = editor.get(); state.raw = null; return true; } catch (e) {
          return confirmDialog({ title: '物模型有未完成的内容', message: `${e.message}\n继续离开将丢弃本次未保存的编辑。`, confirmText: '放弃并离开', danger: true });
        }
      },
    },
    {
      key: 'json', label: '物模型 JSON', render: jsonTab,
      canLeave: async () => {
        try { state.tm = { ...emptyModel(), ...JSON.parse(rawArea.value) }; state.raw = null; return true; } catch (e) {
          state.raw = rawArea.value;
          return confirmDialog({ title: 'JSON 格式错误', message: `${e.message}\n继续离开将保留未保存的文本，但不会应用到结构化编辑器。`, confirmText: '仍然离开', danger: true });
        }
      },
    },
    { key: 'devices', label: '设备列表', render: devTab },
  ]);
  const title = h('h2', {}, prod.name);
  el.append(
    h('div', { class: 'breadcrumb' }, h('a', { href: '#/products' }, '产品'), ' / ', prod.name),
    h('div', { class: 'page-head' }, h('div', { class: 'grow' }, title, h('div', { class: 'sub' }, h('code', {}, prod.productKey), ' ', badge(prod.protocol, 'blue'), ' ', badge(LABELS.nodeType[prod.nodeType] || prod.nodeType)))),
    t.el);
  ctx.onCleanup(() => t.destroy());
  getProducts().catch(() => {});
}
