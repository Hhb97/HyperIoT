// Typed value inputs driven by a thing-model definition ({type, min, max, values, unit, name, identifier}).
import { h, options } from './ui.js';

export const NUMERIC = new Set(['int', 'long', 'float', 'double']);
export const TYPES = ['int', 'long', 'float', 'double', 'bool', 'string', 'enum'];

/** Returns {el, get(), set(v)}. get() throws Error with a user-facing message on invalid input. */
export function valueControl(def, initial) {
  const label = def.name || def.identifier || '参数';
  const type = def.type || 'string';
  let el;
  if (type === 'bool') {
    el = h('select', { 'aria-label': label }, options([['true', 'true (开)'], ['false', 'false (关)']], String(initial === true || initial === 'true')));
  } else if (type === 'enum') {
    const vals = (def.values || []).map(String);
    el = h('select', { 'aria-label': label }, options(vals.map((v) => [v, v]), initial == null ? vals[0] : String(initial)));
  } else if (NUMERIC.has(type)) {
    el = h('input', { type: 'number', 'aria-label': label, step: type === 'int' || type === 'long' ? '1' : 'any', min: def.min, max: def.max, value: initial ?? '', placeholder: def.min != null || def.max != null ? `${def.min ?? ''} ~ ${def.max ?? ''}` : '' });
  } else {
    el = h('input', { type: 'text', 'aria-label': label, value: initial ?? '' });
  }
  const get = () => {
    const raw = el.value;
    if (type === 'bool') return raw === 'true';
    if (type === 'enum' || type === 'string') {
      if (type === 'enum' && raw === '') throw new Error(`请选择「${label}」`);
      return raw;
    }
    if (raw.trim() === '') throw new Error(`请输入「${label}」`);
    const n = Number(raw);
    if (!isFinite(n)) throw new Error(`「${label}」不是有效数字`);
    if ((type === 'int' || type === 'long') && !Number.isInteger(n)) throw new Error(`「${label}」必须是整数`);
    if (def.min != null && n < def.min) throw new Error(`「${label}」不能小于 ${def.min}`);
    if (def.max != null && n > def.max) throw new Error(`「${label}」不能大于 ${def.max}`);
    return n;
  };
  return { el, get, set: (v) => { el.value = String(v ?? ''); } };
}

/** Parse free text into a JSON scalar: true/false/number/else string (used for rule condition values). */
export function parseScalar(text) {
  const t = String(text).trim();
  if (t === 'true') return true;
  if (t === 'false') return false;
  if (t !== '' && isFinite(Number(t))) return Number(t);
  return t;
}

export function typeHint(def) {
  const parts = [def.type];
  if (def.unit) parts.push(def.unit);
  if (def.min != null || def.max != null) parts.push(`${def.min ?? '-∞'} ~ ${def.max ?? '+∞'}`);
  if (def.type === 'enum' && def.values) parts.push(def.values.join(' / '));
  return parts.join(' · ');
}

/** tags editor: rows of key/value. Returns {el, get()} */
export function tagsEditor(initial = {}) {
  const wrap = h('div');
  const rows = h('div');
  const addRow = (k = '', v = '') => {
    const row = h('div', { class: 'line-row' },
      h('input', { type: 'text', class: 'tk', placeholder: '键', 'aria-label': '标签键', value: k }),
      h('input', { type: 'text', class: 'tv', placeholder: '值', 'aria-label': '标签值', value: v }),
      h('button', { type: 'button', class: 'btn btn-sm', 'aria-label': '删除标签', onclick: () => row.remove() }, '删除'));
    rows.append(row);
  };
  Object.entries(initial || {}).forEach(([k, v]) => addRow(k, String(v)));
  wrap.append(rows, h('button', { type: 'button', class: 'btn btn-sm', onclick: () => addRow() }, '+ 添加标签'));
  return {
    el: wrap,
    get() {
      const out = {};
      rows.querySelectorAll('.line-row').forEach((r) => {
        const k = r.querySelector('.tk').value.trim();
        if (k) out[k] = r.querySelector('.tv').value;
      });
      return out;
    },
  };
}
