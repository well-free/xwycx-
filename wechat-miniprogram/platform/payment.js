function requestPayment(parameters) {
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: parameters.timeStamp,
      nonceStr: parameters.nonceStr,
      package: parameters.packageValue,
      signType: parameters.signType,
      paySign: parameters.paySign,
      success: resolve,
      fail: reject
    })
  })
}

module.exports = { requestPayment }
