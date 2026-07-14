const orders = require('../../services/orders')
Page({
  data: { amount: '', reason: '', submitting: false },
  onLoad(options) { this.id = options.id; this.setData({ amount: options.amount || '' }) },
  amountInput(event) { this.setData({ amount: event.detail.value }) }, reasonInput(event) { this.setData({ reason: event.detail.value }) },
  async submit() { if (!this.data.reason.trim()) return wx.showToast({ title: '请填写退款原因', icon: 'none' }); this.setData({ submitting: true }); try { await orders.refund(this.id, { amount: this.data.amount, reason: this.data.reason }); wx.redirectTo({ url: `/pages/order-detail/index?id=${this.id}` }) } catch (error) { wx.showToast({ title: error.message, icon: 'none' }) } finally { this.setData({ submitting: false }) } }
})
