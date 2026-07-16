export default function Toast({ toast }) {
  return <div className={`toast ${toast ? 'show' : ''}`} data-tone={toast?.tone || 'info'} role="status">{toast?.message}</div>;
}
