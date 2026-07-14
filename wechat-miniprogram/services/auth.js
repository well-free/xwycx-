const request = require('./request')
const session = require('../store/session')
const platform = require('../platform/login')

async function wechatLogin() {
  const result = await platform.login()
  return save(await request('/api/auth/wechat/login', { method: 'POST', data: { code: result.code } }))
}

function sendSms(phone) {
  return request('/api/auth/sms/send', { method: 'POST', data: { phone } })
}

async function smsLogin(phone, code) {
  return save(await request('/api/auth/sms/login', { method: 'POST', data: { phone, code } }))
}

async function restore() {
  const current = session.get()
  if (!current) return null
  const user = await request('/api/auth/me')
  return save({ token: current.token, user })
}

async function logout() {
  try { await request('/api/auth/logout', { method: 'POST' }) } finally { session.clear() }
}

function save(response) {
  return session.set({ token: response.token, user: response.user })
}

module.exports = { wechatLogin, sendSms, smsLogin, restore, logout }
