const addresses = require('../../services/addresses')
Page({
  data: { form: { receiverName: '', receiverPhone: '', province: '', city: '', district: '', detail: '', defaultAddress: false }, saving: false },
  onLoad() { const address = wx.getStorageSync('xwycx.editAddress'); if (address) this.setData({ form: address }); this.id = address && address.id },
  input(event) { this.setData({ [`form.${event.currentTarget.dataset.field}`]: event.detail.value }) },
  toggle(event) { this.setData({ 'form.defaultAddress': event.detail.value }) },
  async save() { this.setData({ saving: true }); try { if (this.id) await addresses.update(this.id, this.data.form); else await addresses.create(this.data.form); wx.navigateBack() } catch (error) { wx.showToast({ title: error.message, icon: 'none' }) } finally { this.setData({ saving: false }) } }
})
