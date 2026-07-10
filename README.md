# xwycx.xyz 一次性用品订单交易系统

一个面向一次性用品供应和采购场景的订单交易系统，域名为 `xwycx.xyz`。系统覆盖用品限价单、撤单、供需撮合、成交记录、商品缓存、限流、支付、退款和前端交易工作台。默认使用 H2 内存库便于本地运行，生产 profile 预留 MySQL、Redis、Redisson 和 RocketMQ 配置。

## 技术栈

- Java 21
- Spring Boot 3.3.5
- MyBatis-Plus
- H2 / MySQL
- Redis / Redisson
- RocketMQ
- Maven
- 原生 HTML / CSS / JavaScript

## 已实现功能

### 用品订单

- 一次性用品限价单下单
- 采购 / 供应方向
- 多商品 SKU，例如 `MASK-50`
- 价格优先、时间优先撮合
- 支持部分成交
- 支持撤单
- 支持超时关闭
- 成交记录查询

### 支付与退款

- 创建支付单
- 支付渠道：支付宝、微信支付
- 模拟支付链接和二维码内容
- 支付状态流转：待支付、支付中、成功、失败、关闭、退款中、已退款
- 支付回调验签模拟
- 支付回调金额校验
- 支付回调幂等处理
- 支付关闭
- 全额 / 指定金额退款
- 退款记录查询

当前支付渠道为模拟实现，方便跑通业务闭环；后续可在 `PaymentService` 的渠道边界处替换为支付宝/微信官方 SDK。

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
- 本地调度实现订单超时关闭
- 商家目录变更模拟入口，触发缓存淘汰和 MQ 消息

### 前端页面

- 用品订单概览指标
- 一次性用品下单面板
- 供需订单簿快照
- 订单列表与撤单
- 成交记录
- 支付处理面板
- 支付记录表
- 商品缓存运维入口

访问地址：

```text
http://localhost:8080/
https://xwycx.xyz/
```

## 主要接口

### 订单

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

### 商品与管理

```http
GET  /api/products/{id}
POST /api/products/{id}/hot-score?score=92
POST /api/admin/catalog/change?merchantId=1
GET  /api/health
```

## 本地运行

项目默认使用 `local` profile 和 H2 内存数据库。

```powershell
$env:JAVA_HOME=(Resolve-Path .jdk\jdk-21.0.11).Path
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn spring-boot:run
```

或打包后运行：

```powershell
mvn package -DskipTests
java -jar target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar
```

启动后打开：

```text
http://localhost:8080/
```

如果提示 `Port 8080 was already in use`，说明 8080 端口被占用。可以停止占用进程，或在 `application.yml` 中修改 `server.port`。

## 生产配置

生产 profile 配置文件：

```text
src/main/resources/application-prod.yml
```

已预留：

- MySQL: `jdbc:mysql://localhost:3306/order_mvp`
- Redis: `localhost:6379`
- RocketMQ NameServer: `localhost:9876`
- `app.redis.enabled=true`
- `app.mq.enabled=true`

启动生产 profile：

```powershell
java -jar target/xwycx-disposable-order-system-1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 数据库结构

核心表：

- `orders`
- `trades`
- `products`
- `merchants`
- `payment_orders`
- `payment_callbacks`
- `refund_orders`

默认初始化商品：

- 商家：`xwycx 一次性用品供应商`
- 商品：`一次性医用口罩 50只装`
- 默认 SKU：`MASK-50`

初始化脚本：

```text
src/main/resources/schema.sql
src/main/resources/data.sql
```

## 测试

运行全部测试：

```powershell
mvn test
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
- legacy 兼容代码清理约束

## 当前边界

- 支付宝/微信支付目前是模拟渠道，尚未接入官方 SDK。
- Canal 目前是商家变更落点模拟，尚未直接连接 Canal Server。
- 默认本地数据使用 H2 内存库，重启后重置。
- 未实现登录、鉴权、真实用户账户体系。
