const addresses = require('../../services/addresses')
Page({
  data: { items: [], selectMode: false },
  onLoad(options) { this.setData({ selectMode: options.select === '1' }) },
  onShow() { this.load() },
  async load() { const result = await addresses.list(); this.setData({ items: result.items || [] }) },
  choose(event) { if (!this.data.selectMode) return; wx.setStorageSync('xwycx.selectedAddress', this.data.items[event.currentTarget.dataset.index]); wx.navigateBack() },
  edit(event) { wx.setStorageSync('xwycx.editAddress', this.data.items[event.currentTarget.dataset.index]); wx.navigateTo({ url: '/pages/address-edit/index' }) },
  add() { wx.removeStorageSync('xwycx.editAddress'); wx.navigateTo({ url: '/pages/address-edit/index' }) },
  async remove(event) { await addresses.remove(event.currentTarget.dataset.id); await this.load() }
})
