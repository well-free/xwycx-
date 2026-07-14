const products = require('../../services/products')
const cart = require('../../services/cart')
Page({
  data: { item: null, quantity: 1, loading: true },
  onLoad(options) { this.id = options.id; this.load() },
  async load() { this.setData({ item: await products.get(this.id), loading: false }) },
  step(event) { this.setData({ quantity: Math.max(1, this.data.quantity + Number(event.currentTarget.dataset.delta)) }) },
  async add() { await cart.add(this.data.item.id, this.data.quantity); wx.showToast({ title: '已加入采购车' }) }
})
