import { api, realtime } from '../api.js';
import { h, pageHead, badge, statusBadge, timeEl, fmtTime, compactJson, errorBox, loadingBox, emptyBox } from '../ui.js';
import { barChart } from '../charts.js';

// Live feed buffer lives at module level so it survives navigation between pages.
const FEED_MAX = 50;
const feed = [];
let paused = false, started = false;
const feedSubs = new Set();

function toFeed(m) {
  const ts = m.ts || Date.now();
  switch (m.type) {
    case 'telemetry': return { ts, label: '遥测', tone: 'blue', device: m.deviceName, text: compactJson(m.params, 140) };
    case 'status': return { ts, label: '状态', tone: m.status === 'ONLINE' ? 'green' : '', device: m.deviceName, text: m.status === 'ONLINE' ? '设备上线' : m.status === 'OFFLINE' ? '设备离线' : String(m.status) };
    case 'alarm': return m.alarm ? { ts: m.alarm.triggeredAt || ts, label: '告警', tone: 'red', device: m.alarm.deviceName, text: `[${m.alarm.ruleName ?? ''}] ${m.alarm.message ?? ''}`, alarm: true } : null;
    case 'command': return m.command ? { ts, label: '指令', tone: 'amber', device: m.command.deviceName, text: `${m.command.identifier} → ${m.command.status}` } : null;
    default: return null;
  }
}
function startFeed() {
  if (started) return;
  started = true;
  realtime.on((m) => {
    if (paused) return;
    const it = toFeed(m);
    if (!it) return;
    feed.unshift(it);
    if (feed.length > FEED_MAX) feed.length = FEED_MAX;
    feedSubs.forEach((f) => f(it));
  });
}
const feedRow = (it) => h('div', { class: 'feed-item' },
  h('span', { class: 'time' }, fmtTime(it.ts, false)), badge(it.label, it.tone), h('span', { class: 'msg' }, h('b', {}, it.device ? it.device + ' ' : ''), it.text));

function statCard(label, value, href, tone, hint) {
  return h('a', { class: 'stat ' + tone, href }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, String(value ?? '-')), hint && h('div', { class: 'hint' }, hint));
}

export default async function mount(el, _p, ctx) {
  ctx.setTitle('看板');
  startFeed();
  const stats = h('div', { class: 'stat-grid' });
  const chartBox = h('div', {}, loadingBox());
  const alarmBox = h('div', {}, loadingBox());
  const feedBox = h('div', { class: 'feed', 'aria-live': 'off' });
  const pauseBtn = h('button', { type: 'button', class: 'btn btn-sm', onclick: () => { paused = !paused; pauseBtn.textContent = paused ? '继续' : '暂停'; } }, paused ? '继续' : '暂停');
  const clearBtn = h('button', { type: 'button', class: 'btn btn-sm', onclick: () => { feed.length = 0; renderFeed(); } }, '清空');
  const renderFeed = () => feedBox.replaceChildren(...(feed.length ? feed.map(feedRow) : [h('div', { class: 'muted', style: { padding: '16px 0' } }, '等待实时数据…（遥测 / 状态 / 告警 / 指令）')]));

  async function refresh() {
    try {
      const d = await api.get('/api/dashboard');
      stats.replaceChildren(
        statCard('产品数', d.products, '#/products', ''),
        statCard('设备总数', d.devices, '#/devices', ''),
        statCard('在线设备', d.online, '#/devices', 'green', d.devices ? `在线率 ${Math.round((d.online / d.devices) * 100)}%` : ''),
        statCard('活跃告警', d.activeAlarms, '#/alarms', d.activeAlarms > 0 ? 'red' : 'amber', d.activeAlarms > 0 ? '需要处理' : '一切正常'));
      chartBox.replaceChildren(barChart(d.uplinkPerMinute || [], { name: '上行消息' }));
      const alarms = d.recentAlarms || [];
      alarmBox.replaceChildren(alarms.length ? h('div', {}, alarms.map((a) => h('div', { class: 'feed-item', style: { gridTemplateColumns: '58px minmax(0,1fr) auto' } },
        statusBadge('level', a.level),
        h('span', { class: 'msg' }, h('a', { href: `#/devices/${a.deviceId}` }, a.deviceName), ' ', a.message),
        timeEl(a.triggeredAt)))) : emptyBox('最近没有告警'));
    } catch (e) {
      if (!stats.children.length) stats.replaceChildren(errorBox(e, refresh));
    }
  }
  el.append(
    pageHead('看板', '平台运行概览'),
    stats,
    h('div', { class: 'grid-21', style: { marginBottom: '16px' } },
      h('section', { class: 'layer' }, h('h3', {}, '上行消息 / 分钟（最近 30 分钟）'), chartBox),
      h('section', { class: 'layer' }, h('h3', {}, '最近告警', h('span', { class: 'spacer' }), h('a', { href: '#/alarms' }, '全部')), alarmBox)),
    h('section', { class: 'layer' }, h('h3', {}, '实时消息', h('span', { class: 'spacer' }), pauseBtn, clearBtn), feedBox));
  renderFeed();
  const onItem = (it) => {
    if (feedBox.querySelector('.muted')) feedBox.replaceChildren();
    const row = feedRow(it); row.classList.add('flash');
    feedBox.prepend(row);
    while (feedBox.children.length > FEED_MAX) feedBox.lastChild.remove();
  };
  feedSubs.add(onItem);
  ctx.onCleanup(() => feedSubs.delete(onItem));
  let t;
  const off = realtime.on((m) => { if (m.type === 'alarm' || m.type === 'status') { clearTimeout(t); t = setTimeout(refresh, 800); } });
  ctx.onCleanup(() => { off(); clearTimeout(t); });
  ctx.interval(refresh, 30000);
  await refresh();
}
