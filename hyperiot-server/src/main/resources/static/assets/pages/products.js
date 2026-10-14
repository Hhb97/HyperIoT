import { api, invalidateProducts } from '../api.js';
import { h, pageHead, field, select, formModal, confirmDialog, listView, copyBtn, timeEl, toast, toastError, debounce, LABELS, badge } from '../ui.js';

export const NODE_OPTS = [['DEVICE', '直连设备 (DEVICE)'], ['GATEWAY', '网关 (GATEWAY)']];
export const PROTO_OPTS = [['MQTT', 'MQTT'], ['HTTP', 'HTTP'], ['TCP', 'TCP']];

export default function mount(el, _p, ctx) {
  ctx.setTitle('产品与物模型');
  let keyword = '';
  const lv = listView({
    empty: '还没有产品，点击右上角「新建产品」开始',
    fetch: (page, size) => api.get('/api/products', { keyword, page, size }),
    columns: [
      { title: '产品名称', render: (p) => h('a', { href: `#/products/${p.id}` }, p.name) },
      { title: 'ProductKey', render: (p) => h('span', { class: 'secret' }, h('code', {}, p.productKey), copyBtn(p.productKey)) },
      { title: '节点类型', render: (p) => LABELS.nodeType[p.nodeType] || p.nodeType },
      { title: '协议', render: (p) => badge(p.protocol, 'blue') },
      { title: '设备数', render: (p) => h('a', { href: '#/devices' }, String(p.deviceCount ?? 0)) },
      { title: '创建时间', render: (p) => timeEl(p.createdAt) },
      { title: '', class: 'right', render: (p) => h('div', { class: 'actions' },
        h('a', { class: 'btn btn-sm', href: `#/products/${p.id}` }, '详情'),
        h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: () => del(p) }, '删除')) },
    ],
  });
  async function del(p) {
    if (!(await confirmDialog({ title: '删除产品', message: `确定删除产品「${p.name}」吗？\n仍有设备的产品无法删除。`, confirmText: '删除', danger: true }))) return;
    try { await api.del(`/api/products/${p.id}`); invalidateProducts(); toast('产品已删除', 'success'); lv.reload(); } catch (e) { toastError(e); }
  }
  function create() {
    const name = h('input', { type: 'text', required: true, maxlength: 64 });
    const desc = h('textarea', { rows: 2, maxlength: 255 });
    const node = select(NODE_OPTS, 'DEVICE');
    const proto = select(PROTO_OPTS, 'MQTT');
    formModal({
      title: '新建产品', submitText: '创建',
      body: h('div', {}, field('产品名称', name, { required: true }), field('描述', desc),
        h('div', { class: 'form-grid' }, field('节点类型', node), field('接入协议', proto))),
      onSubmit: async () => {
        const p = await api.post('/api/products', { name: name.value.trim(), description: desc.value.trim(), nodeType: node.value, protocol: proto.value });
        invalidateProducts(); toast('产品已创建', 'success');
        location.hash = `#/products/${p.id}`;
      },
    });
  }
  const search = h('input', { type: 'search', placeholder: '搜索产品名称…', 'aria-label': '搜索产品', oninput: debounce((e) => { keyword = e.target.value.trim(); lv.reset(); }, 300) });
  el.append(
    pageHead('产品', '产品是一类设备的模板：定义物模型、协议与节点类型', h('button', { type: 'button', class: 'btn btn-primary', onclick: create }, '+ 新建产品')),
    h('div', { class: 'toolbar' }, search),
    lv.el);
}
