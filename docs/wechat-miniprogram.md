# 微信原生小程序开发说明

## 导入项目

1. 启动本地 Spring Boot 服务：`mvn spring-boot:run`。
2. 打开微信开发者工具，导入仓库中的 `wechat-miniprogram/`。
3. 本地开发可使用测试 AppID，并在开发者工具中临时勾选“不校验合法域名、web-view、TLS 版本以及 HTTPS 证书”。该选项只能用于本地开发。
4. `develop` 版本默认请求 `http://localhost:8080`，`trial` 和 `release` 版本自动请求 `https://xwycx.xyz`。
5. 真机调试时，在开发者工具控制台设置电脑局域网地址后重新打开小程序：

```javascript
wx.setStorageSync('xwycx.apiBaseUrl', 'http://192.168.1.20:8080')
```

该覆盖只对 `develop` 生效，体验版和正式版不会读取本地覆盖地址。

## 本地登录与支付

- 手机验证码登录固定验证码：`123456`。
- 普通用户示例手机号：`13800000001`。
- 管理员示例手机号：`13900000000`。
- 微信登录调用 `wx.login`，本地 profile 使用模拟 code2session。
- 微信首次登录没有手机号时会进入绑定页面；绑定调用 `/api/auth/wechat/bind-phone`，后端会合并同手机号已有账号的购物车、地址与订单归属。
- mock 支付创建支付单后调用受保护的 `/api/payments/{id}/simulate-success`。
- gateway 模式使用后端返回的 `miniProgram` 参数调用 `wx.requestPayment`。

## 目录约束

- 页面不能直接调用 `wx.request`，统一通过 `services/request.js`。
- 会话统一保存在 `store/session.js`，请求自动注入 `X-Session-Token`。
- 401 会清理本地会话，并携带当前页面路径跳转登录页。
- 后端错误响应字段为 `error`；请求层同时兼容 `message`，页面会显示真实业务错误。
- 管理页面在 `onShow` 中执行管理员角色守卫。
- `project.private.config.json` 只保存本机设置，已加入 `.gitignore`。

## 生产发布前

1. 小程序主体完成认证并完成 ICP 备案相关要求。
2. 在小程序后台把 `https://xwycx.xyz` 加入 request 合法域名。
3. 配置真实 AppID/AppSecret，并确认登录、手机号绑定和支付使用同一个小程序 AppID。
4. 在常见手机宽度下检查登录、商品、购物车、地址、结算、订单、退款和管理员页面。
5. 关闭开发者工具中的“不校验合法域名”选项后再次验证。

## 接口对应关系

| 小程序能力 | Spring Boot 接口 |
| --- | --- |
| 微信登录、绑定手机号 | `/api/auth/wechat/login`、`/api/auth/wechat/bind-phone` |
| 短信登录 | `/api/auth/sms/send`、`/api/auth/sms/login` |
| 商品 | `/api/products`、`/api/products/{id}` |
| 购物车 | `/api/cart`、`/api/cart/items/**` |
| 配送地址 | `/api/addresses/**` |
| 采购订单 | `/api/customer-orders/**` |
| 支付、退款 | `/api/customer-orders/{id}/payments`、`/api/customer-orders/{id}/refunds` |
| 管理后台 | `/api/admin/**` |
