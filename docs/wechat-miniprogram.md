# 微信原生小程序开发说明

## 导入项目

1. 启动本地 Spring Boot 服务：`mvn spring-boot:run`。
2. 打开微信开发者工具，导入仓库中的 `wechat-miniprogram/`。
3. 本地开发可使用测试 AppID，并在开发者工具中临时勾选“不校验合法域名、web-view、TLS 版本以及 HTTPS 证书”。该选项只能用于本地开发。
4. `wechat-miniprogram/config/env.js` 的本地地址默认为 `http://localhost:8080`；真机调试需要改成电脑局域网地址或已备案的 HTTPS 域名。

## 本地登录与支付

- 手机验证码登录固定验证码：`123456`。
- 普通用户示例手机号：`13800000001`。
- 管理员示例手机号：`13900000000`。
- 微信登录调用 `wx.login`，本地 profile 使用模拟 code2session。
- mock 支付创建支付单后调用受保护的 `/api/payments/{id}/simulate-success`。
- gateway 模式使用后端返回的 `miniProgram` 参数调用 `wx.requestPayment`。

## 目录约束

- 页面不能直接调用 `wx.request`，统一通过 `services/request.js`。
- 会话统一保存在 `store/session.js`，请求自动注入 `X-Session-Token`。
- 401 会清理本地会话并跳转登录页。
- 管理页面在 `onShow` 中执行管理员角色守卫。
- `project.private.config.json` 只保存本机设置，已加入 `.gitignore`。

## 生产发布前

1. 小程序主体完成认证并完成 ICP 备案相关要求。
2. 在小程序后台把 `https://xwycx.xyz` 加入 request 合法域名。
3. 配置真实 AppID/AppSecret，并确认登录、手机号绑定和支付使用同一个小程序 AppID。
4. 在常见手机宽度下检查登录、商品、购物车、地址、结算、订单、退款和管理员页面。
5. 关闭开发者工具中的“不校验合法域名”选项后再次验证。
