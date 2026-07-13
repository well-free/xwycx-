# 阿里云短信验证码接入说明

## 当前实现

- 本地 `local` profile 使用 `LocalSmsProvider`，验证码固定为 `123456`。
- 生产 `prod` profile 使用 `AliyunSmsProvider`，通过阿里云短信服务发送 6 位数字验证码。
- 验证码通过 `CacheStore` 保存，生产环境 `app.redis.enabled=true` 时落 Redis，key 为 `sms:code:{phone}`，默认 5 分钟过期。
- `sms_codes` 表保留发送审计，记录手机号、验证码、provider、provider requestId、状态、过期时间和是否已消费。
- 生产环境不允许使用本地固定验证码直接登录。

## 阿里云侧准备

1. 开通阿里云短信服务。
2. 创建 RAM 子账号，不使用主账号 AccessKey。
3. 给 RAM 子账号授予短信发送权限。
4. 申请短信签名。
5. 申请验证码短信模板。
6. 模板内容建议：`您的验证码为 ${code}，5分钟内有效，请勿泄露。`

## 生产环境变量

```bash
XWYCX_ALIYUN_SMS_ACCESS_KEY_ID=your-ram-access-key-id
XWYCX_ALIYUN_SMS_ACCESS_KEY_SECRET=your-ram-access-key-secret
XWYCX_ALIYUN_SMS_SIGN_NAME=your-sms-sign-name
XWYCX_ALIYUN_SMS_TEMPLATE_CODE=SMS_123456789
XWYCX_ALIYUN_SMS_ENDPOINT=dysmsapi.aliyuncs.com
```

这些变量应写入服务器的 `/etc/xwycx/xwycx.env`，不要提交到 Git。

## 依赖组件

- MySQL：保存用户、会话和短信发送审计。
- Redis：保存验证码、发送冷却、每日手机号计数、IP 计数。
- Nginx + HTTPS：上线后登录页和验证码接口必须走 HTTPS。
- 日志巡检：关注 `sms code sent`、`aliyun sms send failed`、`too many requests`。

## 验证规则

- 验证码长度：6 位数字。
- 验证码有效期：300 秒。
- 登录成功后验证码立即失效。
- 生产环境同手机号 60 秒内不能重复发送。
- 生产环境同手机号每日最多发送 10 次。
- 生产环境同 IP 每分钟最多发送 20 次。
