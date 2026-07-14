const products = require('../../services/products')
const cart = require('../../services/cart')

Page({
  data: { items: [], keyword: '', loading: true, error: '' },
  onShow() { this.load() },
  async load() {
    this.setData({ loading: true, error: '' })
    try { const result = await products.list(); this.all = result.items || []; this.filter() }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ loading: false }) }
  },
  search(event) { this.setData({ keyword: event.detail.value }); this.filter() },
  filter() {
    const key = this.data.keyword.trim().toLowerCase()
    this.setData({ items: key ? this.all.filter(item => `${item.name}${item.sku}`.toLowerCase().includes(key)) : this.all })
  },
  detail(event) { wx.navigateTo({ url: `/pages/product-detail/index?id=${event.detail.id}` }) },
  async add(event) {
    try { await cart.add(event.detail.id, 1); wx.showToast({ title: '已加入采购车' }) }
    catch (error) { wx.showToast({ title: error.message, icon: 'none' }) }
  },
  checkout() { wx.switchTab({ url: '/pages/cart/index' }) }
})
