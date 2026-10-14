import { api, auth } from '../api.js';
import { h, field, brand } from '../ui.js';

export default function mount(el) {
  const user = h('input', { type: 'text', name: 'username', autocomplete: 'username', required: true, autofocus: true });
  const pass = h('input', { type: 'password', name: 'password', autocomplete: 'current-password', required: true });
  const err = h('div', { class: 'form-error', role: 'alert', hidden: true, style: { margin: '0 0 12px' } });
  const btn = h('button', { type: 'submit', class: 'btn btn-primary', style: { width: '100%' } }, '登录');
  const form = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      err.hidden = true; btn.disabled = true; btn.classList.add('is-busy');
      try {
        const res = await api.post('/api/auth/login', { username: user.value.trim(), password: pass.value });
        auth.save(res.token, res.username || user.value.trim());
        let back = '#/dashboard';
        try { back = sessionStorage.getItem('hyperiot.returnTo') || back; sessionStorage.removeItem('hyperiot.returnTo'); } catch { /* ignore */ }
        if (!/^#\/(?!login)/.test(back)) back = '#/dashboard';
        location.hash = back;
      } catch (ex) {
        err.textContent = ex.message; err.hidden = false; pass.value = ''; pass.focus();
      } finally { btn.disabled = false; btn.classList.remove('is-busy'); }
    },
  }, field('用户名', user), field('密码', pass), err, btn);
  el.replaceChildren(h('div', { class: 'login-wrap' }, h('div', { class: 'card login-card' }, brand(), h('p', { class: 'lead' }, '登录以管理你的设备与数据'), form)));
}
