const orders = require('../../services/orders')
Page({ data: { items: [], loading: true }, onShow() { this.load() }, async load() { const result = await orders.list(); this.setData({ items: result.items || [], loading: false }) }, detail(event) { wx.navigateTo({ url: `/pages/order-detail/index?id=${event.currentTarget.dataset.id}` }) } })
