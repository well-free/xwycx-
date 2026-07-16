export default function AsyncState({ loading, error, empty = false, children }) {
  if (loading) return <div className="async-state" role="status">正在加载数据…</div>;
  if (error) return <div className="async-state error-state" role="alert">{error}</div>;
  if (empty) return <div className="async-state">暂无数据</div>;
  return children;
}
