import { useCallback, useEffect, useRef, useState } from 'react';

export default function useToast() {
  const [toast, setToast] = useState(null);
  const timer = useRef(null);
  const showToast = useCallback((message, tone = 'info') => {
    setToast({ message, tone });
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), 2400);
  }, []);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return [toast, showToast];
}
