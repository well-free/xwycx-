# 支付宝沙箱与模拟支付说明

## 当前实现

- `app.payment.mode=mock`：本地默认模式，使用站内模拟支付地址和 `mock-signature`。
- `app.payment.mode=sandbox`：支付宝沙箱模拟模式，支付宝支付单生成沙箱网关 URL。
- `app.payment.mode=gateway`：预留真实网关模式，不允许使用模拟成功接口。

## 支付宝沙箱模式

开启沙箱模式需要配置：

```bash
XWYCX_PAYMENT_MODE=sandbox
XWYCX_PAYMENT_CALLBACK_SECRET=sandbox-secret
XWYCX_PAYMENT_CALLBACK_BASE_URL=https://xwycx.xyz
XWYCX_ALIPAY_APP_ID=your-alipay-sandbox-app-id
XWYCX_ALIPAY_SANDBOX_GATEWAY_URL=https://openapi-sandbox.dl.alipaydev.com/gateway.do
```

创建支付宝支付单后，响应中的 `payUrl` 会指向支付宝沙箱网关，`qrCode` 会返回 `ALIPAY_SANDBOX:{paymentId}:{amount}`，`gatewayMode` 返回 `sandbox`。

## 模拟成功接口

前端“模拟支付回调”按钮调用：

```http
POST /api/payments/{id}/simulate-success
```

该接口仅允许在 `mock` 或 `sandbox` 支付单上使用。它会复用正式回调处理链路，仍然执行支付单归属校验、幂等处理、状态流转和订单已支付标记。

真实 `gateway` 模式下，该接口返回业务冲突错误，避免生产误用模拟支付。
