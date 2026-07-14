const request = require('./request')
module.exports = {
  list: () => request('/api/addresses'),
  create: data => request('/api/addresses', { method: 'POST', data }),
  update: (id, data) => request(`/api/addresses/${id}`, { method: 'PUT', data }),
  remove: id => request(`/api/addresses/${id}`, { method: 'DELETE' })
}
