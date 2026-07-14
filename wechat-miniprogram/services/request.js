const env = require('../config/env')
const session = require('../store/session')

function request(path, options = {}) {
  const current = session.get()
  const header = Object.assign({ 'content-type': 'application/json' }, options.header || {})
  if (current && current.token) header['X-Session-Token'] = current.token
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${env.baseUrl}${path}`,
      method: options.method || 'GET',
      data: options.data,
      header,
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data)
          return
        }
        const message = response.data && response.data.message
          ? response.data.message : `request failed (${response.statusCode})`
        if (response.statusCode === 401) {
          session.clear()
          if (wx.reLaunch) wx.reLaunch({ url: '/pages/login/index' })
        }
        reject(new Error(message))
      },
      fail(error) {
        if (error && error.statusCode === 401) session.clear()
        reject(new Error((error && error.errMsg) || 'network unavailable'))
      }
    })
  })
}

module.exports = request
