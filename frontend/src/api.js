export const tokenKey = 'xwycx.token';

export function getToken() {
  return localStorage.getItem(tokenKey) || '';
}

export function clearToken() {
  localStorage.removeItem(tokenKey);
}

export async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { 'X-Session-Token': getToken() } : {}),
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
    if (response.status === 401 || response.status === 403) {
      clearToken();
    }
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return data;
}

export function redirectToLogin() {
  const redirect = `${window.location.pathname}${window.location.search}`;
  window.location.replace(`/login.html?redirect=${encodeURIComponent(redirect)}`);
}
