const test = require('node:test')
const assert = require('node:assert/strict')
const appConfig = require('../app.json')

test('customer routes contain complete purchase flow', () => {
  assert.ok(appConfig.pages.includes('pages/checkout/index'))
  assert.ok(appConfig.pages.includes('pages/order-detail/index'))
  assert.ok(appConfig.pages.includes('pages/refund/index'))
})

test('customer menu excludes admin workbench', () => {
  const { menuFor } = require('../store/navigation')
  assert.equal(menuFor({ role: 'CUSTOMER' }).some(item => item.url.includes('/admin/')), false)
  assert.equal(menuFor({ role: 'ADMIN' }).some(item => item.url === '/pages/admin/home/index'), true)
})
