const request = require('./request')
const platform = require('../platform/payment')

async function create(orderId, channel = 'WECHAT') {
  return request(`/api/customer-orders/${orderId}/payments`, { method: 'POST', data: { channel } })
}

async function pay(orderId, channel = 'WECHAT') {
  const payment = await create(orderId, channel)
  if (payment.gatewayMode === 'mock' || payment.gatewayMode === 'sandbox') {
    await request(`/api/payments/${payment.id}/simulate-success`, { method: 'POST' })
    return payment
  }
  if (!payment.miniProgram) throw new Error('missing mini program payment parameters')
  await platform.requestPayment(payment.miniProgram)
  return payment
}

module.exports = { create, pay }
