const request = require('./request')
module.exports = {
  products: () => request('/api/products'),
  createProduct: data => request('/api/admin/products', { method: 'POST', data }),
  updateProduct: (id, data) => request(`/api/admin/products/${id}`, { method: 'PUT', data }),
  adjustStock: (id, stock) => request(`/api/admin/products/${id}/stock?stock=${stock}`, { method: 'POST' }),
  orders: () => request('/api/admin/orders'),
  ship: (id, data) => request(`/api/admin/orders/${id}/ship`, { method: 'POST', data }),
  refunds: () => request('/api/admin/refunds'),
  approveRefund: id => request(`/api/admin/refunds/${id}/approve`, { method: 'POST' }),
  payments: () => request('/api/admin/payments')
}
