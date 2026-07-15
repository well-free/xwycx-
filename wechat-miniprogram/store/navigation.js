function menuFor(user) {
  const menu = [
    { label: '我的订单', icon: '单', url: '/pages/order-list/index' },
    { label: '配送地址', icon: '址', url: '/pages/address-list/index' }
  ]
  if (user && !user.phone) {
    menu.unshift({ label: '绑定手机号', icon: '绑', url: '/pages/bind-phone/index' })
  }
  if (user && user.role === 'ADMIN') {
    menu.push({ label: '运营工作台', icon: '营', url: '/pages/admin/home/index' })
  }
  return menu
}

function requireAdmin(user) {
  if (user && user.role === 'ADMIN') return true
  wx.switchTab({ url: '/pages/profile/index' })
  return false
}

module.exports = { menuFor, requireAdmin }
