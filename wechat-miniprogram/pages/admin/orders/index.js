const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({ data: { items: [], filter: 'ALL' }, onShow() { if (requireAdmin((session.get() || {}).user)) this.load() }, async load() { const result = await admin.orders(); this.all = result.items || []; this.filter() }, setFilter(event) { this.setData({ filter: event.currentTarget.dataset.value }); this.filter() }, filter() { this.setData({ items: this.data.filter === 'ALL' ? this.all : this.all.filter(item => item.status === this.data.filter) }) }, shipment(event) { wx.setStorageSync('xwycx.adminOrder', this.data.items[event.currentTarget.dataset.index]); wx.navigateTo({ url: '/pages/admin/shipment/index' }) } })
