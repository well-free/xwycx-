const state = {
  orders: [],
  trades: [],
  payments: [],
  selectedPaymentId: null,
  symbol: 'MASK-50',
  side: 'BUY',
  loading: false
};

const els = {
  orderForm: document.getElementById('orderForm'),
  orderResult: document.getElementById('orderResult'),
  orderIdInput: document.getElementById('orderIdInput'),
  productIdInput: document.getElementById('productIdInput'),
  hotScoreInput: document.getElementById('hotScoreInput'),
  productResult: document.getElementById('productResult'),
  ordersTable: document.getElementById('ordersTable'),
  tradesTable: document.getElementById('tradesTable'),
  paymentsTable: document.getElementById('paymentsTable'),
  serverStatus: document.getElementById('serverStatus'),
  cancelBtn: document.getElementById('cancelBtn'),
  refreshBtn: document.getElementById('refreshBtn'),
  productBtn: document.getElementById('productBtn'),
  hotScoreBtn: document.getElementById('hotScoreBtn'),
  catalogBtn: document.getElementById('catalogBtn'),
  submitOrderBtn: document.getElementById('submitOrderBtn'),
  paymentOrderIdInput: document.getElementById('paymentOrderIdInput'),
  paymentChannelInput: document.getElementById('paymentChannelInput'),
  paymentCreateBtn: document.getElementById('paymentCreateBtn'),
  paymentSuccessBtn: document.getElementById('paymentSuccessBtn'),
  paymentRefundBtn: document.getElementById('paymentRefundBtn'),
  paymentResult: document.getElementById('paymentResult'),
  metricOrders: document.getElementById('metricOrders'),
  metricOpen: document.getElementById('metricOpen'),
  metricTrades: document.getElementById('metricTrades'),
  metricVolume: document.getElementById('metricVolume'),
  metricLastPrice: document.getElementById('metricLastPrice'),
  metricLastSymbol: document.getElementById('metricLastSymbol'),
  metricSymbol: document.getElementById('metricSymbol'),
  metricSpread: document.getElementById('metricSpread'),
  metricPayments: document.getElementById('metricPayments'),
  metricPaid: document.getElementById('metricPaid'),
  bookSymbol: document.getElementById('bookSymbol'),
  asksBook: document.getElementById('asksBook'),
  bidsBook: document.getElementById('bidsBook'),
  tradeTape: document.getElementById('tradeTape'),
  lastTradeText: document.getElementById('lastTradeText'),
  toast: document.getElementById('toast')
};

const openStatuses = new Set(['NEW', 'PARTIALLY_FILLED']);
const statusLabels = {
  NEW: '待撮合',
  PARTIALLY_FILLED: '部分成交',
  FILLED: '已成交',
  CANCELED: '已撤销',
  TIMEOUT_CLOSED: '超时关闭'
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
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
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
  els.toast.textContent = message;
  els.toast.dataset.tone = tone;
  els.toast.classList.add('show');
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => els.toast.classList.remove('show'), 2400);
}

function setStatus(online) {
  els.serverStatus.classList.toggle('offline', !online);
  els.serverStatus.innerHTML = `<span class="health-dot"></span>${online ? '服务在线' : '服务离线'}`;
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

function statusBadge(status) {
  return `<span class="status-badge ${escapeHtml(status)}">${statusLabels[status] || status}</span>`;
}

function paymentBadge(status) {
  return `<span class="payment-badge ${escapeHtml(status)}">${paymentStatusLabels[status] || status}</span>`;
}

function sideBadge(side) {
  return `<span class="side-badge ${side === 'BUY' ? 'buy' : 'sell'}">${side === 'BUY' ? '买入' : '卖出'}</span>`;
}

function progressBar(order) {
  const total = Number(order.originalQuantity) || 0;
  const filled = Number(order.filledQuantity) || 0;
  const pct = total > 0 ? Math.round((filled / total) * 100) : 0;
  return `
    <div class="fill-cell">
      <div class="fill-track"><span style="width:${Math.min(100, pct)}%"></span></div>
      <small>${filled}/${total}</small>
    </div>
  `;
}

function renderTable(target, rows, columns, emptyText) {
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

function renderOrders() {
  const rows = [...state.orders].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  renderTable(
    els.ordersTable,
    rows,
    [
      { label: '订单', render: (o) => `<button class="link-button" data-select-order="${o.id}">${o.id}</button>` },
      { label: 'SKU', render: (o) => escapeHtml(o.symbol) },
      { label: '方向', render: (o) => sideBadge(o.side) },
      { label: '价格', render: (o) => formatNumber(o.price) },
      { label: '成交进度', render: progressBar },
      { label: '状态', render: (o) => statusBadge(o.status) },
      { label: '更新时间', render: (o) => formatTime(o.updatedAt) },
      {
        label: '',
        render: (o) => openStatuses.has(o.status)
          ? `<button class="table-action" data-cancel-order="${o.id}">撤单</button>`
          : '<span class="muted-text">--</span>'
      }
    ],
    '暂无订单，提交一笔买单或卖单后会显示在这里'
  );
}

function renderTrades() {
  const rows = [...state.trades].sort((a, b) => new Date(b.executedAt) - new Date(a.executedAt));
  renderTable(
    els.tradesTable,
    rows,
    [
      { label: '成交', render: (t) => escapeHtml(t.id) },
      { label: 'SKU', render: (t) => escapeHtml(t.symbol) },
      { label: '价格', render: (t) => formatNumber(t.price) },
      { label: '数量', render: (t) => formatNumber(t.quantity) },
      { label: '时间', render: (t) => formatTime(t.executedAt) }
    ],
    '暂无成交记录'
  );

  const latest = rows[0];
  els.lastTradeText.textContent = latest ? `${latest.symbol} ${formatNumber(latest.price)}` : '暂无';
  els.tradeTape.innerHTML = rows.slice(0, 6).map((trade) => `
    <div class="tape-row">
      <span>${escapeHtml(trade.symbol)}</span>
      <strong>${formatNumber(trade.price)}</strong>
      <span>${formatNumber(trade.quantity)} 包/箱</span>
      <small>${formatTime(trade.executedAt)}</small>
    </div>
  `).join('') || '<div class="empty-state small">等待撮合成交</div>';
}

function renderPayments() {
  const rows = [...state.payments].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  renderTable(
    els.paymentsTable,
    rows,
    [
      { label: '支付单', render: (p) => `<button class="link-button" data-select-payment="${p.id}">${p.id}</button>` },
      { label: '订单', render: (p) => escapeHtml(p.orderId) },
      { label: '渠道', render: (p) => p.channel === 'ALIPAY' ? '支付宝' : '微信' },
      { label: '金额', render: (p) => `¥${formatNumber(p.amount)}` },
      { label: '状态', render: (p) => paymentBadge(p.status) },
      { label: '过期时间', render: (p) => formatTime(p.expireAt) }
    ],
    '暂无支付记录'
  );
}

function aggregateBook(side) {
  const map = new Map();
  state.orders
    .filter((order) => order.symbol === state.symbol && order.side === side && openStatuses.has(order.status))
    .forEach((order) => {
      const price = Number(order.price);
      const quantity = Number(order.remainingQuantity);
      map.set(price, (map.get(price) || 0) + quantity);
    });
  return [...map.entries()]
    .map(([price, quantity]) => ({ price, quantity }))
    .sort((a, b) => side === 'BUY' ? b.price - a.price : a.price - b.price)
    .slice(0, 5);
}

function renderBookList(target, rows, side) {
  if (!rows.length) {
    target.innerHTML = '<div class="empty-state small">暂无挂单</div>';
    return;
  }
  const maxQuantity = Math.max(...rows.map((row) => row.quantity), 1);
  target.innerHTML = rows.map((row) => {
    const width = Math.max(8, Math.round((row.quantity / maxQuantity) * 100));
    return `
      <div class="book-row ${side.toLowerCase()}">
        <span class="depth" style="width:${width}%"></span>
        <strong>${formatNumber(row.price)}</strong>
        <span>${formatNumber(row.quantity)} 包/箱</span>
      </div>
    `;
  }).join('');
}

function renderMetrics() {
  const openOrders = state.orders.filter((order) => openStatuses.has(order.status));
  const totalVolume = state.trades.reduce((sum, trade) => sum + Number(trade.quantity || 0), 0);
  const latestTrade = [...state.trades].sort((a, b) => new Date(b.executedAt) - new Date(a.executedAt))[0];
  const bids = aggregateBook('BUY');
  const asks = aggregateBook('SELL');
  const bestBid = bids[0]?.price;
  const bestAsk = asks[0]?.price;
  const paidCount = state.payments.filter((payment) => payment.status === 'SUCCESS' || payment.status === 'REFUNDED').length;

  els.metricOrders.textContent = state.orders.length;
  els.metricOpen.textContent = `${openOrders.length} 个可撮合订单`;
  els.metricTrades.textContent = state.trades.length;
  els.metricVolume.textContent = `累计 ${formatNumber(totalVolume)} 包/箱`;
  els.metricLastPrice.textContent = latestTrade ? formatNumber(latestTrade.price) : '--';
  els.metricLastSymbol.textContent = latestTrade ? `${latestTrade.symbol} · ${formatTime(latestTrade.executedAt)}` : '暂无成交';
  els.metricSymbol.textContent = state.symbol;
  els.metricSpread.textContent = bestBid && bestAsk ? `买 ${formatNumber(bestBid)} / 卖 ${formatNumber(bestAsk)}` : '等待买卖盘';
  els.metricPayments.textContent = state.payments.length;
  els.metricPaid.textContent = `${paidCount} 笔已支付`;
  els.bookSymbol.textContent = state.symbol;
}

function renderAll() {
  renderMetrics();
  renderBookList(els.asksBook, aggregateBook('SELL'), 'SELL');
  renderBookList(els.bidsBook, aggregateBook('BUY'), 'BUY');
  renderOrders();
  renderTrades();
  renderPayments();
}

async function refreshAll(silent = false) {
  if (state.loading) {
    return;
  }
  state.loading = true;
  els.refreshBtn.classList.add('spinning');
  try {
    const [health, orders, trades, payments] = await Promise.all([
      request('/api/health'),
      request('/api/orders'),
      request('/api/trades'),
      request('/api/payments')
    ]);
    state.orders = orders.items || [];
    state.trades = trades.items || [];
    state.payments = payments.items || [];
    setStatus(health.status === 'ok');
    renderAll();
    if (!silent) {
      showToast('数据已刷新');
    }
  } catch (error) {
    setStatus(false);
    showToast(error.message, 'error');
  } finally {
    state.loading = false;
    els.refreshBtn.classList.remove('spinning');
  }
}

function setSide(side) {
  state.side = side;
  els.orderForm.elements.side.value = side;
  document.querySelectorAll('.side-switch').forEach((button) => {
    button.classList.toggle('active', button.dataset.side === side);
  });
  els.submitOrderBtn.textContent = side === 'BUY' ? '提交买单' : '提交卖单';
  els.submitOrderBtn.classList.toggle('sell-mode', side === 'SELL');
}

async function cancelOrder(orderId) {
  const id = String(orderId || '').trim();
  if (!id) {
    showToast('请输入订单 ID', 'error');
    return;
  }
  try {
    const result = await request(`/api/orders/${id}/cancel`, {
      method: 'POST',
      body: '{}'
    });
    els.orderResult.textContent = `订单 ${result.id} 已撤销`;
    els.orderIdInput.value = result.id;
    showToast('撤单成功');
    await refreshAll(true);
  } catch (error) {
    showToast(error.message, 'error');
  }
}

els.orderForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = new FormData(els.orderForm);
  const payload = {
    symbol: String(form.get('symbol') || '').trim().toUpperCase(),
    side: form.get('side'),
    price: Number(form.get('price')),
    quantity: Number(form.get('quantity'))
  };
  state.symbol = payload.symbol || state.symbol;
  try {
    const result = await request('/api/orders', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    const tradesText = result.trades?.length ? `，生成 ${result.trades.length} 笔成交` : '，进入订单簿';
    els.orderResult.textContent = `订单 ${result.order.id} 已提交${tradesText}`;
    els.orderIdInput.value = result.order.id;
    els.paymentOrderIdInput.value = result.order.id;
    showToast('下单成功');
    await refreshAll(true);
  } catch (error) {
    els.orderResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

document.querySelectorAll('.side-switch').forEach((button) => {
  button.addEventListener('click', () => setSide(button.dataset.side));
});

document.querySelectorAll('[data-price]').forEach((button) => {
  button.addEventListener('click', () => {
    els.orderForm.elements.price.value = button.dataset.price;
  });
});

els.orderForm.elements.symbol.addEventListener('input', (event) => {
  const value = event.target.value.trim().toUpperCase();
  if (value) {
    state.symbol = value;
    renderMetrics();
    renderBookList(els.asksBook, aggregateBook('SELL'), 'SELL');
    renderBookList(els.bidsBook, aggregateBook('BUY'), 'BUY');
  }
});

els.ordersTable.addEventListener('click', (event) => {
  const selectId = event.target.dataset.selectOrder;
  const cancelId = event.target.dataset.cancelOrder;
  if (selectId) {
    els.orderIdInput.value = selectId;
    els.paymentOrderIdInput.value = selectId;
    showToast(`已选中订单 ${selectId}`);
  }
  if (cancelId) {
    cancelOrder(cancelId);
  }
});

els.paymentsTable.addEventListener('click', (event) => {
  const paymentId = event.target.dataset.selectPayment;
  if (!paymentId) {
    return;
  }
  const payment = state.payments.find((item) => String(item.id) === String(paymentId));
  state.selectedPaymentId = paymentId;
  if (payment) {
    els.paymentOrderIdInput.value = payment.orderId;
    els.paymentChannelInput.value = payment.channel;
    els.paymentResult.innerHTML = `
      <strong>已选中支付单 ${escapeHtml(payment.id)}</strong>
      <span>${payment.channel === 'ALIPAY' ? '支付宝' : '微信支付'} · ¥${formatNumber(payment.amount)} · ${paymentStatusLabels[payment.status] || payment.status}</span>
    `;
  }
});

els.cancelBtn.addEventListener('click', () => cancelOrder(els.orderIdInput.value));
els.refreshBtn.addEventListener('click', () => refreshAll());

els.productBtn.addEventListener('click', async () => {
  const id = els.productIdInput.value.trim();
  try {
    const product = await request(`/api/products/${id}`);
    els.productResult.innerHTML = `
      <strong>${escapeHtml(product.name)}</strong>
      <span>¥${formatNumber(product.price)} · 库存 ${formatNumber(product.stock)} · 热度 ${product.hotScore}</span>
    `;
    showToast('商品已加载');
  } catch (error) {
    els.productResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.hotScoreBtn.addEventListener('click', async () => {
  const id = els.productIdInput.value.trim();
  const score = els.hotScoreInput.value.trim();
  try {
    const product = await request(`/api/products/${id}/hot-score?score=${encodeURIComponent(score)}`, {
      method: 'POST'
    });
    els.productResult.innerHTML = `
      <strong>${escapeHtml(product.name)}</strong>
      <span>热度已更新为 ${product.hotScore}</span>
    `;
    showToast('热度已更新');
  } catch (error) {
    els.productResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.catalogBtn.addEventListener('click', async () => {
  try {
    const result = await request('/api/admin/catalog/change?merchantId=1', { method: 'POST' });
    els.productResult.innerHTML = `
      <strong>缓存淘汰已触发</strong>
      <span>商家 ${result.merchantId} 的目录变更已受理</span>
    `;
    showToast('模拟变更已发送');
  } catch (error) {
    els.productResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.paymentCreateBtn.addEventListener('click', async () => {
  const orderId = els.paymentOrderIdInput.value.trim();
  const channel = els.paymentChannelInput.value;
  if (!orderId) {
    showToast('请先选择或输入订单 ID', 'error');
    return;
  }
  try {
    const payment = await request('/api/payments', {
      method: 'POST',
      body: JSON.stringify({ orderId: Number(orderId), channel })
    });
    state.selectedPaymentId = payment.id;
    els.paymentResult.innerHTML = `
      <strong>支付单 ${escapeHtml(payment.id)} 已创建</strong>
      <span>${payment.channel === 'ALIPAY' ? '支付宝' : '微信支付'} · ¥${formatNumber(payment.amount)}</span>
      <span>${escapeHtml(payment.qrCode)}</span>
    `;
    showToast('支付单已创建');
    await refreshAll(true);
  } catch (error) {
    els.paymentResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.paymentSuccessBtn.addEventListener('click', async () => {
  const paymentId = state.selectedPaymentId || state.payments.find((payment) => String(payment.orderId) === els.paymentOrderIdInput.value.trim())?.id;
  const payment = state.payments.find((item) => String(item.id) === String(paymentId));
  if (!payment) {
    showToast('请先创建或选择支付单', 'error');
    return;
  }
  try {
    const result = await request(`/api/payments/callbacks/${payment.channel}`, {
      method: 'POST',
      body: JSON.stringify({
        paymentId: payment.id,
        notifyId: `ui-${Date.now()}`,
        channelTradeNo: `MOCK-${payment.channel}-${payment.id}`,
        amount: payment.amount,
        status: 'SUCCESS',
        signature: 'mock-signature'
      })
    });
    els.paymentResult.innerHTML = `
      <strong>支付回调已处理</strong>
      <span>支付单 ${escapeHtml(result.id)} 状态：${paymentStatusLabels[result.status] || result.status}</span>
    `;
    showToast('支付成功回调已模拟');
    await refreshAll(true);
  } catch (error) {
    els.paymentResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

els.paymentRefundBtn.addEventListener('click', async () => {
  const payment = state.payments.find((item) => String(item.id) === String(state.selectedPaymentId));
  if (!payment) {
    showToast('请先选择支付单', 'error');
    return;
  }
  try {
    const refund = await request(`/api/payments/${payment.id}/refunds`, {
      method: 'POST',
      body: JSON.stringify({ amount: payment.amount, reason: '前端模拟退款' })
    });
    els.paymentResult.innerHTML = `
      <strong>退款成功</strong>
      <span>退款单 ${escapeHtml(refund.id)} · ¥${formatNumber(refund.amount)}</span>
    `;
    showToast('退款成功');
    await refreshAll(true);
  } catch (error) {
    els.paymentResult.textContent = error.message;
    showToast(error.message, 'error');
  }
});

setSide('BUY');
refreshAll(true);
