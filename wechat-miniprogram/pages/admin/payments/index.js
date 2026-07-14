const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
const admin = require('../../../services/admin')
Page({ data: { items: [] }, onShow() { if (requireAdmin((session.get() || {}).user)) this.load() }, async load() { const result = await admin.payments(); this.setData({ items: result.items || [] }) } })
