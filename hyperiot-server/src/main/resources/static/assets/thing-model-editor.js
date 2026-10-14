// Structured thing-model editor (properties / events / services). Edits a deep copy; get() returns a cleaned ThingModel.
import { h, field, select } from './ui.js';
import { NUMERIC, TYPES } from './forms.js';

const LEVELS = ['INFO', 'WARN', 'CRITICAL'];
const ACCESS = [['r', 'r 只读'], ['rw', 'rw 可写']];
const clone = (v) => JSON.parse(JSON.stringify(v));

export function emptyModel() { return { properties: [], events: [], services: [] }; }

function textField(label, obj, key, { number = false, required = false, placeholder = '', list } = {}) {
  const input = h('input', {
    type: number ? 'number' : 'text', step: number ? 'any' : null, placeholder, required, list,
    value: obj[key] == null ? '' : String(obj[key]),
    oninput: (e) => {
      const v = e.target.value;
      if (v === '') delete obj[key]; else obj[key] = number ? Number(v) : v;
    },
  });
  return field(label, input, { required });
}
function selectField(label, obj, key, opts, onchange) {
  const sel = select(opts, obj[key] ?? (Array.isArray(opts[0]) ? opts[0][0] : opts[0]), {
    onchange: (e) => { obj[key] = e.target.value; onchange?.(); },
  });
  if (obj[key] == null) obj[key] = sel.value;
  return field(label, sel);
}
function valuesField(obj) {
  return field('枚举值（逗号分隔）', h('input', {
    type: 'text', placeholder: 'auto, manual', value: (obj.values || []).join(', '),
    oninput: (e) => { obj.values = e.target.value.split(',').map((x) => x.trim()).filter(Boolean); },
  }));
}

/** Compact list of params: [{identifier,type,min,max,values}] */
function paramList(owner, key, rerender, title) {
  if (!Array.isArray(owner[key])) owner[key] = [];
  const arr = owner[key];
  const box = h('div', { class: 'sub-rows' }, h('div', { class: 'hint', style: { marginBottom: '6px' } }, title));
  arr.forEach((p, i) => {
    box.append(h('div', { class: 'item-card', style: { background: 'var(--surface)' } },
      h('div', { class: 'item-grid' },
        textField('标识符', p, 'identifier', { required: true }),
        textField('名称', p, 'name'),
        selectField('类型', p, 'type', TYPES, rerender),
        NUMERIC.has(p.type) && textField('最小值', p, 'min', { number: true }),
        NUMERIC.has(p.type) && textField('最大值', p, 'max', { number: true }),
        p.type === 'enum' && valuesField(p),
        h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: () => { arr.splice(i, 1); rerender(); } }, '删除'))));
  });
  box.append(h('button', { type: 'button', class: 'btn btn-sm', onclick: () => { arr.push({ identifier: '', type: 'string' }); rerender(); } }, '+ 添加参数'));
  return box;
}

export function thingModelEditor(initial) {
  const model = clone({ ...emptyModel(), ...(initial || {}) });
  for (const k of ['properties', 'events', 'services']) if (!Array.isArray(model[k])) model[k] = [];
  const root = h('div');

  function head(title, onAdd) {
    return h('div', { class: 'card-title', style: { marginTop: '4px' } }, title, h('span', { class: 'spacer' }), h('button', { type: 'button', class: 'btn btn-sm', onclick: onAdd }, '+ 添加'));
  }
  function itemCard(label, i, arr, body) {
    return h('div', { class: 'item-card' },
      h('div', { class: 'item-head' }, h('span', {}, `${label} #${i + 1}`),
        h('button', { type: 'button', class: 'btn btn-sm btn-outline-danger', onclick: () => { arr.splice(i, 1); render(); } }, '删除')),
      body);
  }
  function render() {
    const props = model.properties.map((p, i) => itemCard('属性', i, model.properties, h('div', { class: 'item-grid' },
      textField('标识符', p, 'identifier', { required: true, placeholder: 'temperature' }),
      textField('名称', p, 'name', { placeholder: '温度' }),
      selectField('类型', p, 'type', TYPES, render),
      selectField('读写', p, 'access', ACCESS),
      textField('单位', p, 'unit', { placeholder: '℃' }),
      NUMERIC.has(p.type) && textField('最小值', p, 'min', { number: true }),
      NUMERIC.has(p.type) && textField('最大值', p, 'max', { number: true }),
      p.type === 'enum' && valuesField(p))));
    const events = model.events.map((ev, i) => itemCard('事件', i, model.events, h('div', {},
      h('div', { class: 'item-grid' },
        textField('标识符', ev, 'identifier', { required: true, placeholder: 'overheat' }),
        textField('名称', ev, 'name'),
        selectField('级别', ev, 'level', LEVELS)),
      paramList(ev, 'params', render, '事件参数'))));
    const services = model.services.map((sv, i) => itemCard('服务', i, model.services, h('div', {},
      h('div', { class: 'item-grid' },
        textField('标识符', sv, 'identifier', { required: true, placeholder: 'reboot' }),
        textField('名称', sv, 'name')),
      paramList(sv, 'input', render, '输入参数'),
      paramList(sv, 'output', render, '输出参数'))));
    const empty = (t) => h('div', { class: 'hint', style: { margin: '4px 0 12px' } }, t);
    root.replaceChildren(
      head('属性 (properties)', () => { model.properties.push({ identifier: '', name: '', type: 'double', access: 'r' }); render(); }),
      ...(props.length ? props : [empty('暂无属性')]),
      head('事件 (events)', () => { model.events.push({ identifier: '', name: '', level: 'INFO', params: [] }); render(); }),
      ...(events.length ? events : [empty('暂无事件')]),
      head('服务 (services)', () => { model.services.push({ identifier: '', name: '', input: [], output: [] }); render(); }),
      ...(services.length ? services : [empty('暂无服务')]));
  }
  render();

  function cleanParam(p) {
    const o = { ...p };
    if (!NUMERIC.has(o.type)) { delete o.min; delete o.max; }
    if (o.type !== 'enum') delete o.values;
    return o;
  }
  return {
    el: root,
    get() {
      const out = clone(model);
      const seen = (arr, label) => {
        const ids = new Set();
        arr.forEach((x, i) => {
          if (!x.identifier || !x.identifier.trim()) throw new Error(`${label} #${i + 1} 的标识符不能为空`);
          x.identifier = x.identifier.trim();
          if (ids.has(x.identifier)) throw new Error(`${label}标识符重复：${x.identifier}`);
          ids.add(x.identifier);
        });
      };
      seen(out.properties, '属性'); seen(out.events, '事件'); seen(out.services, '服务');
      out.properties = out.properties.map((p) => { const o = cleanParam(p); if (!o.access) o.access = 'r'; return o; });
      for (const ev of out.events) { ev.params = (ev.params || []).map(cleanParam); seen(ev.params, `事件 ${ev.identifier} 的参数`); }
      for (const sv of out.services) {
        sv.input = (sv.input || []).map(cleanParam); sv.output = (sv.output || []).map(cleanParam);
        seen(sv.input, `服务 ${sv.identifier} 的输入参数`); seen(sv.output, `服务 ${sv.identifier} 的输出参数`);
      }
      return out;
    },
  };
}
