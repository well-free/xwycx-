const labels = {
  PENDING_PAYMENT: '待支付', PAID: '已支付', FULFILLING: '备货中',
  SHIPPED: '已发货', COMPLETED: '已完成', CANCELED: '已取消',
  REFUNDING: '退款中', REFUNDED: '已退款'
}
Component({
  properties: { status: String },
  data: { label: '' },
  observers: { status(value) { this.setData({ label: labels[value] || value }) } }
})
