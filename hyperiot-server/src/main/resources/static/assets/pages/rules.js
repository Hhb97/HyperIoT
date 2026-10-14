import { api, getProducts, getThingModelByKey, productLabel } from '../api.js';
import { h, pageHead, field, select, formModal, confirmDialog, listView, statusBadge, switchEl, toast, toastError, fmtValue, LABELS, uid } from '../ui.js';
import { parseScalar } from '../forms.js';

const OPS = Object.entries(LABELS.op).map(([k, v]) => [k, `${v}  ${k}`]);
const LEVELS = [['INFO', '提示 INFO'], ['WARN', '警告 WARN'], ['CRITICAL', '严重 CRITICAL']];

const condText = (r) => (r.conditions || []).map((c) => `${c.identifier} ${LABELS.op[c.op] || c.op} ${fmtValue(c.value)}`).join(r.logic === 'OR' ? '  或  ' : '  且  ');
const actText = (r) => (r.actions || []).map((a) => (a.type === 'COMMAND' ? `指令 ${a.identifier}` : `Webhook`)).join('、');

export default async function mount(el, _p, ctx) {
  ctx.setTitle('规则引擎');
  const prods = await getProducts();
  const lv = listView({
    empty: '还没有规则，点击右上角「新建规则」',
    fetch: (page, size) => api.get('/api/rules', { page, size }),
    columns: [
      { title: '规则名称', render: (r) => h('div', {}, h('b', {}, r.name), r.description && h('div', { class: 'hint' }, r.description)) },
      { title: '产品 / 设备', render: (r) => h('div', {}, productLabel(prods, r.productKey), h('div', { class: 'hint' }, r.deviceName ? `设备 ${r.deviceName}` : '该产品下全部设备')) },
      { title: '条件', class: 'wrap', render: (r) => h('code', {}, condText(r)) },
      { title: '动作', class: 'wrap', render: (r) => actText(r) || '仅告警' },
      { title: '级别', render: (r) => statusBadge('level', r.level) },
      { title: '启用', render: (r) => switchEl({ checked: !!r.enabled, label: `启用规则 ${r.name}`, onChange: async (c, input) => {
        try { await api.post(`/api/rules/${r.id}/${c ? 'enable' : 'disable'}`); r.enabled = c; toast(c ? '规则已启用' : '规则已停用', 'success'); } catch (e) { input.checked = !c; toastError(e); }
      } }) },
      { title: '', class: 'right', render: (r) => h('div', { class: 'actions' },
        h('button', { type: 'button', class: 'btn btn-sm', onclick: () => edit(r) }, '编辑'),
        h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: () => del(r) }, '删除')) },
    ],
  });
  async function del(r) {
    if (!(await confirmDialog({ title: '删除规则', message: `确定删除规则「${r.name}」吗？`, confirmText: '删除', danger: true }))) return;
    try { await api.del(`/api/rules/${r.id}`); toast('规则已删除', 'success'); lv.reload(); } catch (e) { toastError(e); }
  }
  async function edit(r) {
    try { openDialog(r ? await api.get(`/api/rules/${r.id}`) : null); } catch (e) { toastError(e); }
  }

  function openDialog(rule) {
    if (!prods.length) { toast('请先创建产品', 'warn'); return; }
    const isEdit = !!rule;
    const name = h('input', { type: 'text', required: true, maxlength: 64, value: rule?.name || '' });
    const desc = h('input', { type: 'text', maxlength: 255, value: rule?.description || '' });
    const prod = select(prods.map((p) => [p.productKey, `${p.name} (${p.productKey})`]), rule?.productKey || prods[0].productKey, { onchange: loadModel });
    const idList = uid('ids'), svcList = uid('svc'), devList = uid('devs');
    const dl = h('datalist', { id: idList }), sl = h('datalist', { id: svcList }), ddl = h('datalist', { id: devList });
    const dev = h('input', { type: 'text', list: devList, placeholder: '留空 = 该产品下全部设备', value: rule?.deviceName || '', autocomplete: 'off' });
    const logic = select([['AND', LABELS.logic.AND], ['OR', LABELS.logic.OR]], rule?.logic || 'AND');
    const level = select(LEVELS, rule?.level || 'WARN');
    let enabled = rule ? !!rule.enabled : true;
    const sw = switchEl({ checked: enabled, label: '启用规则', onChange: (c) => { enabled = c; } });

    async function loadModel() {
      try {
        const tm = await getThingModelByKey(prod.value);
        dl.replaceChildren(...tm.properties.map((p) => h('option', { value: p.identifier }, p.name || '')));
        sl.replaceChildren(...tm.services.map((s) => h('option', { value: s.identifier }, s.name || '')));
        const res = await api.get('/api/devices', { productKey: prod.value, page: 1, size: 200 });
        ddl.replaceChildren(...(res.items || []).map((d) => h('option', { value: d.deviceName }, d.nickname || '')));
      } catch { /* suggestions are optional */ }
    }

    // ----- conditions -----
    const condBox = h('div');
    const conds = [];
    function addCond(c = {}) {
      const idI = h('input', { type: 'text', list: idList, placeholder: '属性标识符', 'aria-label': '属性标识符', required: true, value: c.identifier || '', autocomplete: 'off' });
      const op = select(OPS, c.op || 'GT', { 'aria-label': '比较符' });
      const val = h('input', { type: 'text', placeholder: '值（数值 / true / 文本）', 'aria-label': '比较值', required: true, value: c.value === undefined ? '' : String(c.value) });
      const row = h('div', { class: 'line-row' }, h('div', { class: 'grow' }, idI), op, h('div', { class: 'grow' }, val),
        h('button', { type: 'button', class: 'btn btn-sm', 'aria-label': '删除条件', onclick: () => { row.remove(); } }, '删除'));
      row.get = () => ({ identifier: idI.value.trim(), op: op.value, value: parseScalar(val.value) });
      conds.push(row); condBox.append(row);
    }
    // ----- actions -----
    const actBox = h('div');
    const acts = [];
    function addAct(a = { type: 'COMMAND' }) {
      const type = select([['COMMAND', '下发指令'], ['WEBHOOK', 'Webhook']], a.type, { 'aria-label': '动作类型', onchange: build });
      const body = h('div', { class: 'grow row', style: { flexWrap: 'nowrap' } });
      const row = h('div', { class: 'line-row' }, type, body, h('button', { type: 'button', class: 'btn btn-sm', 'aria-label': '删除动作', onclick: () => row.remove() }, '删除'));
      let getter;
      function build() {
        if (type.value === 'WEBHOOK') {
          const url = h('input', { type: 'url', placeholder: 'https://example.com/hook', 'aria-label': 'Webhook URL', required: true, value: a.url || '' });
          body.replaceChildren(url);
          getter = () => ({ type: 'WEBHOOK', url: url.value.trim() });
        } else {
          const idI = h('input', { type: 'text', list: svcList, placeholder: '服务标识符', 'aria-label': '服务标识符', required: true, value: a.identifier || '', autocomplete: 'off' });
          const params = h('input', { type: 'text', class: 'mono', placeholder: '参数 JSON，如 {"mode":"auto"}', 'aria-label': '指令参数 JSON', value: a.params && Object.keys(a.params).length ? JSON.stringify(a.params) : '' });
          body.replaceChildren(h('div', { style: { flex: '1' } }, idI), h('div', { style: { flex: '1.4' } }, params));
          getter = () => {
            let p = {};
            if (params.value.trim()) { try { p = JSON.parse(params.value); } catch { throw new Error('指令参数不是合法的 JSON'); } if (p === null || typeof p !== 'object' || Array.isArray(p)) throw new Error('指令参数必须是 JSON 对象'); }
            return { type: 'COMMAND', identifier: idI.value.trim(), params: p };
          };
        }
      }
      build();
      row.get = () => getter();
      acts.push(row); actBox.append(row);
    }
    (rule?.conditions?.length ? rule.conditions : [{}]).forEach(addCond);
    (rule?.actions || []).forEach(addAct);
    loadModel();

    formModal({
      title: isEdit ? '编辑规则' : '新建规则', submitText: isEdit ? '保存' : '创建', size: 'wide',
      body: h('div', {},
        h('div', { class: 'form-grid' }, field('规则名称', name, { required: true }), field('描述', desc),
          field('所属产品', prod, { required: true }), field('指定设备（可选）', dev, { hint: '填写 DeviceName，留空则适用于产品下全部设备' })),
        dl, sl, ddl,
        h('div', { class: 'field' }, h('span', { class: 'field-label' }, '触发条件'),
          h('div', { class: 'row', style: { marginBottom: '8px' } }, h('span', { class: 'hint' }, '多个条件之间：'), logic), condBox,
          h('button', { type: 'button', class: 'btn btn-sm', onclick: () => addCond() }, '+ 添加条件')),
        h('div', { class: 'field' }, h('span', { class: 'field-label' }, '触发动作（可选）'), actBox,
          h('button', { type: 'button', class: 'btn btn-sm', onclick: () => addAct() }, '+ 添加动作')),
        h('div', { class: 'form-grid' }, field('告警级别', level), h('div', { class: 'field' }, h('span', { class: 'field-label' }, '状态'), sw))),
      onSubmit: async () => {
        const conditions = conds.filter((r) => r.isConnected).map((r) => r.get());
        if (!conditions.length) throw new Error('至少需要一个触发条件');
        for (const c of conditions) if (c.value === '') throw new Error('条件的比较值不能为空');
        const actions = acts.filter((r) => r.isConnected).map((r) => r.get());
        for (const a of actions) if (a.type === 'WEBHOOK' && !/^https?:\/\//i.test(a.url)) throw new Error('Webhook URL 必须以 http:// 或 https:// 开头');
        const body = { name: name.value.trim(), description: desc.value.trim(), productKey: prod.value, deviceName: dev.value.trim() || null, logic: logic.value, conditions, actions, level: level.value, enabled };
        if (isEdit) await api.put(`/api/rules/${rule.id}`, body); else await api.post('/api/rules', body);
        toast(isEdit ? '规则已保存' : '规则已创建', 'success');
        lv.reload();
      },
    });
  }

  el.append(pageHead('规则引擎', '当设备上报满足条件时创建告警，并可联动下发指令或调用 Webhook', h('button', { type: 'button', class: 'btn btn-primary', onclick: () => edit(null) }, '+ 新建规则')), lv.el);
}
