const session = require('../../../store/session')
const { requireAdmin } = require('../../../store/navigation')
Page({
  data: { entries: [
    { label: '商品管理', note: '新增、上下架与编辑', url: '/pages/admin/products/index' },
    { label: '订单处理', note: '查询订单与录入发货', url: '/pages/admin/orders/index' },
    { label: '退款审核', note: '核对退款申请', url: '/pages/admin/refunds/index' },
    { label: '支付记录', note: '查看支付状态', url: '/pages/admin/payments/index' }
  ] },
  onShow() { requireAdmin((session.get() || {}).user) },
  open(event) { wx.navigateTo({ url: event.currentTarget.dataset.url }) },
  store() { wx.switchTab({ url: '/pages/home/index' }) }
})
