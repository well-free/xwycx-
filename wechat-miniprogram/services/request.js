const env = require('../config/env')
const session = require('../store/session')

function request(path, options = {}) {
  const current = session.get()
  const header = Object.assign({ 'content-type': 'application/json' }, options.header || {})
  if (current && current.token) header['X-Session-Token'] = current.token
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${env.getBaseUrl()}${path}`,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: options.timeout || 10000,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          redirectingToLogin = false
          resolve(response.data)
          return
        }
        const message = errorMessage(response.data, response.statusCode)
        if (response.statusCode === 401) {
          session.clear()
          redirectToLogin()
        }
        reject(new Error(message))
      },
      fail(error) {
        if (error && error.statusCode === 401) session.clear()
        reject(new Error(networkErrorMessage(error)))
      }
    })
  })
}

let redirectingToLogin = false

function errorMessage(data, statusCode) {
  if (data && typeof data === 'object') return data.error || data.message || `request failed (${statusCode})`
  if (typeof data === 'string' && data.trim()) return data
  return `request failed (${statusCode})`
}

function networkErrorMessage(error) {
  const message = error && error.errMsg ? error.errMsg : ''
  if (/timeout/i.test(message)) return '请求超时，请稍后重试'
  if (/domain list|url not in domain/i.test(message)) return '当前接口域名未加入小程序合法域名'
  return message || '网络不可用，请检查连接'
}

function redirectToLogin() {
  if (redirectingToLogin || typeof wx.reLaunch !== 'function') return
  redirectingToLogin = true
  let redirect = '/pages/home/index'
  if (typeof getCurrentPages === 'function') {
    const pages = getCurrentPages()
    const current = pages[pages.length - 1]
    if (current && current.route && current.route !== 'pages/login/index') redirect = `/${current.route}`
  }
  wx.reLaunch({ url: `/pages/login/index?redirect=${encodeURIComponent(redirect)}` })
}

module.exports = request
