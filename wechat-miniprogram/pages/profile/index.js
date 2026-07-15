const session = require('../../store/session')
const auth = require('../../services/auth')
const { menuFor } = require('../../store/navigation')
Page({
  data: { current: null, menu: [], avatar: '访', accountLabel: '' },
  onShow() {
    const current = session.get()
    const user = current && current.user
    this.setData({
      current,
      menu: menuFor(user),
      avatar: current ? user.role.substring(0, 1) : '访',
      accountLabel: user ? (user.phone || '微信用户') : ''
    })
  },
  open(event) { wx.navigateTo({ url: event.currentTarget.dataset.url }) },
  login() { wx.navigateTo({ url: '/pages/login/index?redirect=/pages/profile/index' }) },
  async logout() { await auth.logout(); this.setData({ current: null, menu: [], avatar: '访', accountLabel: '' }) }
})
