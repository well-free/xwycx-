const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
const empty = { sku: '', name: '', price: '', stock: 0, mainImage: '', detailImages: '', spec: '', unit: '件', status: 'ON_SHELF', sortOrder: 0 }
Page({
  data: { form: empty, saving: false },
  onLoad() { const product = wx.getStorageSync('xwycx.adminProduct'); if (product) { this.id = product.id; this.setData({ form: product }) } },
  onShow() { requireAdmin((session.get() || {}).user) },
  input(event) { this.setData({ [`form.${event.currentTarget.dataset.field}`]: event.detail.value }) },
  status(event) { this.setData({ 'form.status': event.detail.value ? 'ON_SHELF' : 'OFF_SHELF' }) },
  async save() { this.setData({ saving: true }); try { if (this.id) await admin.updateProduct(this.id, this.data.form); else await admin.createProduct(this.data.form); wx.navigateBack() } catch (error) { wx.showToast({ title: error.message, icon: 'none' }) } finally { this.setData({ saving: false }) } }
})
