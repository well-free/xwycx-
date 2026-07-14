const test = require('node:test')
const assert = require('node:assert/strict')

test('admin guard redirects customers to profile', () => {
  let redirected = ''
  global.wx = { switchTab: ({ url }) => { redirected = url } }
  const { requireAdmin } = require('../store/navigation')

  assert.equal(requireAdmin({ role: 'CUSTOMER' }), false)
  assert.equal(redirected, '/pages/profile/index')
  assert.equal(requireAdmin({ role: 'ADMIN' }), true)
})
