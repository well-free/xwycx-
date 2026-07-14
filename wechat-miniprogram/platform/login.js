function login() {
  return new Promise((resolve, reject) => {
    wx.login({ success: resolve, fail: reject })
  })
}

module.exports = { login }
