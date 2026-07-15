const auth = require('../../services/auth')
const session = require('../../store/session')

Page({
  data: { phone: '', code: '', sending: false, binding: false, error: '' },
  onLoad(options) { this.redirect = validRedirect(options.redirect) },
  onShow() {
    const current = session.get()
    if (!current) return wx.reLaunch({ url: '/pages/login/index' })
    if (current.user && current.user.phone) wx.reLaunch({ url: this.redirect })
  },
  phoneInput(event) { this.setData({ phone: event.detail.value }) },
  codeInput(event) { this.setData({ code: event.detail.value }) },
  async sendCode() {
    if (!/^1[3-9]\d{9}$/.test(this.data.phone)) return this.setData({ error: '请输入正确手机号' })
    this.setData({ sending: true, error: '' })
    try {
      await auth.sendSms(this.data.phone)
      wx.showToast({ title: '验证码已发送', icon: 'success' })
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ sending: false })
    }
  },
  async submit() {
    if (!/^1[3-9]\d{9}$/.test(this.data.phone) || !this.data.code) {
      return this.setData({ error: '请输入手机号和验证码' })
    }
    this.setData({ binding: true, error: '' })
    try {
      await auth.bindPhone(this.data.phone, this.data.code)
      wx.showToast({ title: '绑定成功', icon: 'success' })
      wx.reLaunch({ url: this.redirect })
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ binding: false })
    }
  },
  skip() { wx.reLaunch({ url: this.redirect }) }
})

function validRedirect(value) {
  const decoded = value ? decodeURIComponent(value) : ''
  return /^\/pages\/[a-z0-9-/]+\/index$/.test(decoded) ? decoded : '/pages/home/index'
}
