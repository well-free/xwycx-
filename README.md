# xwycx.xyz 一次性用品订单交易系统

> 当前同时提供 Web 页面和原生微信小程序。小程序源码位于 `wechat-miniprogram/`，支持微信登录与短信登录、商品采购、购物车、地址、订单、mock 支付、微信支付 API v3 参数、退款及管理员工作台。开发与上线说明见 `docs/wechat-miniprogram.md`、`docs/wechat-pay-v3.md` 和 `docs/deployment.md`。

一个面向一次性用品供应和采购场景的单商家直营商城，域名为 `xwycx.xyz`。一个商家维护统一商品与库存，多个用户独立登录、下单、支付和退款，不涉及多商户拆单或分账。默认使用 H2 内存库便于本地运行，生产 profile 使用 MySQL、Redis、RocketMQ、Flyway 和 Nginx。

## 技术栈

- Java 25 LTS
- Spring Boot 4.0.3
- MyBatis-Plus
- H2 / MySQL
- Redis / Redisson
- RocketMQ
- Flyway
- Maven
- 原生 HTML / CSS / JavaScript

## 项目目录

```text
backend/             Spring Boot 后端、数据库迁移与后端测试
frontend/            Web 前端 HTML、CSS 和 JavaScript
wechat-miniprogram/  原生微信小程序
deploy/              Nginx、systemd 与备份脚本
docs/                开发和部署文档
```

根目录 `pom.xml` 是 Maven 聚合入口。后端构建会将 `frontend/` 作为静态资源打入 Spring Boot JAR，因此源码保持独立，部署方式不变。

## 已实现功能

### 用户与采购订单

- 手机号验证码登录
- 服务端会话 token
- 用户角色：`CUSTOMER` / `ADMIN`
- 商品列表与商品详情
- 商城采购订单创建
- 下单冻结库存，取消或超时释放库存
- 发货后冻结库存转为已售库存
- 我的订单查询
- 管理员发货

### 保留的撮合能力

- 限价单下单 / 撤单 / 查询
- 价格优先、时间优先撮合
- 支持部分成交
- 成交记录查询
- 默认关闭且不作为商城主流程；仅通过 `app.matching.enabled=true` 兼容开启

### 支付与退款

- 创建支付单
- 支付渠道：支付宝、微信支付
- 支付网关抽象：local 使用 mock，prod 使用 gateway 配置
- 支付状态流转：待支付、支付中、成功、失败、关闭、退款中、已退款
- 支付回调验签
- 支付回调金额校验
- 支付回调幂等处理
- 支付关闭
- 全额 / 指定金额退款
- 退款记录查询

当前支付网关已经抽象出 `PaymentGateway` 边界；local profile 使用 mock secret，生产 profile 禁用默认数据库密码并通过环境变量配置支付密钥、AppId 和商户号。

### 商品缓存与防护

- 商品查询缓存
- 订单查询缓存
- 空对象缓存
- 布隆过滤器防穿透
- 本地缓存实现
- Redis 缓存实现
- 接口级令牌桶限流
- Redis + Lua 限流实现

### 并发与异步

- 下单撮合按商品 SKU 加锁
- 撤单、支付、退款按业务 ID 加锁
- Redisson 生产配置预留
- MyBatis-Plus 乐观锁字段
- RocketMQ 延迟消息预留
- 事务 Outbox 可靠记录订单超时事件并支持失败重试
- 本地或 RocketMQ 调度实现订单超时关闭
- 商家目录变更模拟入口，触发缓存淘汰和 MQ 消息

### 前端页面

- 用品订单概览指标
- 手机号登录面板
- 一次性用品商品列表
- 采购订单创建
- 我的订单列表
- 支付与退款处理面板
- 支付记录表
- 管理员商品新增和发货入口

访问地址：

```text
http://localhost:8080/
https://xwycx.xyz/
```

## 主要接口

### 订单

```http
POST /api/auth/sms/send
POST /api/auth/sms/login
POST /api/auth/logout
GET  /api/auth/me
```

### 商品

```http
GET  /api/store
GET  /api/products
GET  /api/products/{id}
```

### 采购订单

```http
POST /api/customer-orders
GET  /api/customer-orders
GET  /api/customer-orders/{id}
POST /api/customer-orders/{id}/cancel
POST /api/customer-orders/{id}/payments
POST /api/customer-orders/{id}/refunds
```

创建采购订单示例：

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 10
    }
  ],
  "addressId": 1,
  "remark": "工作日配送"
}
```

### 撮合订单

```http
POST /api/orders
POST /api/orders/{id}/cancel
GET  /api/orders/{id}
GET  /api/orders
GET  /api/trades
```

下单示例：

```json
{
  "symbol": "MASK-50",
  "side": "BUY",
  "price": 12.80,
  "quantity": 10
}
```

### 支付

```http
POST /api/payments
GET  /api/payments
GET  /api/payments/{id}
POST /api/payments/{id}/close
POST /api/payments/callbacks/{channel}
POST /api/payments/{id}/refunds
GET  /api/payments/{id}/refunds
```

创建支付单示例：

```json
{
  "orderId": 1,
  "channel": "ALIPAY"
}
```

模拟支付成功回调示例：

```json
{
  "paymentId": 1,
  "notifyId": "notify-1",
  "channelTradeNo": "trade-1",
  "amount": 200.00,
  "status": "SUCCESS",
  "signature": "mock-signature"
}
```

退款示例：

```json
{
  "amount": 200.00,
  "reason": "用户申请退款"
}
```

### 管理后台

```http
GET  /api/products/{id}
POST /api/products/{id}/hot-score?score=92
POST /api/admin/products
PUT  /api/admin/products/{id}
POST /api/admin/products/{id}/stock?stock=5000
POST /api/admin/orders/{id}/ship
POST /api/admin/refunds/{id}/approve
POST /api/admin/catalog/change?merchantId=1
GET  /api/health
```

## 本地运行

项目默认使用 `local` profile 和 H2 内存数据库。

```powershell
$env:JAVA_HOME='C:\Users\1\Downloads\jdk-25_windows-x64_bin\jdk-25.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f backend/pom.xml spring-boot:run
```

或打包后运行：

```powershell
mvn package -DskipTests
java -jar backend/target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar
```

启动后打开根路径：

```text
http://localhost:8080/
```

根路径会由后端直接跳转到登录页：

```text
http://localhost:8080/login.html?redirect=/index.html
```

登录成功后进入工作台 `http://localhost:8080/index.html`。如果直接访问 `/index.html` 且本地没有有效登录 token，前端也会跳回登录页。

前端页面已按业务拆分：

```text
http://localhost:8080/index.html     采购总览
http://localhost:8080/products.html  商品采购
http://localhost:8080/orders.html    订单支付
http://localhost:8080/admin.html     后台运营
```

本地验证码固定为 `123456`；普通用户可用 `13800000001`，管理员可用 `13900000000`。

如果提示 `Port 8080 was already in use`，说明 8080 端口被占用。可以停止占用进程，或在 `backend/src/main/resources/application.yml` 中修改 `server.port`。

## 生产配置

生产 profile 配置文件：

```text
backend/src/main/resources/application-prod.yml
```

已预留：

- MySQL: `jdbc:mysql://localhost:3306/xwycx_order`
- Redis: `localhost:6379`
- RocketMQ NameServer: `localhost:9876`
- `app.redis.enabled=true`
- `app.mq.enabled=true`
- `spring.flyway.enabled=true`

启动生产 profile：

```powershell
java -jar backend/target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

详细部署说明：

```text
docs/deployment.md
deploy/nginx.conf
deploy/xwycx.service
deploy/mysql-backup.sh
```

## 数据库结构

核心表：

- `orders`
- `trades`
- `products`
- `merchants`
- `users`
- `sms_codes`
- `user_sessions`
- `customer_orders`
- `customer_order_items`
- `shipping_addresses`
- `inventory_logs`
- `payment_orders`
- `payment_callbacks`
- `refund_orders`
- `store_settings`
- `outbox_events`

默认初始化商品：

- 商家：`xwycx 一次性用品供应商`
- 商品：`一次性医用口罩 50只装`
- 默认 SKU：`MASK-50`

初始化脚本：

```text
backend/src/main/resources/schema.sql
backend/src/main/resources/data.sql
```

## 测试

运行全部测试：

```powershell
mvn test
node --test wechat-miniprogram/tests/*.test.js
```

当前覆盖：

- 订单撮合
- 部分成交
- 同价时间优先
- 撤单后不可成交
- 非法价格/数量校验
- 订单接口
- 支付创建
- 支付回调幂等
- 支付金额校验
- 退款
- 支付接口
- 手机号登录接口
- 采购订单创建 / 支付 / 退款
- 后台权限校验
- legacy 兼容代码清理约束

## 当前边界

- 支付宝/微信支付已经抽象网关边界，生产仍需填入真实商户配置并对接官方证书/SDK。
- Canal 目前是商家变更落点模拟，尚未直接连接 Canal Server。
- 默认本地数据使用 H2 内存库，重启后重置。
- 短信验证码 local profile 固定为 `123456`，生产仍需接入阿里云或腾讯云短信。
- 管理后台是轻量入口，复杂权限、审计报表和客服工作台可后续增强。
