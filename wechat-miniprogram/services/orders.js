const request = require('./request')
module.exports = {
  list: () => request('/api/customer-orders'),
  get: id => request(`/api/customer-orders/${id}`),
  create: data => request('/api/customer-orders', { method: 'POST', data }),
  cancel: id => request(`/api/customer-orders/${id}/cancel`, { method: 'POST' }),
  refund: (id, data) => request(`/api/customer-orders/${id}/refunds`, { method: 'POST', data })
}
