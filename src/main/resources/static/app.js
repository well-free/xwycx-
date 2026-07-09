const state = {
  orders: [],
  trades: []
};

const orderForm = document.getElementById('orderForm');
const orderResult = document.getElementById('orderResult');
const queryResult = document.getElementById('queryResult');
const orderIdInput = document.getElementById('orderIdInput');
const productIdInput = document.getElementById('productIdInput');
const productResult = document.getElementById('productResult');
const systemResult = document.getElementById('systemResult');
const ordersTable = document.getElementById('ordersTable');
const tradesTable = document.getElementById('tradesTable');
const serverStatus = document.getElementById('serverStatus');
const cancelBtn = document.getElementById('cancelBtn');
const refreshBtn = document.getElementById('refreshBtn');
const productBtn = document.getElementById('productBtn');
const catalogBtn = document.getElementById('catalogBtn');

function pretty(value) {
  return JSON.stringify(value, null, 2);
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

function renderTable(target, rows, columns, emptyText) {
  if (!rows.length) {
    target.innerHTML = `<div class="empty">${emptyText}</div>`;
    return;
  }
  const head = columns.map((column) => `<th>${column.label}</th>`).join('');
  const body = rows.map((row) => `<tr>${columns.map((column) => `<td>${column.render(row)}</td>`).join('')}</tr>`).join('');
  target.innerHTML = `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

function renderOrders() {
  renderTable(
    ordersTable,
    state.orders,
    [
      { label: 'ID', render: (o) => o.id },
      { label: 'Symbol', render: (o) => o.symbol },
      { label: 'Side', render: (o) => `<span class="pill">${o.side}</span>` },
      { label: 'Price', render: (o) => o.price },
      { label: '成交/总量', render: (o) => `${o.filledQuantity}/${o.originalQuantity}` },
      { label: '状态', render: (o) => o.status }
    ],
    '暂无订单'
  );
}

function renderTrades() {
  renderTable(
    tradesTable,
    state.trades,
    [
      { label: 'ID', render: (t) => t.id },
      { label: 'Symbol', render: (t) => t.symbol },
      { label: 'Price', render: (t) => t.price },
      { label: 'Qty', render: (t) => t.quantity },
      { label: 'Buy', render: (t) => t.buyOrderId },
      { label: 'Sell', render: (t) => t.sellOrderId }
    ],
    '暂无成交'
  );
}

async function refreshAll() {
  const [health, orders, trades] = await Promise.all([
    request('/api/health'),
    request('/api/orders'),
    request('/api/trades')
  ]);
  state.orders = orders.items || [];
  state.trades = trades.items || [];
  renderOrders();
  renderTrades();
  serverStatus.textContent = health.status === 'ok' ? '在线' : '异常';
  systemResult.textContent = pretty({
    message: '接口已连通',
    orders: state.orders.length,
    trades: state.trades.length
  });
}

orderForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = new FormData(orderForm);
  const payload = {
    symbol: form.get('symbol'),
    side: form.get('side'),
    price: Number(form.get('price')),
    quantity: Number(form.get('quantity'))
  };
  try {
    const result = await request('/api/orders', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    orderResult.textContent = pretty(result);
    orderIdInput.value = result.order.id;
    await refreshAll();
  } catch (error) {
    orderResult.textContent = pretty({ error: error.message });
  }
});

cancelBtn.addEventListener('click', async () => {
  const orderId = orderIdInput.value.trim();
  if (!orderId) {
    queryResult.textContent = pretty({ error: '请输入订单 ID' });
    return;
  }
  try {
    const result = await request(`/api/orders/${orderId}/cancel`, {
      method: 'POST',
      body: '{}'
    });
    queryResult.textContent = pretty(result);
    await refreshAll();
  } catch (error) {
    queryResult.textContent = pretty({ error: error.message });
  }
});

refreshBtn.addEventListener('click', async () => {
  try {
    await refreshAll();
    queryResult.textContent = pretty({ message: '已刷新' });
  } catch (error) {
    queryResult.textContent = pretty({ error: error.message });
  }
});

productBtn.addEventListener('click', async () => {
  const id = productIdInput.value.trim();
  try {
    const result = await request(`/api/products/${id}`);
    productResult.textContent = pretty(result);
  } catch (error) {
    productResult.textContent = pretty({ error: error.message });
  }
});

catalogBtn.addEventListener('click', async () => {
  try {
    const result = await request('/api/admin/catalog/change?merchantId=1', {
      method: 'POST'
    });
    productResult.textContent = pretty(result);
    systemResult.textContent = pretty({
      message: '已触发模拟变更，缓存会被异步淘汰'
    });
  } catch (error) {
    productResult.textContent = pretty({ error: error.message });
  }
});

refreshAll().catch((error) => {
  serverStatus.textContent = '离线';
  queryResult.textContent = pretty({ error: error.message });
});
