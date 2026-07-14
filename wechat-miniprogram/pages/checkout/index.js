const cart = require('../../services/cart')
const addresses = require('../../services/addresses')
const orders = require('../../services/orders')
const payments = require('../../services/payments')

Page({
  data: { items: [], address: null, total: '0.00', remark: '', channel: 'WECHAT', submitting: false, error: '' },
  onShow() { this.load() },
  async load() {
    try {
      const [cartResult, addressResult] = await Promise.all([cart.list(), addresses.list()])
      const selected = wx.getStorageSync('xwycx.selectedAddress')
      const allAddresses = addressResult.items || []
      const address = allAddresses.find(item => selected && item.id === selected.id)
        || allAddresses.find(item => item.defaultAddress) || allAddresses[0] || null
      const items = (cartResult.items || []).filter(item => item.available)
      this.setData({ items, address, total: items.reduce((sum, item) => sum + Number(item.currentPrice) * item.quantity, 0).toFixed(2) })
    } catch (error) { this.setData({ error: error.message }) }
  },
  chooseAddress() { wx.navigateTo({ url: '/pages/address-list/index?select=1' }) },
  remarkInput(event) { this.setData({ remark: event.detail.value }) },
  chooseChannel(event) { this.setData({ channel: event.currentTarget.dataset.channel }) },
  async submit() {
    if (this.data.submitting) return
    if (!this.data.address) return wx.showToast({ title: '请先添加配送地址', icon: 'none' })
    if (!this.data.items.length) return wx.showToast({ title: '采购车为空', icon: 'none' })
    this.setData({ submitting: true, error: '' })
    try {
      const order = await orders.create({
        items: this.data.items.map(item => ({ productId: item.productId, quantity: item.quantity })),
        addressId: this.data.address.id,
        remark: this.data.remark
      })
      await payments.pay(order.id, this.data.channel)
      wx.redirectTo({ url: `/pages/order-detail/index?id=${order.id}` })
    } catch (error) { this.setData({ error: error.message }); wx.showToast({ title: error.message, icon: 'none' }) }
    finally { this.setData({ submitting: false }) }
  }
})
