function menuFor(user) {
  const menu = [
    { label: '我的订单', url: '/pages/order-list/index' },
    { label: '配送地址', url: '/pages/address-list/index' }
  ]
  if (user && user.role === 'ADMIN') {
    menu.push({ label: '运营工作台', url: '/pages/admin/home/index' })
  }
  return menu
}

function requireAdmin(user) {
  if (user && user.role === 'ADMIN') return true
  wx.switchTab({ url: '/pages/profile/index' })
  return false
}

module.exports = { menuFor, requireAdmin }
