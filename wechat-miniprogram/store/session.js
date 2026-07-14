const KEY = 'xwycx.session'

function get() {
  return wx.getStorageSync(KEY) || null
}

function set(value) {
  wx.setStorageSync(KEY, value)
  return value
}

function clear() {
  wx.removeStorageSync(KEY)
}

module.exports = { get, set, clear }
