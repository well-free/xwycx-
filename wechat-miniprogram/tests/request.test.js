const test = require('node:test')
const assert = require('node:assert/strict')

function fresh(modulePath) {
  delete require.cache[require.resolve(modulePath)]
  return require(modulePath)
}

test('request injects token and clears expired session on 401', async () => {
  const storage = new Map()
  const redirects = []
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    reLaunch: options => redirects.push(options.url),
    request: options => {
      assert.equal(options.header['X-Session-Token'], 'token-1')
      assert.equal(options.url, 'http://localhost:8080/api/auth/me')
      assert.equal(options.timeout, 10000)
      options.success({ statusCode: 401, data: { error: 'login required' } })
    }
  }
  global.getCurrentPages = () => [{ route: 'pages/profile/index' }]
  const session = fresh('../store/session')
  session.set({ token: 'token-1', user: { role: 'CUSTOMER' } })
  const request = fresh('../services/request')

  await assert.rejects(() => request('/api/auth/me'), /login required/)
  assert.equal(session.get(), null)
  assert.equal(redirects.length, 1)
  assert.equal(redirects[0], '/pages/login/index?redirect=%2Fpages%2Fprofile%2Findex')
})

test('request reports a readable domain configuration error', async () => {
  global.wx = {
    getStorageSync: () => null,
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    request: options => {
      assert.equal(options.url, 'https://xwycx.xyz/api/products')
      options.fail({ errMsg: 'request:fail url not in domain list' })
    }
  }
  const request = fresh('../services/request')
  await assert.rejects(() => request('/api/products'), /接口域名未加入小程序合法域名/)
})
