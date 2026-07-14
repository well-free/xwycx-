const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({
  data: { items: [] },
  onShow() { if (requireAdmin((session.get() || {}).user)) this.load() },
  async load() { const result = await admin.products(); this.setData({ items: result.items || [] }) },
  add() { wx.removeStorageSync('xwycx.adminProduct'); wx.navigateTo({ url: '/pages/admin/product-edit/index' }) },
  edit(event) { wx.setStorageSync('xwycx.adminProduct', this.data.items[event.currentTarget.dataset.index]); wx.navigateTo({ url: '/pages/admin/product-edit/index' }) },
  stock(event) { wx.setStorageSync('xwycx.adminProduct', this.data.items[event.currentTarget.dataset.index]); wx.navigateTo({ url: '/pages/admin/inventory/index' }) }
})
