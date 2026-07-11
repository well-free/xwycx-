const tokenKey = 'xwycx.token';
const params = new URLSearchParams(window.location.search);
const redirectTo = normalizeRedirect(params.get('redirect'));

const els = {
  phoneInput: document.getElementById('phoneInput'),
  codeInput: document.getElementById('codeInput'),
  smsBtn: document.getElementById('smsBtn'),
  loginBtn: document.getElementById('loginBtn'),
  registerBtn: document.getElementById('registerBtn'),
  authResult: document.getElementById('authResult'),
  toast: document.getElementById('toast')
};

function normalizeRedirect(value) {
  if (!value || value === '/' || value === '/login.html' || value.startsWith('//')) {
    return '/index.html';
  }
  return value.startsWith('/') ? value : '/index.html';
}

function showToast(message, tone = 'info') {
  els.toast.textContent = message;
  els.toast.dataset.tone = tone;
  els.toast.classList.add('show');
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => els.toast.classList.remove('show'), 2400);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    },
    ...options
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!response.ok) {
    throw new Error(data && data.error ? data.error : `HTTP ${response.status}`);
  }
  return data;
}

async function redirectIfLoggedIn() {
  const token = localStorage.getItem(tokenKey);
  if (!token) {
    return;
  }
  try {
    const response = await fetch('/api/auth/me', { headers: { 'X-Session-Token': token } });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    window.location.replace(redirectTo);
  } catch {
    localStorage.removeItem(tokenKey);
  }
}

async function submitSmsAuth(successMessage) {
  const result = await request('/api/auth/sms/login', {
    method: 'POST',
    body: JSON.stringify({ phone: els.phoneInput.value.trim(), code: els.codeInput.value.trim() })
  });
  localStorage.setItem(tokenKey, result.token);
  els.authResult.textContent = `${successMessage}：${result.user.phone} (${result.user.role})`;
  showToast(successMessage);
  window.setTimeout(() => window.location.replace(redirectTo), 300);
}

async function registerAndEnter() {
  try {
    await submitSmsAuth('注册成功');
  } catch (error) {
    els.authResult.textContent = error.message;
    showToast(error.message, 'error');
  }
}

els.smsBtn.addEventListener('click', async () => {
  try {
    await request('/api/auth/sms/send', {
      method: 'POST',
      body: JSON.stringify({ phone: els.phoneInput.value.trim() })
    });
    els.authResult.textContent = '验证码已发送，本地环境固定为 123456';
    showToast('验证码已发送');
  } catch (error) {
    els.authResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.loginBtn.addEventListener('click', async () => {
  try {
    await submitSmsAuth('登录成功');
  } catch (error) {
    els.authResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.registerBtn.addEventListener('click', registerAndEnter);

redirectIfLoggedIn();
