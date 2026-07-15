const test = require('node:test')
const assert = require('node:assert/strict')

function fresh() {
  delete require.cache[require.resolve('../config/env')]
  return require('../config/env')
}

test('release and trial builds always use the https production api', () => {
  for (const envVersion of ['trial', 'release']) {
    global.wx = {
      getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
      getStorageSync: () => 'http://192.168.1.20:8080'
    }
    assert.equal(fresh().getBaseUrl(), 'https://xwycx.xyz')
  }
})

test('develop build accepts a local api override for real-device debugging', () => {
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getStorageSync: key => key === 'xwycx.apiBaseUrl' ? 'http://192.168.1.20:8080/' : ''
  }
  assert.equal(fresh().getBaseUrl(), 'http://192.168.1.20:8080')
})
