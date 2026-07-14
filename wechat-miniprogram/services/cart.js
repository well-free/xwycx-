const request = require('./request')
module.exports = {
  list: () => request('/api/cart'),
  add: (productId, quantity) => request('/api/cart/items', { method: 'POST', data: { productId, quantity } }),
  update: (productId, quantity) => request(`/api/cart/items/${productId}`, { method: 'PUT', data: { quantity } }),
  remove: productId => request(`/api/cart/items/${productId}`, { method: 'DELETE' })
}
