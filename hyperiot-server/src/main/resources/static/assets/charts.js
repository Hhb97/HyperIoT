// Hand-written SVG charts (no libraries). Colours come from CSS classes / variables so dark mode just works.
import { h, s, fmtTime, fmtValue } from './ui.js';

function niceNum(range, round) {
  const exp = Math.floor(Math.log10(range));
  const f = range / 10 ** exp;
  let nf;
  if (round) nf = f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10;
  else nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nf * 10 ** exp;
}
export function niceTicks(min, max, n = 5) {
  if (!isFinite(min) || !isFinite(max)) { min = 0; max = 1; }
  if (min === max) { const d = Math.abs(min) * 0.1 || 1; min -= d; max += d; }
  const step = niceNum(niceNum(max - min, false) / (n - 1), true);
  const lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
  const dec = Math.max(0, -Math.floor(Math.log10(step)));
  const ticks = [];
  for (let v = lo; v <= hi + step / 2; v += step) ticks.push(Number(v.toFixed(Math.min(dec + 1, 10))));
  return { ticks, lo, hi, dec };
}
const fmtTick = (v, dec) => (Math.abs(v) >= 1e6 ? (v / 1e6).toFixed(1) + 'M' : Math.abs(v) >= 1e4 ? (v / 1e3).toFixed(0) + 'k' : v.toFixed(dec));

const pad2 = (n) => String(n).padStart(2, '0');
const STEPS = [1, 2, 5, 10, 15, 30].map((m) => m * 60000).concat([1, 2, 3, 6, 12].map((x) => x * 3600000), [1, 2, 7].map((d) => d * 86400000));
function timeTicks(min, max, maxCount = 6) {
  const span = max - min;
  const step = STEPS.find((st) => span / st <= maxCount) || STEPS[STEPS.length - 1];
  const off = new Date(min).getTimezoneOffset() * 60000;
  const out = [];
  for (let t = Math.ceil((min - off) / step) * step + off; t <= max; t += step) out.push(t);
  return { ticks: out, withDate: span > 36 * 3600000 };
}
function fmtAxisTime(ts, withDate) {
  const d = new Date(ts);
  const hm = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  return withDate ? `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${hm}` : hm;
}

function mount(box, draw) {
  let lastW = 0;
  const run = () => { const w = Math.floor(box.clientWidth); if (w > 0 && w !== lastW) { lastW = w; draw(w); } };
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(run).observe(box);
  requestAnimationFrame(run);
}
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function placeTip(tip, box, x, y) {
  tip.hidden = false;
  const half = tip.offsetWidth / 2;
  tip.style.left = clamp(x, half + 2, box.clientWidth - half - 2) + 'px';
  tip.style.top = Math.max(y, tip.offsetHeight + 6) + 'px';
}

/** data: [{ts, count}] */
export function barChart(data, { height = 220, name = '上行消息', unit = '条' } = {}) {
  const box = h('div', { class: 'chart' });
  const tip = h('div', { class: 'chart-tip', hidden: true });
  function draw(W) {
    const H = height;
    if (!data.length) { box.replaceChildren(h('div', { class: 'chart-empty' }, '暂无数据')); return; }
    const maxV = Math.max(...data.map((d) => d.count), 1);
    const { ticks, hi, dec } = niceTicks(0, maxV, 5);
    const labels = ticks.map((t) => fmtTick(t, dec));
    const m = { l: Math.max(...labels.map((x) => x.length)) * 6.5 + 14, r: 8, t: 10, b: 24 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    const y = (v) => m.t + ih - (v / hi) * ih;
    const slot = iw / data.length, bw = Math.max(2, slot * 0.7);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': `${name}柱状图，最近 ${data.length} 分钟` });
    ticks.forEach((t, i) => {
      svg.append(s('line', { class: 'grid', x1: m.l, x2: W - m.r, y1: y(t), y2: y(t) }));
      svg.append(s('text', { x: m.l - 6, y: y(t) + 4, 'text-anchor': 'end' }, labels[i]));
    });
    svg.append(s('line', { class: 'axis', x1: m.l, x2: W - m.r, y1: y(0), y2: y(0) }));
    const every = Math.ceil(data.length / Math.max(2, Math.floor(iw / 64)));
    const bars = data.map((d, i) => {
      const x = m.l + i * slot + (slot - bw) / 2;
      const r = s('rect', { class: 'bar', x, y: y(d.count), width: bw, height: Math.max(0, y(0) - y(d.count)), rx: Math.min(3, bw / 2) });
      svg.append(r);
      if (i % every === 0) svg.append(s('text', { x: x + bw / 2, y: H - 6, 'text-anchor': 'middle' }, fmtAxisTime(d.ts, false)));
      return r;
    });
    const hit = s('rect', { x: m.l, y: m.t, width: iw, height: ih, fill: 'transparent' });
    let cur = -1;
    hit.addEventListener('pointermove', (e) => {
      const rect = svg.getBoundingClientRect();
      const px = (e.clientX - rect.left) * (W / rect.width);
      const i = clamp(Math.floor((px - m.l) / slot), 0, data.length - 1);
      if (cur >= 0) bars[cur].classList.remove('hot');
      cur = i; bars[i].classList.add('hot');
      tip.replaceChildren(h('div', {}, fmtTime(data[i].ts, false).slice(0, 5)), h('div', {}, `${name} `, h('b', {}, fmtValue(data[i].count)), ` ${unit}`));
      placeTip(tip, box, m.l + i * slot + slot / 2, y(data[i].count) - 6);
    });
    hit.addEventListener('pointerleave', () => { tip.hidden = true; if (cur >= 0) bars[cur].classList.remove('hot'); cur = -1; });
    svg.append(hit);
    box.replaceChildren(svg, tip);
  }
  mount(box, draw);
  return box;
}

/** points: [{ts, value:number}] sorted by ts */
export function lineChart(points, { height = 280, unit = '', name = '' } = {}) {
  const box = h('div', { class: 'chart' });
  const tip = h('div', { class: 'chart-tip', hidden: true });
  function draw(W) {
    const H = height;
    if (!points.length) { box.replaceChildren(h('div', { class: 'chart-empty' }, '该时间范围内没有数据')); return; }
    let vmin = Infinity, vmax = -Infinity;
    for (const p of points) { if (p.value < vmin) vmin = p.value; if (p.value > vmax) vmax = p.value; }
    const yt = niceTicks(vmin, vmax, 5);
    const labels = yt.ticks.map((t) => fmtTick(t, yt.dec));
    const m = { l: Math.max(...labels.map((x) => x.length)) * 6.5 + 14, r: 14, t: 12, b: 26 };
    const iw = W - m.l - m.r, ih = H - m.t - m.b;
    let t0 = points[0].ts, t1 = points[points.length - 1].ts;
    if (t0 === t1) { t0 -= 30000; t1 += 30000; }
    const x = (t) => m.l + ((t - t0) / (t1 - t0)) * iw;
    const y = (v) => m.t + ih - ((v - yt.lo) / (yt.hi - yt.lo)) * ih;
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': `${name || '数据'}曲线，共 ${points.length} 个点` });
    yt.ticks.forEach((t, i) => {
      svg.append(s('line', { class: 'grid', x1: m.l, x2: W - m.r, y1: y(t), y2: y(t) }));
      svg.append(s('text', { x: m.l - 6, y: y(t) + 4, 'text-anchor': 'end' }, labels[i]));
    });
    const tt = timeTicks(t0, t1, Math.max(2, Math.floor(iw / 90)));
    svg.append(s('line', { class: 'axis', x1: m.l, x2: W - m.r, y1: H - m.b, y2: H - m.b }));
    tt.ticks.forEach((t) => svg.append(s('text', { x: x(t), y: H - 8, 'text-anchor': 'middle' }, fmtAxisTime(t, tt.withDate))));
    const d = points.map((p, i) => `${i ? 'L' : 'M'}${x(p.ts).toFixed(1)},${y(p.value).toFixed(1)}`).join('');
    svg.append(s('path', { class: 'area', d: `${d}L${x(points[points.length - 1].ts).toFixed(1)},${H - m.b}L${x(points[0].ts).toFixed(1)},${H - m.b}Z` }));
    svg.append(s('path', { class: 'line', d }));
    if (points.length === 1) svg.append(s('circle', { class: 'dot', cx: x(points[0].ts), cy: y(points[0].value), r: 4 }));
    const cross = s('line', { class: 'cross', y1: m.t, y2: H - m.b, hidden: true });
    const dot = s('circle', { class: 'dot', r: 4.5, hidden: true });
    const hit = s('rect', { x: m.l, y: m.t, width: iw, height: ih, fill: 'transparent' });
    hit.addEventListener('pointermove', (e) => {
      const rect = svg.getBoundingClientRect();
      const px = (e.clientX - rect.left) * (W / rect.width);
      const t = t0 + ((px - m.l) / iw) * (t1 - t0);
      let lo = 0, hi = points.length - 1;
      while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (points[mid].ts < t) lo = mid; else hi = mid; }
      const p = Math.abs(points[lo].ts - t) <= Math.abs(points[hi].ts - t) ? points[lo] : points[hi];
      const cx = x(p.ts), cy = y(p.value);
      cross.setAttribute('x1', cx); cross.setAttribute('x2', cx); cross.removeAttribute('hidden');
      dot.setAttribute('cx', cx); dot.setAttribute('cy', cy); dot.removeAttribute('hidden');
      tip.replaceChildren(h('div', {}, fmtTime(p.ts)), h('div', {}, name ? `${name} ` : '', h('b', {}, fmtValue(p.value)), unit ? ` ${unit}` : ''));
      placeTip(tip, box, cx, cy - 10);
    });
    hit.addEventListener('pointerleave', () => { tip.hidden = true; cross.setAttribute('hidden', ''); dot.setAttribute('hidden', ''); });
    svg.append(cross, dot, hit);
    box.replaceChildren(svg, tip);
  }
  mount(box, draw);
  return box;
}
