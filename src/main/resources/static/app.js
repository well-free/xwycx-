const tokenKey = 'xwycx.token';
const initialToken = localStorage.getItem(tokenKey) || '';

if (!initialToken) {
  window.location.replace(`/login.html?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`);
}

const state = {
  token: initialToken,
  user: null,
  products: [],
  selectedProductId: null,
  orders: [],
  payments: [],
  selectedPaymentId: null,
  productSearch: '',
  productSort: 'hot',
  orderStatusFilter: 'ALL',
  initialPaymentOrderId: new URLSearchParams(window.location.search).get('orderId') || ''
};

const els = {
  serverStatus: document.getElementById('serverStatus'),
  userStatus: document.getElementById('userStatus'),
  refreshBtn: document.getElementById('refreshBtn'),
  logoutBtn: document.getElementById('logoutBtn'),
  workspaceTabs: document.getElementById('workspaceTabs'),
  productsGrid: document.getElementById('productsGrid'),
  productSearchInput: document.getElementById('productSearchInput'),
  productSortInput: document.getElementById('productSortInput'),
  selectedProduct: document.getElementById('selectedProduct'),
  quantityInput: document.getElementById('quantityInput'),
  createOrderBtn: document.getElementById('createOrderBtn'),
  orderStatusFilter: document.getElementById('orderStatusFilter'),
  refreshOrdersBtn: document.getElementById('refreshOrdersBtn'),
  ordersTable: document.getElementById('ordersTable'),
  paymentOrderIdInput: document.getElementById('paymentOrderIdInput'),
  paymentChannelInput: document.getElementById('paymentChannelInput'),
  paymentCreateBtn: document.getElementById('paymentCreateBtn'),
  paymentSuccessBtn: document.getElementById('paymentSuccessBtn'),
  paymentRefundBtn: document.getElementById('paymentRefundBtn'),
  paymentResult: document.getElementById('paymentResult'),
  paymentsTable: document.getElementById('paymentsTable'),
  adminSkuInput: document.getElementById('adminSkuInput'),
  adminNameInput: document.getElementById('adminNameInput'),
  adminPriceInput: document.getElementById('adminPriceInput'),
  adminStockInput: document.getElementById('adminStockInput'),
  adminCreateProductBtn: document.getElementById('adminCreateProductBtn'),
  adminShipBtn: document.getElementById('adminShipBtn'),
  adminResult: document.getElementById('adminResult'),
  adminRoleChip: document.getElementById('adminRoleChip'),
  metricUser: document.getElementById('metricUser'),
  metricRole: document.getElementById('metricRole'),
  metricProducts: document.getElementById('metricProducts'),
  metricOrders: document.getElementById('metricOrders'),
  metricPending: document.getElementById('metricPending'),
  metricPaidOrders: document.getElementById('metricPaidOrders'),
  metricPayments: document.getElementById('metricPayments'),
  metricPaid: document.getElementById('metricPaid'),
  toast: document.getElementById('toast')
};

const orderStatusLabels = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  FULFILLING: '备货中',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
};

const paymentStatusLabels = {
  PENDING: '待支付',
  PAYING: '支付中',
  SUCCESS: '已支付',
  FAILED: '失败',
  CLOSED: '已关闭',
  REFUNDING: '退款中',
  REFUNDED: '已退款'
};

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function formatNumber(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '--';
  }
  return number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatTime(value) {
  if (!value) {
    return '--';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(new Date(value));
}

function showToast(message, tone = 'info') {
  if (!els.toast) {
    return;
  }
  els.toast.textContent = message;
  els.toast.dataset.tone = tone;
  els.toast.classList.add('show');
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => els.toast.classList.remove('show'), 2400);
}

function setStatus(online) {
  if (!els.serverStatus) {
    return;
  }
  els.serverStatus.classList.toggle('offline', !online);
  els.serverStatus.innerHTML = `<span class="health-dot"></span>${online ? '服务在线' : '服务离线'}`;
}

function redirectToLogin() {
  localStorage.removeItem(tokenKey);
  window.location.replace(`/login.html?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(state.token ? { 'X-Session-Token': state.token } : {}),
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

function renderTable(target, rows, columns, emptyText) {
  if (!target) {
    return;
  }
  if (!rows.length) {
    target.innerHTML = `<div class="empty-state">${emptyText}</div>`;
    return;
  }
  const head = columns.map((column) => `<th>${column.label}</th>`).join('');
  const body = rows.map((row) => {
    const cells = columns.map((column) => `<td>${column.render(row)}</td>`).join('');
    return `<tr>${cells}</tr>`;
  }).join('');
  target.innerHTML = `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

function renderMetrics() {
  const pending = state.orders.filter((order) => order.status === 'PENDING_PAYMENT').length;
  const paidOrders = state.orders.filter((order) => ['PAID', 'FULFILLING', 'SHIPPED', 'COMPLETED'].includes(order.status)).length;
  const paid = state.payments.filter((payment) => payment.status === 'SUCCESS' || payment.status === 'REFUNDED').length;
  if (els.metricUser) {
    els.metricUser.textContent = state.user ? state.user.phone : '未登录';
  }
  if (els.metricRole) {
    els.metricRole.textContent = state.user ? state.user.role : '请先登录后下单';
  }
  if (els.userStatus) {
    els.userStatus.innerHTML = `<span class="health-dot"></span>${state.user ? `${state.user.phone} · ${state.user.role}` : '未登录'}`;
  }
  if (els.metricProducts) {
    els.metricProducts.textContent = state.products.length;
  }
  if (els.metricOrders) {
    els.metricOrders.textContent = state.orders.length;
  }
  if (els.metricPending) {
    els.metricPending.textContent = `${pending} 笔待支付`;
  }
  if (els.metricPaidOrders) {
    els.metricPaidOrders.textContent = paidOrders;
  }
  if (els.metricPayments) {
    els.metricPayments.textContent = state.payments.length;
  }
  if (els.metricPaid) {
    els.metricPaid.textContent = `${paid} 笔支付完成`;
  }
  if (els.adminRoleChip) {
    els.adminRoleChip.textContent = state.user?.role === 'ADMIN' ? '管理员在线' : '需要管理员';
    els.adminRoleChip.classList.toggle('success', state.user?.role === 'ADMIN');
  }
}

function syncAdminVisibility() {
  const isAdmin = state.user?.role === 'ADMIN';
  const adminOnlyPage = document.body.classList.contains('admin-only-page');

  if (adminOnlyPage && !isAdmin) {
    window.location.replace('/index.html');
    return;
  }

  document.body.classList.toggle('admin-verified', isAdmin);

  document.querySelectorAll('.workspace-tabs').forEach((tabs) => {
    let adminLink = tabs.querySelector('[data-admin-link]');
    if (isAdmin && !adminLink) {
      adminLink = document.createElement('a');
      adminLink.href = '/admin.html';
      adminLink.dataset.adminLink = 'true';
      adminLink.textContent = '后台运营';
      if (adminOnlyPage) {
        adminLink.classList.add('active');
      }
      tabs.appendChild(adminLink);
    }
    if (!isAdmin && adminLink) {
      adminLink.remove();
    }
  });

  const dashboardLinks = document.querySelector('.dashboard-links');
  if (dashboardLinks) {
    let adminEntry = dashboardLinks.querySelector('[data-admin-entry]');
    if (isAdmin && !adminEntry) {
      adminEntry = document.createElement('a');
      adminEntry.className = 'panel feature-entry';
      adminEntry.href = '/admin.html';
      adminEntry.dataset.adminEntry = 'true';
      adminEntry.innerHTML = '<span>Admin</span><strong>后台运营</strong><small>管理员新增商品、维护库存并录入发货。</small>';
      dashboardLinks.appendChild(adminEntry);
    }
    if (!isAdmin && adminEntry) {
      adminEntry.remove();
    }
  }
}

function renderProducts() {
  if (!els.productsGrid) {
    return;
  }
  const keyword = state.productSearch.trim().toLowerCase();
  const rows = state.products
    .filter((product) => {
      if (!keyword) {
        return true;
      }
      return [product.name, product.sku, product.spec, product.unit]
        .some((value) => String(value || '').toLowerCase().includes(keyword));
    })
    .sort((a, b) => {
      if (state.productSort === 'priceAsc') {
        return Number(a.price) - Number(b.price);
      }
      if (state.productSort === 'stockDesc') {
        return Number(b.stock) - Number(a.stock);
      }
      return Number(b.hotScore || 0) - Number(a.hotScore || 0);
    });

  if (!rows.length) {
    els.productsGrid.innerHTML = '<div class="empty-state">暂无可采购商品</div>';
    return;
  }
  els.productsGrid.innerHTML = rows.map((product) => {
    const image = product.mainImage ? `<img src="${escapeHtml(product.mainImage)}" alt="${escapeHtml(product.name)}" loading="lazy" onerror="this.remove()" />` : '';
    return `
    <button type="button" class="product-card ${state.selectedProductId === product.id ? 'active' : ''}" data-product-id="${product.id}">
      <span class="product-media">${image}<b>${escapeHtml(product.sku || 'SKU')}</b></span>
      <span class="product-info">
        <strong>${escapeHtml(product.name)}</strong>
        <small>${escapeHtml(product.sku)} · ${escapeHtml(product.spec)} · ${escapeHtml(product.unit)}</small>
      </span>
      <span class="product-meta">
        <em>¥${formatNumber(product.price)}</em>
        <small>库存 ${product.stock} · 热度 ${product.hotScore || 0}</small>
      </span>
    </button>
  `;
  }).join('');
  const selected = state.products.find((product) => product.id === state.selectedProductId);
  if (els.selectedProduct) {
    els.selectedProduct.innerHTML = selected
      ? `<strong>${escapeHtml(selected.name)}</strong><span>${escapeHtml(selected.sku)} · ¥${formatNumber(selected.price)} · 库存 ${selected.stock} · ${escapeHtml(selected.spec)}</span>`
      : '请选择商品';
  }
}

function renderOrders() {
  if (!els.ordersTable) {
    return;
  }
  const rows = [...state.orders]
    .filter((order) => state.orderStatusFilter === 'ALL' || order.status === state.orderStatusFilter)
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  renderTable(
    els.ordersTable,
    rows,
    [
      { label: '订单', render: (o) => `<button class="link-button" data-select-order="${o.id}">${o.orderNo}</button>` },
      { label: '金额', render: (o) => `¥${formatNumber(o.totalAmount)}` },
      { label: '状态', render: (o) => `<span class="status-badge ${o.status}">${orderStatusLabels[o.status] || o.status}</span>` },
      { label: '商品', render: (o) => escapeHtml(o.items?.[0]?.productName || '--') },
      { label: '时间', render: (o) => formatTime(o.createdAt) },
      { label: '操作', render: (o) => `<button class="table-action" data-select-order="${o.id}">${o.status === 'PENDING_PAYMENT' ? '去支付' : '查看'}</button>` }
    ],
    state.user ? '暂无订单，选择商品后创建采购单' : '登录后查看订单'
  );
}

function renderPayments() {
  if (!els.paymentsTable) {
    return;
  }
  const rows = [...state.payments].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  renderTable(
    els.paymentsTable,
    rows,
    [
      { label: '支付单', render: (p) => `<button class="link-button" data-select-payment="${p.id}">${p.id}</button>` },
      { label: '订单', render: (p) => escapeHtml(p.orderId) },
      { label: '渠道', render: (p) => p.channel === 'ALIPAY' ? '支付宝' : '微信' },
      { label: '模式', render: (p) => escapeHtml(p.gatewayMode || 'mock') },
      { label: '金额', render: (p) => `¥${formatNumber(p.amount)}` },
      { label: '状态', render: (p) => paymentStatusLabels[p.status] || p.status }
    ],
    '暂无支付记录'
  );
}

function renderAll() {
  renderMetrics();
  renderProducts();
  renderOrders();
  renderPayments();
}

async function refreshAll(silent = false) {
  try {
    const [health, products, payments] = await Promise.all([
      request('/api/health'),
      request('/api/products'),
      request('/api/payments').catch(() => ({ items: [] }))
    ]);
    setStatus(health.status === 'ok');
    state.products = products.items || [];
    state.payments = payments.items || [];
    state.user = await request('/api/auth/me');
    syncAdminVisibility();
    const orders = await request('/api/customer-orders');
    state.orders = orders.items || [];
    if (!state.selectedProductId && state.products[0]) {
      state.selectedProductId = state.products[0].id;
    }
    renderAll();
    if (state.initialPaymentOrderId && els.paymentOrderIdInput) {
      els.paymentOrderIdInput.value = state.initialPaymentOrderId;
      if (els.paymentResult) {
        els.paymentResult.innerHTML = `<strong>已带入订单 ${escapeHtml(state.initialPaymentOrderId)}</strong><span>请选择支付渠道后发起支付</span>`;
      }
      state.initialPaymentOrderId = '';
    }
    if (!silent) {
      showToast('数据已刷新');
    }
  } catch (error) {
    setStatus(false);
    if (error.message.includes('login required') || error.message.includes('HTTP 401')) {
      redirectToLogin();
      return;
    }
    showToast(error.message, 'error');
  }
}

els.logoutBtn?.addEventListener('click', async () => {
  try {
    await request('/api/auth/logout', { method: 'POST', body: '{}' });
  } catch {
  }
  state.token = '';
  state.user = null;
  redirectToLogin();
});

document.addEventListener('click', (event) => {
  const trigger = event.target.closest('[data-scroll-target]');
  if (!trigger) {
    return;
  }
  const target = document.getElementById(trigger.dataset.scrollTarget);
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  if (els.workspaceTabs && trigger.parentElement === els.workspaceTabs) {
    [...els.workspaceTabs.querySelectorAll('button')].forEach((button) => button.classList.remove('active'));
    trigger.classList.add('active');
  }
});

els.productSearchInput?.addEventListener('input', () => {
  state.productSearch = els.productSearchInput.value;
  renderProducts();
});

els.productSortInput?.addEventListener('change', () => {
  state.productSort = els.productSortInput.value;
  renderProducts();
});

els.orderStatusFilter?.addEventListener('change', () => {
  state.orderStatusFilter = els.orderStatusFilter.value;
  renderOrders();
});

els.productsGrid?.addEventListener('click', (event) => {
  const button = event.target.closest('[data-product-id]');
  if (!button) {
    return;
  }
  state.selectedProductId = Number(button.dataset.productId);
  renderProducts();
});

els.createOrderBtn?.addEventListener('click', async () => {
  if (!state.selectedProductId) {
    showToast('请先选择商品', 'error');
    return;
  }
  try {
    const order = await request('/api/customer-orders', {
      method: 'POST',
      body: JSON.stringify({
        items: [{ productId: state.selectedProductId, quantity: Number(els.quantityInput.value) }],
        addressId: 1,
        remark: '前端采购单'
      })
    });
    showToast('采购单已创建');
    await refreshAll(true);
    if (els.paymentOrderIdInput) {
      els.paymentOrderIdInput.value = order.id;
      document.getElementById('paymentPanel')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else {
      window.location.href = `/orders.html?orderId=${order.id}`;
    }
  } catch (error) {
    showToast(error.message, 'error');
  }
});

els.ordersTable?.addEventListener('click', (event) => {
  const orderId = event.target.dataset.selectOrder;
  if (orderId) {
    els.paymentOrderIdInput.value = orderId;
    showToast(`已选中订单 ${orderId}`);
  }
});

els.paymentsTable?.addEventListener('click', (event) => {
  const paymentId = event.target.dataset.selectPayment;
  if (!paymentId) {
    return;
  }
  state.selectedPaymentId = paymentId;
  const payment = state.payments.find((item) => String(item.id) === String(paymentId));
  if (payment) {
    if (els.paymentOrderIdInput) {
      els.paymentOrderIdInput.value = payment.orderId;
    }
    if (els.paymentResult) {
      els.paymentResult.innerHTML = `<strong>已选中支付单 ${payment.id}</strong><span>${payment.channel} · ${escapeHtml(payment.gatewayMode || 'mock')} · ¥${formatNumber(payment.amount)} · ${paymentStatusLabels[payment.status] || payment.status}</span>`;
    }
  }
});

els.paymentCreateBtn?.addEventListener('click', async () => {
  const orderId = els.paymentOrderIdInput.value.trim();
  if (!orderId) {
    showToast('请先选择订单', 'error');
    return;
  }
  try {
    const payment = await request(`/api/customer-orders/${orderId}/payments`, {
      method: 'POST',
      body: JSON.stringify({ channel: els.paymentChannelInput.value })
    });
    state.selectedPaymentId = payment.id;
    if (els.paymentResult) {
      els.paymentResult.innerHTML = `<strong>支付单 ${payment.id} 已创建</strong><span>${escapeHtml(payment.gatewayMode || 'mock')} · ¥${formatNumber(payment.amount)} · ${escapeHtml(payment.qrCode)}</span>`;
    }
    showToast('支付单已创建');
    await refreshAll(true);
  } catch (error) {
    showToast(error.message, 'error');
  }
});

els.paymentSuccessBtn?.addEventListener('click', async () => {
  const payment = state.payments.find((item) => String(item.id) === String(state.selectedPaymentId))
    || state.payments.find((item) => String(item.orderId) === els.paymentOrderIdInput.value.trim());
  if (!payment) {
    showToast('请先创建或选择支付单', 'error');
    return;
  }
  try {
    await request(`/api/payments/${payment.id}/simulate-success`, { method: 'POST', body: '{}' });
    showToast('支付回调已处理');
    await refreshAll(true);
  } catch (error) {
    showToast(error.message, 'error');
  }
});

els.paymentRefundBtn?.addEventListener('click', async () => {
  const payment = state.payments.find((item) => String(item.id) === String(state.selectedPaymentId));
  if (!payment) {
    showToast('请先选择支付单', 'error');
    return;
  }
  try {
    await request(`/api/customer-orders/${payment.orderId}/refunds`, {
      method: 'POST',
      body: JSON.stringify({ amount: payment.amount, reason: '前端申请退款' })
    });
    showToast('退款已提交');
    await refreshAll(true);
  } catch (error) {
    showToast(error.message, 'error');
  }
});

els.adminCreateProductBtn?.addEventListener('click', async () => {
  try {
    const product = await request('/api/admin/products', {
      method: 'POST',
      body: JSON.stringify({
        sku: els.adminSkuInput.value.trim(),
        name: els.adminNameInput.value.trim(),
        price: Number(els.adminPriceInput.value),
        stock: Number(els.adminStockInput.value),
        spec: '100只/盒',
        unit: '盒',
        status: 'ON_SHELF',
        sortOrder: 90
      })
    });
    if (els.adminResult) {
      els.adminResult.textContent = `商品 ${product.sku} 已创建`;
    }
    showToast('商品已创建');
    await refreshAll(true);
  } catch (error) {
    if (els.adminResult) {
      els.adminResult.textContent = error.message;
    }
    showToast(error.message, 'error');
  }
});

els.adminShipBtn?.addEventListener('click', async () => {
  const orderId = els.paymentOrderIdInput.value.trim();
  if (!orderId) {
    showToast('请先选择订单', 'error');
    return;
  }
  try {
    await request(`/api/admin/orders/${orderId}/ship`, { method: 'POST', body: '{}' });
    showToast('发货状态已更新');
    await refreshAll(true);
  } catch (error) {
    showToast(error.message, 'error');
  }
});

els.refreshBtn?.addEventListener('click', () => refreshAll());
els.refreshOrdersBtn?.addEventListener('click', () => refreshAll());
refreshAll(true);
