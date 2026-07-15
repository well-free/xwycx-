const test = require('node:test')
const assert = require('node:assert/strict')

function fresh(modulePath) {
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

test('phone binding calls backend and replaces the wechat-only session', async () => {
  const storage = new Map()
  storage.set('xwycx.session', { token: 'wechat-token', user: { id: 1, phone: '', role: 'CUSTOMER' } })
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    request: options => {
      assert.equal(options.url, 'http://localhost:8080/api/auth/wechat/bind-phone')
      assert.equal(options.method, 'POST')
      assert.equal(options.header['X-Session-Token'], 'wechat-token')
      assert.deepEqual(options.data, { phone: '13800000001', code: '123456' })
      options.success({
        statusCode: 200,
        data: { token: 'bound-token', user: { id: 2, phone: '13800000001', role: 'CUSTOMER' } }
      })
    }
  }
  fresh('../store/session')
  fresh('../services/request')
  const auth = fresh('../services/auth')

  const result = await auth.bindPhone('13800000001', '123456')

  assert.equal(result.token, 'bound-token')
  assert.equal(storage.get('xwycx.session').user.phone, '13800000001')
})
