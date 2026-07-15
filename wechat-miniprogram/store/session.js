const KEY = 'xwycx.session'

function get() {
  return wx.getStorageSync(KEY) || null
}

function set(value) {
  wx.setStorageSync(KEY, value)
  syncApp(value)
  return value
}

function clear() {
  wx.removeStorageSync(KEY)
  syncApp(null)
}

function syncApp(value) {
  if (typeof getApp !== 'function') return
  try {
    const app = getApp()
    if (app && app.globalData) app.globalData.session = value
  } catch (error) {
  }
}

module.exports = { get, set, clear }
