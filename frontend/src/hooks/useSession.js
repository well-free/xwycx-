import { useEffect, useState } from 'react';
import { clearToken, getToken, redirectToLogin, request } from '../api';

export default function useSession() {
  const [user, setUser] = useState(null);
  const [online, setOnline] = useState(false);
  useEffect(() => {
    if (!getToken()) {
      redirectToLogin();
      return;
    }
    Promise.all([request('/api/auth/me'), request('/api/health')])
      .then(([me, health]) => { setUser(me); setOnline(health.status === 'ok'); })
      .catch(() => { clearToken(); redirectToLogin(); });
  }, []);
  return { user, online };
}
