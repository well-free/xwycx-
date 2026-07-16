import { clearToken, redirectToLogin, request } from '../api';
import Header from './Header';
import Toast from './Toast';

export default function PageShell({ active, children, onRefresh, user, online, toast, showToast }) {
  const logout = async () => {
    try { await request('/api/auth/logout', { method: 'POST', body: '{}' }); } catch { /* best effort */ }
    clearToken();
    redirectToLogin();
  };
  return <main className="app-shell commerce-app"><Header active={active} user={user} online={online} onRefresh={onRefresh} onLogout={logout} />{children}<Toast toast={toast ?? showToast} /></main>;
}
