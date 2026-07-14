const test = require('node:test')
const assert = require('node:assert/strict')

test('session persists and clears login state', () => {
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key)
  }
  delete require.cache[require.resolve('../store/session')]
  const session = require('../store/session')

  session.set({ token: 'token-2', user: { role: 'ADMIN' } })
  assert.equal(session.get().token, 'token-2')
  session.clear()
  assert.equal(session.get(), null)
})
