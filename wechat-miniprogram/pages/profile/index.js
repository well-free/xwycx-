const session = require('../../store/session')
const auth = require('../../services/auth')
const { menuFor } = require('../../store/navigation')
Page({
  data: { current: null, menu: [], avatar: '访' },
  onShow() { const current = session.get(); this.setData({ current, menu: menuFor(current && current.user), avatar: current ? current.user.role.substring(0, 1) : '访' }) },
  open(event) { wx.navigateTo({ url: event.currentTarget.dataset.url }) },
  login() { wx.navigateTo({ url: '/pages/login/index?redirect=/pages/profile/index' }) },
  async logout() { await auth.logout(); this.setData({ current: null, menu: [], avatar: '访' }) }
})
