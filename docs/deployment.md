# xwycx.xyz 单机生产部署

首版部署组件：Ubuntu 22.04+、JDK 21、MySQL 8、Redis、RocketMQ、Nginx、Certbot。

## 1. 服务器目录

```bash
sudo useradd -r -m -d /opt/xwycx xwycx
sudo mkdir -p /opt/xwycx/releases /etc/xwycx /var/log/xwycx /var/backups/xwycx
sudo chown -R xwycx:xwycx /opt/xwycx /var/log/xwycx /var/backups/xwycx
```

微信商户私钥放在 `/etc/xwycx/apiclient_key.pem`，文件权限设为 `600`，所有者为运行应用的 `xwycx` 用户。不要提交私钥、API v3 Key、AppSecret 或 AccessKey。

## 2. MySQL

```sql
create database xwycx_order character set utf8mb4 collate utf8mb4_unicode_ci;
create user 'xwycx'@'127.0.0.1' identified by 'replace-with-strong-password';
grant all privileges on xwycx_order.* to 'xwycx'@'127.0.0.1';
flush privileges;
```

生产环境通过 Flyway 升级，不执行 `schema.sql`，也不会删除已有表。

## 3. 环境变量

创建 `/etc/xwycx/xwycx.env`：

```bash
XWYCX_DB_URL=jdbc:mysql://127.0.0.1:3306/xwycx_order?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
XWYCX_DB_USERNAME=xwycx
XWYCX_DB_PASSWORD=replace-with-strong-password
XWYCX_REDIS_HOST=127.0.0.1
XWYCX_REDIS_PORT=6379
XWYCX_ROCKETMQ_NAMESERVER=127.0.0.1:9876

XWYCX_ALIYUN_SMS_ACCESS_KEY_ID=replace-me
XWYCX_ALIYUN_SMS_ACCESS_KEY_SECRET=replace-me
XWYCX_ALIYUN_SMS_SIGN_NAME=replace-me
XWYCX_ALIYUN_SMS_TEMPLATE_CODE=SMS_000000000

XWYCX_PAYMENT_MODE=gateway
XWYCX_PAYMENT_CALLBACK_SECRET=replace-with-random-value
XWYCX_PAYMENT_CALLBACK_BASE_URL=https://xwycx.xyz
XWYCX_PAYMENT_TIMEOUT_SECONDS=900
XWYCX_ORDER_TIMEOUT_SECONDS=900

XWYCX_WECHAT_APP_ID=replace-me
XWYCX_WECHAT_APP_SECRET=replace-me
XWYCX_WECHAT_MCH_ID=replace-me
XWYCX_WECHAT_MERCHANT_SERIAL_NUMBER=replace-me
XWYCX_WECHAT_PRIVATE_KEY_PATH=/etc/xwycx/apiclient_key.pem
XWYCX_WECHAT_API_V3_KEY=replace-with-exactly-32-characters
XWYCX_WECHAT_NOTIFY_URL=https://xwycx.xyz/api/payments/callbacks/wechat
XWYCX_WECHAT_REFUND_NOTIFY_URL=https://xwycx.xyz/api/payments/callbacks/wechat/refund
```

使用 RAM 子账号发送阿里云短信，只授予短信发送权限。微信 API v3 Key 必须恰好 32 个字符。

## 4. 构建与发布

```bash
mvn -Pprod clean package
sudo cp target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar /opt/xwycx/releases/xwycx-$(date +%Y%m%d%H%M%S).jar
sudo ln -sfn /opt/xwycx/releases/xwycx-YYYYMMDDHHMMSS.jar /opt/xwycx/xwycx-disposable-order-system.jar
sudo cp deploy/xwycx.service /etc/systemd/system/xwycx.service
sudo systemctl daemon-reload
sudo systemctl enable --now xwycx
```

检查：

```bash
sudo systemctl status xwycx
curl -f http://127.0.0.1:8080/actuator/health
```

## 5. Nginx 与 HTTPS

先确保 `xwycx.xyz` 和 `www.xwycx.xyz` 的 A 记录指向服务器，再执行：

```bash
sudo apt install nginx certbot python3-certbot-nginx
sudo certbot certonly --nginx -d xwycx.xyz -d www.xwycx.xyz
sudo cp deploy/nginx.conf /etc/nginx/nginx.conf
sudo nginx -t
sudo systemctl reload nginx
```

网站 TLS 证书由 Let's Encrypt 提供。微信商户 API 证书/私钥用于 API v3 请求签名，两类证书不是同一个用途。

## 6. 上线检查

```bash
curl -f https://xwycx.xyz/actuator/health
curl -I https://xwycx.xyz/api/products
sudo journalctl -u xwycx -n 200 --no-pager
sudo tail -f /var/log/nginx/xwycx.access.log
```

在微信支付商户平台确认回调地址可从公网访问。回调必须是 HTTPS，不能增加登录鉴权，也不能被 CDN/WAF 改写原始请求体或 `Wechatpay-*` 请求头。

## 7. 备份与回滚

安装每日备份：

```cron
20 3 * * * XWYCX_DB_USERNAME=xwycx XWYCX_DB_PASSWORD=replace-me XWYCX_DB_NAME=xwycx_order /opt/xwycx/mysql-backup.sh
```

回滚只切换 jar，不回退已经执行的数据库迁移：

```bash
sudo ln -sfn /opt/xwycx/releases/previous.jar /opt/xwycx/xwycx-disposable-order-system.jar
sudo systemctl restart xwycx
curl -f https://xwycx.xyz/actuator/health
```
