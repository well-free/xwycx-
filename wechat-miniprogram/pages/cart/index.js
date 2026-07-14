const cart = require('../../services/cart')
Page({
  data: { items: [], total: '0.00', loading: true },
  onShow() { this.load() },
  async load() { const result = await cart.list(); this.apply(result.items || []) },
  apply(items) {
    const normalized = items.map(item => Object.assign({}, item, { shortName: item.name.substring(0, 2) }))
    this.setData({ items: normalized, total: normalized.reduce((sum, item) => sum + Number(item.currentPrice) * item.quantity, 0).toFixed(2), loading: false })
  },
  async step(event) {
    const item = this.data.items[event.currentTarget.dataset.index]
    const quantity = Math.max(1, item.quantity + Number(event.currentTarget.dataset.delta))
    await cart.update(item.productId, quantity); await this.load()
  },
  async remove(event) { await cart.remove(event.currentTarget.dataset.id); await this.load() },
  checkout() { wx.navigateTo({ url: '/pages/checkout/index' }) }
})
