const session = require('./store/session')

App({
  globalData: { session: null },
  onLaunch() {
    this.globalData.session = session.get()
  }
})
