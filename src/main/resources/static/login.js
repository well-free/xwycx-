const tokenKey = 'xwycx.token';
const params = new URLSearchParams(window.location.search);
const redirectTo = normalizeRedirect(params.get('redirect'));

let smsConfig = {
  local: true,
  localCode: '123456',
  codeTtlSeconds: 300
};

const els = {
  phoneInput: document.getElementById('phoneInput'),
  codeInput: document.getElementById('codeInput'),
  smsBtn: document.getElementById('smsBtn'),
  loginBtn: document.getElementById('loginBtn'),
  registerBtn: document.getElementById('registerBtn'),
  smsModeHint: document.getElementById('smsModeHint'),
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

async function loadSmsConfig() {
  try {
    smsConfig = await request('/api/auth/sms/config');
  } catch {
    smsConfig = { local: true, localCode: '123456', codeTtlSeconds: 300 };
  }
  if (smsConfig.local) {
    els.codeInput.value = smsConfig.localCode || '123456';
    els.smsModeHint.textContent = `本地验证码固定为 ${els.codeInput.value}，管理员手机号为 13900000000。`;
    els.authResult.textContent = '本地环境可直接使用固定验证码；新手机号会自动注册。';
  } else {
    els.codeInput.value = '';
    els.smsModeHint.textContent = '生产环境使用真实短信验证码，验证码 5 分钟内有效。';
    els.authResult.textContent = '请输入手机号并点击发送验证码。';
  }
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
  els.authResult.textContent = `${successMessage}: ${result.user.phone} (${result.user.role})`;
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
    const ttlMinutes = Math.max(1, Math.floor((smsConfig.codeTtlSeconds || 300) / 60));
    els.authResult.textContent = smsConfig.local
      ? `验证码已生成，本地环境固定为 ${smsConfig.localCode || '123456'}`
      : `验证码已发送，${ttlMinutes} 分钟内有效`;
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

loadSmsConfig();
redirectIfLoggedIn();
