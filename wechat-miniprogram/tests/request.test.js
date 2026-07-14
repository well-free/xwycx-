const test = require('node:test')
const assert = require('node:assert/strict')

function fresh(modulePath) {
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

test('request injects token and clears expired session on 401', async () => {
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    reLaunch: () => {},
    request: options => {
      assert.equal(options.header['X-Session-Token'], 'token-1')
      options.success({ statusCode: 401, data: { message: 'unauthorized' } })
    }
  }
  const session = fresh('../store/session')
  session.set({ token: 'token-1', user: { role: 'CUSTOMER' } })
  const request = fresh('../services/request')

  await assert.rejects(() => request('/api/auth/me'), /unauthorized/)
  assert.equal(session.get(), null)
})
