export function formatNumber(value) {
  const number = Number(value);
  return Number.isFinite(number)
    ? number.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '--';
}

export function formatTime(value) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '--';
}

export function categoryFor(product) {
  const text = `${product.name || ''} ${product.sku || ''} ${product.spec || ''}`.toLowerCase();
  if (/(餐盒|饭盒|吸管|餐具|纸盘|cup|box)/.test(text)) return 'DINING';
  if (/(口罩|手套|清洁|消毒|抹布|垃圾袋|mask|glove)/.test(text)) return 'CLEANING';
  return 'OFFICE';
}
