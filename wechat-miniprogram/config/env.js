const DEVELOPMENT_OVERRIDE_KEY = 'xwycx.apiBaseUrl'

const baseUrls = {
  develop: 'http://localhost:8080',
  trial: 'https://xwycx.xyz',
  release: 'https://xwycx.xyz'
}

function envVersion() {
  if (typeof wx === 'undefined' || typeof wx.getAccountInfoSync !== 'function') return 'develop'
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion || 'develop'
  } catch (error) {
    return 'develop'
  }
}

function normalizeBaseUrl(value) {
  return String(value || '').trim().replace(/\/+$/, '')
}

function getBaseUrl() {
  const version = envVersion()
  if (version === 'develop' && typeof wx !== 'undefined' && typeof wx.getStorageSync === 'function') {
    const override = normalizeBaseUrl(wx.getStorageSync(DEVELOPMENT_OVERRIDE_KEY))
    if (override) return override
  }
  return baseUrls[version] || baseUrls.release
}

module.exports = {
  DEVELOPMENT_OVERRIDE_KEY,
  productionBaseUrl: baseUrls.release,
  envVersion,
  getBaseUrl
}
