const products = require('../../services/products')
const cart = require('../../services/cart')
Page({
  data: { items: [], loading: true },
  onShow() { this.load() },
  async load() { const result = await products.list(); this.setData({ items: result.items || [], loading: false }) },
  detail(event) { wx.navigateTo({ url: `/pages/product-detail/index?id=${event.detail.id}` }) },
  async add(event) { await cart.add(event.detail.id, 1); wx.showToast({ title: '已加入采购车' }) }
})
