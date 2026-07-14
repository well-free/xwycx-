const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({ data: { product: null, stock: 0 }, onLoad() { const product = wx.getStorageSync('xwycx.adminProduct'); this.setData({ product, stock: product ? product.stock : 0 }) }, onShow() { requireAdmin((session.get() || {}).user) }, input(event) { this.setData({ stock: Number(event.detail.value) }) }, async save() { await admin.adjustStock(this.data.product.id, this.data.stock); wx.navigateBack() } })
