const request = require('./request')
module.exports = {
  list: () => request('/api/products'),
  get: id => request(`/api/products/${id}`)
}
