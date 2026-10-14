import { api, getProducts } from '../api.js';
import { h, field, select, formModal, secretDialog, infoDialog, table, copyBtn, copyText, toast, toCsv, downloadText } from '../ui.js';
import { tagsEditor } from '../forms.js';

const productOptions = (prods) => prods.map((p) => [p.productKey, `${p.name} (${p.productKey})`]);

export async function createDeviceDialog({ productKey, onDone } = {}) {
  const prods = await getProducts();
  if (!prods.length) { toast('请先创建产品', 'warn'); return; }
  const prod = select(productOptions(prods), productKey || prods[0].productKey, { disabled: !!productKey });
  const name = h('input', { type: 'text', required: true, maxlength: 64, autocomplete: 'off' });
  const nick = h('input', { type: 'text', maxlength: 64 });
  const tags = tagsEditor({});
  formModal({
    title: '新建设备', submitText: '创建',
    body: h('div', {}, field('所属产品', prod, { required: true }),
      field('DeviceName', name, { required: true, hint: '产品内唯一，建议使用字母、数字及 _ -' }),
      field('备注名', nick),
      h('div', { class: 'field' }, h('span', { class: 'field-label' }, '标签'), tags.el)),
    onSubmit: async () => {
      const d = await api.post('/api/devices', { productKey: prod.value, deviceName: name.value.trim(), nickname: nick.value.trim(), tags: tags.get() });
      toast('设备已创建', 'success');
      onDone?.(d);
      secretDialog('设备已创建', [['DeviceName', d.deviceName], ['ProductKey', d.productKey, true], ['DeviceSecret（请妥善保存）', d.deviceSecret, true]]);
    },
  });
}

export async function batchDeviceDialog({ productKey, onDone } = {}) {
  const prods = await getProducts();
  if (!prods.length) { toast('请先创建产品', 'warn'); return; }
  const prod = select(productOptions(prods), productKey || prods[0].productKey, { disabled: !!productKey });
  const prefix = h('input', { type: 'text', required: true, maxlength: 40, value: 'dev-' });
  const count = h('input', { type: 'number', required: true, min: 1, max: 500, value: 10 });
  formModal({
    title: '批量创建设备', submitText: '创建',
    body: h('div', {}, field('所属产品', prod, { required: true }),
      h('div', { class: 'form-grid' }, field('名称前缀', prefix, { required: true, hint: '生成 前缀+序号，如 dev-001' }), field('数量', count, { required: true, hint: '1 ~ 500' }))),
    onSubmit: async () => {
      const n = Number(count.value);
      if (!Number.isInteger(n) || n < 1 || n > 500) throw new Error('数量需为 1 ~ 500 的整数');
      const list = await api.post('/api/devices/batch', { productKey: prod.value, prefix: prefix.value.trim(), count: n });
      toast(`已创建 ${list.length} 台设备`, 'success');
      onDone?.(list);
      const csv = toCsv([['productKey', 'deviceName', 'deviceSecret'], ...list.map((d) => [d.productKey, d.deviceName, d.deviceSecret])]);
      infoDialog(`已创建 ${list.length} 台设备`, h('div', {},
        h('p', { class: 'hint', style: { marginBottom: '10px' } }, 'DeviceSecret 仅在此处与设备详情中可见，请及时导出保存。'),
        h('div', { class: 'row', style: { marginBottom: '10px' } },
          h('button', { type: 'button', class: 'btn btn-sm', onclick: () => downloadText('devices.csv', csv) }, '下载 CSV'),
          h('button', { type: 'button', class: 'btn btn-sm', onclick: () => copyText(csv) }, '复制 CSV')),
        h('div', { style: { maxHeight: '300px', overflow: 'auto' } }, table([
          { title: 'DeviceName', render: (d) => d.deviceName },
          { title: 'DeviceSecret', render: (d) => h('span', { class: 'secret' }, h('code', {}, d.deviceSecret), copyBtn(d.deviceSecret)) },
        ], list))), 'wide');
    },
  });
}
