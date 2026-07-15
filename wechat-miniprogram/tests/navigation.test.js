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
  assert.equal(menuFor({ role: 'CUSTOMER', phone: '13800000001' }).some(item => item.url.includes('/admin/')), false)
  assert.equal(menuFor({ role: 'ADMIN', phone: '13900000000' }).some(item => item.url === '/pages/admin/home/index'), true)
})

test('wechat-only account exposes phone binding entry', () => {
  const { menuFor } = require('../store/navigation')
  assert.equal(menuFor({ role: 'CUSTOMER', phone: '' })[0].url, '/pages/bind-phone/index')
  assert.equal(menuFor({ role: 'CUSTOMER', phone: '13800000001' }).some(item => item.url.includes('bind-phone')), false)
})
