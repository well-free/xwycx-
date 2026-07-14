const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({ data: { items: [] }, onShow() { if (requireAdmin((session.get() || {}).user)) this.load() }, async load() { const result = await admin.refunds(); this.setData({ items: result.items || [] }) }, async approve(event) { await admin.approveRefund(event.currentTarget.dataset.id); wx.showToast({ title: '已审核' }); await this.load() } })
