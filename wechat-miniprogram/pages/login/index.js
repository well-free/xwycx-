const auth = require('../../services/auth')

Page({
  data: { mode: 'wechat', phone: '', code: '', sending: false, loading: false, error: '' },
  onLoad(options) { this.redirect = validRedirect(options.redirect) },
  setMode(event) { this.setData({ mode: event.currentTarget.dataset.mode, error: '' }) },
  phoneInput(event) { this.setData({ phone: event.detail.value }) },
  codeInput(event) { this.setData({ code: event.detail.value }) },
  async sendCode() {
    if (!/^1[3-9]\d{9}$/.test(this.data.phone)) return this.setData({ error: '请输入正确手机号' })
    this.setData({ sending: true, error: '' })
    try { await auth.sendSms(this.data.phone); wx.showToast({ title: '验证码已发送', icon: 'success' }) }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ sending: false }) }
  },
  async submit() {
    this.setData({ loading: true, error: '' })
    try {
      const current = this.data.mode === 'wechat'
        ? await auth.wechatLogin()
        : await auth.smsLogin(this.data.phone, this.data.code)
      if (this.data.mode === 'wechat' && current.user && !current.user.phone) {
        wx.redirectTo({ url: `/pages/bind-phone/index?redirect=${encodeURIComponent(this.redirect)}` })
        return
      }
      wx.reLaunch({ url: this.redirect })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ loading: false }) }
  }
})

function validRedirect(value) {
  return value && /^\/pages\/[a-z0-9-/]+\/index$/.test(value) ? value : '/pages/home/index'
}
