const orders = require('../../services/orders')
const payments = require('../../services/payments')
Page({
  data: { order: null, paying: false },
  onLoad(options) { this.id = options.id; this.load() }, onShow() { if (this.id) this.load() },
  async load() { this.setData({ order: await orders.get(this.id) }) },
  async pay() { this.setData({ paying: true }); try { await payments.pay(this.id, 'WECHAT'); await this.load() } catch (error) { wx.showToast({ title: error.message, icon: 'none' }) } finally { this.setData({ paying: false }) } },
  async cancel() { await orders.cancel(this.id); await this.load() },
  refund() { wx.navigateTo({ url: `/pages/refund/index?id=${this.id}&amount=${this.data.order.totalAmount}` }) }
})
