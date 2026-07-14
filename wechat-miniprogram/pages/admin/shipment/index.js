const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({ data: { order: null, carrier: '', trackingNo: '' }, onLoad() { this.setData({ order: wx.getStorageSync('xwycx.adminOrder') }) }, onShow() { requireAdmin((session.get() || {}).user) }, input(event) { this.setData({ [event.currentTarget.dataset.field]: event.detail.value }) }, async ship() { if (!this.data.carrier || !this.data.trackingNo) return wx.showToast({ title: '请填写物流信息', icon: 'none' }); await admin.ship(this.data.order.id, { carrier: this.data.carrier, trackingNo: this.data.trackingNo }); wx.navigateBack() } })
