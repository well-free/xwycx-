export default function Header({ active, user, online, onRefresh, onLogout }) {
  const links = [
    ['/index.html', '首页', 'home'],
    ['/products.html', '商品采购', 'products'],
    ['/orders.html', '我的订单', 'orders']
  ];
  if (user?.role === 'ADMIN') links.push(['/admin.html', '后台运营', 'admin']);
  return (
    <header className="topbar commerce-topbar">
      <a className="brand" href="/index.html" aria-label="小物优采首页">
        <span className="brand-mark">优</span>
        <div><h1>小物优采</h1><p>xwycx.xyz</p></div>
      </a>
      <nav className="workspace-tabs" aria-label="页面导航">
        {links.map(([href, label, key]) => <a key={key} className={active === key ? 'active' : ''} href={href}>{label}</a>)}
      </nav>
      <div className="topbar-actions">
        <div className="health-pill"><span className="health-dot" />{user ? `${user.phone} · ${user.role === 'ADMIN' ? '管理员' : '采购用户'}` : '未登录'}</div>
        <div className={`health-pill ${online ? '' : 'offline'}`}><span className="health-dot" />{online ? '服务在线' : '服务离线'}</div>
        <button className="icon-button" type="button" title="刷新数据" onClick={onRefresh}>↻</button>
        <button className="icon-button" type="button" title="退出登录" onClick={onLogout}>↪</button>
      </div>
    </header>
  );
}
