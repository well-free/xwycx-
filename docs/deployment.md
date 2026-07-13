# xwycx.xyz 单机上线部署说明

## 服务器组件

- Ubuntu 22.04+
- JDK 21
- MySQL 8
- Redis
- RocketMQ
- Nginx
- Certbot

## 环境变量

生产环境将敏感配置放在 `/etc/xwycx/xwycx.env`：

```bash
XWYCX_DB_URL=jdbc:mysql://127.0.0.1:3306/xwycx_order?useSSL=false&serverTimezone=UTC&characterEncoding=utf8
XWYCX_DB_USERNAME=xwycx
XWYCX_DB_PASSWORD=change-me
XWYCX_REDIS_HOST=127.0.0.1
XWYCX_REDIS_PORT=6379
XWYCX_ROCKETMQ_NAMESERVER=127.0.0.1:9876
XWYCX_PAYMENT_MODE=sandbox
XWYCX_PAYMENT_CALLBACK_SECRET=change-me
XWYCX_PAYMENT_CALLBACK_BASE_URL=https://xwycx.xyz
XWYCX_ALIPAY_APP_ID=your-alipay-app-id
XWYCX_ALIPAY_SANDBOX_GATEWAY_URL=https://openapi-sandbox.dl.alipaydev.com/gateway.do
XWYCX_WECHAT_MCH_ID=your-wechat-merchant-id
XWYCX_ALIYUN_SMS_ACCESS_KEY_ID=your-ram-access-key-id
XWYCX_ALIYUN_SMS_ACCESS_KEY_SECRET=your-ram-access-key-secret
XWYCX_ALIYUN_SMS_SIGN_NAME=your-sms-sign-name
XWYCX_ALIYUN_SMS_TEMPLATE_CODE=SMS_123456789
```

## 首次部署

```bash
sudo useradd -r -m -d /opt/xwycx xwycx
sudo mkdir -p /opt/xwycx /etc/xwycx /var/log/xwycx /var/backups/xwycx
sudo chown -R xwycx:xwycx /opt/xwycx /var/log/xwycx /var/backups/xwycx
mysql -uroot -p -e "create database xwycx_order character set utf8mb4 collate utf8mb4_unicode_ci;"
mysql -uroot -p -e "create user 'xwycx'@'%' identified by 'change-me'; grant all privileges on xwycx_order.* to 'xwycx'@'%';"
```

复制文件：

```bash
sudo cp target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar /opt/xwycx/xwycx-disposable-order-system.jar
sudo cp deploy/xwycx.service /etc/systemd/system/xwycx.service
sudo cp deploy/nginx.conf /etc/nginx/nginx.conf
sudo systemctl daemon-reload
sudo systemctl enable --now xwycx
```

## HTTPS

```bash
sudo certbot certonly --nginx -d xwycx.xyz -d www.xwycx.xyz
sudo nginx -t
sudo systemctl reload nginx
```

## 发布与回滚

发布：

```bash
sudo systemctl stop xwycx
sudo cp target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar /opt/xwycx/xwycx-disposable-order-system.jar
sudo systemctl start xwycx
curl -f https://xwycx.xyz/api/health
```

回滚：

```bash
sudo cp /opt/xwycx/releases/previous.jar /opt/xwycx/xwycx-disposable-order-system.jar
sudo systemctl restart xwycx
```

## 备份

将 `deploy/mysql-backup.sh` 放到 `/opt/xwycx/mysql-backup.sh`，并配置每日 cron：

```cron
20 3 * * * XWYCX_DB_USERNAME=xwycx XWYCX_DB_PASSWORD=change-me XWYCX_DB_NAME=xwycx_order /opt/xwycx/mysql-backup.sh
```
