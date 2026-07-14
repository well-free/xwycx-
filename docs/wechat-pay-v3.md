# 微信支付 API v3 接入

## 所需材料

- 小程序 AppID 与 AppSecret。
- 微信支付商户号。
- 商户证书序列号。
- 商户 API 私钥 `apiclient_key.pem`。
- 32 字符 API v3 Key。
- 支付通知地址：`https://xwycx.xyz/api/payments/callbacks/wechat`。
- 退款通知地址：`https://xwycx.xyz/api/payments/callbacks/wechat/refund`。

项目使用官方 `wechatpay-java` SDK。`OfficialWechatPaySdkClient` 负责 JSAPI 预下单、RSA 小程序支付参数、退款请求、平台证书自动更新以及通知验签解密。不要自行实现 RSA 验签或 AES-GCM 解密。

## 处理流程

1. 用户通过微信登录获得并绑定 openid。
2. 后端创建支付单，使用支付单 ID 作为 `out_trade_no`。
3. 后端调用 JSAPI 预下单并返回 `timeStamp`、`nonceStr`、`packageValue`、`signType`、`paySign`。
4. 小程序调用 `wx.requestPayment`。
5. 微信回调到 HTTPS 地址，后端使用原始请求体和 `Wechatpay-*` 头验签解密。
6. 服务层再次校验渠道、支付单 ID、商户号、AppID 和金额，并按通知 ID 幂等处理。
7. 退款处于处理中或失败时写入持久化状态，由补偿任务按原退款单号重试。

## 安全要求

- 私钥文件权限为 `600`，运行用户可读，其他用户不可读。
- API v3 Key、AppSecret、商户私钥不写入 Git、日志或前端包。
- Nginx 必须保留原始请求体和 `Wechatpay-Serial/Timestamp/Nonce/Signature` 请求头。
- 生产模式禁止模拟支付。
- 支付宝生产网关未配置时会明确返回不可用，不生成假的支付链接。

## 联调检查

```bash
curl -f https://xwycx.xyz/actuator/health
sudo tail -f /var/log/nginx/xwycx.access.log
sudo journalctl -u xwycx -f
```

支付成功后核对 `payment_orders`、`payment_callbacks`、`customer_orders` 三处状态；退款核对 `refund_orders` 与支付/订单状态。重复通知只能新增一次回调审计，不得重复扣库存或推进终态。
