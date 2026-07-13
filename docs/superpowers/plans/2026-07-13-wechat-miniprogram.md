# WeChat Mini Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a production-oriented native WeChat Mini Program with dual login, a complete disposable-supplies purchasing flow, role-gated administration, mock payments for development, and WeChat Pay API v3 readiness.

**Architecture:** Keep Spring Boot as the single business source of truth and add focused identity, cart, address, and WeChat payment adapters around the existing services. Place the native Mini Program in `wechat-miniprogram/`; isolate HTTP services, state, types, and WeChat-specific APIs so a later Taro migration only replaces the view layer.

**Tech Stack:** Java 21, Spring Boot 3.3.5, MyBatis-Plus, Flyway, H2/MySQL, Redis, RocketMQ, WeChat Pay Java SDK, native WeChat Mini Program, JavaScript, WXML, WXSS.

---

## File Structure

Backend additions are grouped by responsibility:

- `src/main/java/org/example/wechat/`: WeChat session exchange and payment-specific adapters.
- `src/main/java/org/example/service/WechatAuthService.java`: account binding and project-session creation.
- `src/main/java/org/example/service/CartService.java`: persistent cart ownership and mutations.
- `src/main/java/org/example/service/AddressService.java`: address ownership and default-address rules.
- `src/main/java/org/example/web/WechatAuthController.java`, `CartController.java`, `AddressController.java`: HTTP contracts only.
- `src/main/java/org/example/infrastructure/mybatis/entity/` and `mapper/`: focused persistence records.
- `src/main/resources/db/migration/V6__add_wechat_miniprogram_tables.sql`: schema upgrade without destructive initialization.
- `wechat-miniprogram/services/`, `store/`, `platform/`, `types/`: portable non-view code.
- `wechat-miniprogram/pages/` and `components/`: native Mini Program UI.

Existing `AuthService`, `CustomerOrderService`, `PaymentService`, and `AdminController` remain the orchestration points they already are; changes to them stay narrow.

### Task 1: Establish the Migration and Persistence Model

**Files:**
- Create: `src/main/resources/db/migration/V6__add_wechat_miniprogram_tables.sql`
- Create: `src/main/java/org/example/infrastructure/mybatis/entity/WechatIdentityEntity.java`
- Create: `src/main/java/org/example/infrastructure/mybatis/entity/CartItemEntity.java`
- Create: `src/main/java/org/example/infrastructure/mybatis/mapper/WechatIdentityMapper.java`
- Create: `src/main/java/org/example/infrastructure/mybatis/mapper/CartItemMapper.java`
- Modify: `src/main/java/org/example/infrastructure/mybatis/entity/ShippingAddressEntity.java`
- Modify: `src/main/java/org/example/infrastructure/mybatis/entity/CustomerOrderEntity.java`
- Modify: `src/main/java/org/example/infrastructure/mybatis/entity/PaymentOrderEntity.java`
- Test: `src/test/java/org/example/web/WechatSchemaIntegrationTest.java`

- [ ] **Step 1: Write the failing schema integration test**

```java
@SpringBootTest
@Transactional
class WechatSchemaIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void shouldExposeWechatCartAddressSnapshotAndPrepayColumns() {
        assertThat(jdbc.queryForObject("select count(*) from wechat_identities", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cart_items", Long.class)).isZero();
        assertThat(columnExists("CUSTOMER_ORDERS", "SHIPPING_SNAPSHOT")).isTrue();
        assertThat(columnExists("PAYMENT_ORDERS", "PREPAY_ID")).isTrue();
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject("select count(*) from information_schema.columns where table_name=? and column_name=?",
                Integer.class, table, column) > 0;
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `mvn -q -Dtest=WechatSchemaIntegrationTest test`

Expected: FAIL because `wechat_identities` and `cart_items` do not exist.

- [ ] **Step 3: Add the Flyway migration**

```sql
create table wechat_identities (
  id bigint primary key,
  user_id bigint not null,
  appid varchar(64) not null,
  openid varchar(128) not null,
  unionid varchar(128),
  created_at timestamp not null,
  updated_at timestamp not null,
  constraint uk_wechat_app_openid unique (appid, openid)
);

create table cart_items (
  id bigint primary key,
  user_id bigint not null,
  product_id bigint not null,
  quantity bigint not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  constraint uk_cart_user_product unique (user_id, product_id)
);

alter table shipping_addresses add column province varchar(64) default '' not null;
alter table shipping_addresses add column city varchar(64) default '' not null;
alter table shipping_addresses add column district varchar(64) default '' not null;
alter table customer_orders add column shipping_snapshot varchar(2000);
alter table payment_orders add column prepay_id varchar(128);
alter table payment_orders add column payment_parameters varchar(2000);
```

- [ ] **Step 4: Add matching entities and mappers**

```java
@TableName("cart_items")
public class CartItemEntity {
    @TableId(type = IdType.INPUT) private Long id;
    private Long userId;
    private Long productId;
    private long quantity;
    private Instant createdAt;
    private Instant updatedAt;
    // standard getters and setters for every field
}

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {}
```

Implement `WechatIdentityEntity` and `WechatIdentityMapper` with the same MyBatis-Plus conventions. Add `province`, `city`, and `district` to `ShippingAddressEntity`; `shippingSnapshot` to `CustomerOrderEntity`; and `prepayId`, `paymentParameters` to `PaymentOrderEntity`.

- [ ] **Step 5: Run the schema test and full migration smoke test**

Run: `mvn -q -Dtest=WechatSchemaIntegrationTest test`

Expected: PASS.

- [ ] **Step 6: Commit the persistence slice**

```powershell
git add src/main/resources/db/migration/V6__add_wechat_miniprogram_tables.sql src/main/java/org/example/infrastructure/mybatis src/test/java/org/example/web/WechatSchemaIntegrationTest.java
git commit -m "feat: add wechat mini program persistence model"
```

### Task 2: Add WeChat Login and Phone Binding

**Files:**
- Create: `src/main/java/org/example/wechat/WechatSessionGateway.java`
- Create: `src/main/java/org/example/wechat/WechatSession.java`
- Create: `src/main/java/org/example/wechat/LocalWechatSessionGateway.java`
- Create: `src/main/java/org/example/wechat/WechatCode2SessionGateway.java`
- Create: `src/main/java/org/example/service/WechatAuthService.java`
- Create: `src/main/java/org/example/web/WechatAuthController.java`
- Create: `src/main/java/org/example/web/dto/WechatLoginRequest.java`
- Create: `src/main/java/org/example/web/dto/WechatBindPhoneRequest.java`
- Modify: `src/main/java/org/example/config/AppProperties.java`
- Modify: `src/main/java/org/example/service/AuthService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/java/org/example/service/WechatAuthServiceTest.java`
- Test: `src/test/java/org/example/web/WechatAuthApiTest.java`

- [ ] **Step 1: Write failing service tests for login reuse and phone binding**

```java
@Test
void shouldReuseUserForSameAppidAndOpenid() {
    when(sessionGateway.exchange("wx-code")).thenReturn(new WechatSession("app-local", "openid-1", null));
    AuthLoginResponse first = service.login(new WechatLoginRequest("wx-code"));
    AuthLoginResponse second = service.login(new WechatLoginRequest("wx-code"));
    assertThat(second.user().id()).isEqualTo(first.user().id());
}

@Test
void shouldBindVerifiedPhoneWithoutChangingRole() {
    AuthLoginResponse login = service.login(new WechatLoginRequest("wx-code"));
    AuthLoginResponse bound = service.bindPhone(login.token(), new WechatBindPhoneRequest("13800000018", "123456"));
    assertThat(bound.user().phone()).isEqualTo("13800000018");
    assertThat(bound.user().role()).isEqualTo(UserRole.CUSTOMER);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -q -Dtest=WechatAuthServiceTest test`

Expected: compilation FAIL because WeChat auth classes do not exist.

- [ ] **Step 3: Define the gateway and local implementation**

```java
public interface WechatSessionGateway {
    WechatSession exchange(String code);
}

public record WechatSession(String appId, String openId, String unionId) {}

@Component
@Profile("local")
public class LocalWechatSessionGateway implements WechatSessionGateway {
    public WechatSession exchange(String code) {
        if (code == null || code.isBlank()) throw BusinessException.badRequest("invalid wechat code");
        return new WechatSession("local-app", "local-" + code, null);
    }
}
```

- [ ] **Step 4: Implement project session creation and binding**

Extract package-private helpers in `AuthService` so both SMS and WeChat flows use the same `createSession(UserEntity)` and `toResponse(UserEntity)` behavior. `WechatAuthService.login` finds `appid + openid`, creates a customer user when missing, persists identity, and returns a normal `AuthLoginResponse`. `bindPhone` consumes the SMS code, checks for an existing phone user, transfers the identity to that verified user when necessary, and creates a fresh session.

- [ ] **Step 5: Add production code-to-session exchange**

Use Spring `RestClient` against:

```text
https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code
```

Reject responses with missing `openid` or nonzero `errcode`. Never return or log `session_key`.

- [ ] **Step 6: Add HTTP endpoints and API tests**

```java
@RestController
@RequestMapping("/api/auth/wechat")
class WechatAuthController {
    @PostMapping("/login")
    AuthLoginResponse login(@Valid @RequestBody WechatLoginRequest request) { return service.login(request); }

    @PostMapping("/bind-phone")
    AuthLoginResponse bindPhone(@RequestHeader("X-Session-Token") String token,
                                @Valid @RequestBody WechatBindPhoneRequest request) {
        return service.bindPhone(token, request);
    }
}
```

Run: `mvn -q -Dtest=WechatAuthServiceTest,WechatAuthApiTest test`

Expected: PASS.

- [ ] **Step 7: Commit the auth slice**

```powershell
git add src/main/java/org/example/wechat src/main/java/org/example/service/WechatAuthService.java src/main/java/org/example/web src/main/java/org/example/config/AppProperties.java src/main/resources/application*.yml src/test/java/org/example/service/WechatAuthServiceTest.java src/test/java/org/example/web/WechatAuthApiTest.java
git commit -m "feat: add wechat login and phone binding"
```

### Task 3: Add Persistent Shopping Cart APIs

**Files:**
- Create: `src/main/java/org/example/service/CartService.java`
- Create: `src/main/java/org/example/web/CartController.java`
- Create: `src/main/java/org/example/web/dto/CartItemRequest.java`
- Create: `src/main/java/org/example/web/dto/CartItemResponse.java`
- Test: `src/test/java/org/example/service/CartServiceTest.java`
- Test: `src/test/java/org/example/web/CartApiTest.java`

- [ ] **Step 1: Write failing ownership and quantity tests**

```java
@Test
void shouldMergeRepeatedProductAndRejectInvalidQuantity() {
    service.put(user, new CartItemRequest(1L, 2));
    CartItemResponse item = service.put(user, new CartItemRequest(1L, 3));
    assertThat(item.quantity()).isEqualTo(3);
    assertThatThrownBy(() -> service.put(user, new CartItemRequest(1L, 0)))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -q -Dtest=CartServiceTest test`

Expected: compilation FAIL because cart service and DTOs do not exist.

- [ ] **Step 3: Implement cart service and product checks**

`put` must require a positive quantity, load an `ON_SALE` product, and upsert by `user_id + product_id`. `list` joins current product data into responses. `remove` deletes only rows matching both current user and product. Add `removePurchased(userId, productIds)` for order orchestration.

- [ ] **Step 4: Expose the exact API contract**

```java
@GetMapping public ApiPageResponse<CartItemResponse> list(@RequestHeader("X-Session-Token") String token);
@PostMapping("/items") public CartItemResponse add(...);
@PutMapping("/items/{productId}") public CartItemResponse update(...);
@DeleteMapping("/items/{productId}") public void delete(...);
```

- [ ] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=CartServiceTest,CartApiTest test`

Expected: PASS, including 401 without a token and isolation between two users.

- [ ] **Step 6: Commit the cart slice**

```powershell
git add src/main/java/org/example/service/CartService.java src/main/java/org/example/web/CartController.java src/main/java/org/example/web/dto/CartItem* src/test/java/org/example/service/CartServiceTest.java src/test/java/org/example/web/CartApiTest.java
git commit -m "feat: add persistent customer cart"
```

### Task 4: Add Address Management APIs

**Files:**
- Create: `src/main/java/org/example/service/AddressService.java`
- Create: `src/main/java/org/example/web/AddressController.java`
- Create: `src/main/java/org/example/web/dto/AddressRequest.java`
- Create: `src/main/java/org/example/web/dto/AddressResponse.java`
- Test: `src/test/java/org/example/service/AddressServiceTest.java`
- Test: `src/test/java/org/example/web/AddressApiTest.java`

- [ ] **Step 1: Write failing tests for ownership and one-default rule**

```java
@Test
void shouldKeepExactlyOneDefaultAddress() {
    AddressResponse first = service.create(user, request("Shanghai", true));
    AddressResponse second = service.create(user, request("Suzhou", true));
    assertThat(service.list(user)).filteredOn(AddressResponse::defaultAddress).extracting(AddressResponse::id)
            .containsExactly(second.id());
    assertThat(first.id()).isNotEqualTo(second.id());
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=AddressServiceTest test`

Expected: compilation FAIL because address service and DTOs do not exist.

- [ ] **Step 3: Implement CRUD and default switching transactionally**

Validate mainland mobile number, receiver name, province, city, district, and detail. Updates and deletes must query by `id + user_id`. Setting one address as default clears the user's other defaults in the same transaction.

- [ ] **Step 4: Add API endpoints and verify**

Run: `mvn -q -Dtest=AddressServiceTest,AddressApiTest test`

Expected: PASS for create/list/update/delete, 404 for another user's address, and 401 without login.

- [ ] **Step 5: Commit the address slice**

```powershell
git add src/main/java/org/example/service/AddressService.java src/main/java/org/example/web/AddressController.java src/main/java/org/example/web/dto/Address* src/main/java/org/example/infrastructure/mybatis/entity/ShippingAddressEntity.java src/test/java/org/example/service/AddressServiceTest.java src/test/java/org/example/web/AddressApiTest.java
git commit -m "feat: add shipping address management"
```

### Task 5: Integrate Cart and Address Snapshots with Orders

**Files:**
- Modify: `src/main/java/org/example/service/CustomerOrderService.java`
- Modify: `src/main/java/org/example/web/dto/CustomerOrderResponse.java`
- Test: `src/test/java/org/example/service/CustomerOrderServiceTest.java`
- Test: `src/test/java/org/example/web/CustomerCommerceApiTest.java`

- [ ] **Step 1: Write a failing order snapshot test**

```java
@Test
void shouldSnapshotOwnedAddressAndRemoveOnlyPurchasedCartItems() {
    CustomerOrderResponse order = service.create(user, requestFor(productId, addressId));
    addressService.update(user, addressId, changedAddress());
    assertThat(order.shippingAddress().detail()).isEqualTo("Original detail");
    assertThat(cartService.list(user)).extracting(CartItemResponse::productId).doesNotContain(productId);
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=CustomerOrderServiceTest test`

Expected: FAIL because orders do not expose an immutable address snapshot and cart rows remain.

- [ ] **Step 3: Implement snapshot and post-order cart cleanup**

Resolve the address through `AddressService.requireOwned`, serialize a stable `ShippingAddressSnapshot` record into `shipping_snapshot`, and return it in `CustomerOrderResponse`. Keep stock deduction, order insertion, items, inventory logs, and cart cleanup in one transaction. Preserve existing optimistic-lock and distributed-lock behavior.

- [ ] **Step 4: Verify cancel and timeout inventory idempotency**

Add assertions that calling cancellation twice does not release stock twice and that timeout handling ignores already-paid orders.

Run: `mvn -q -Dtest=CustomerOrderServiceTest,CustomerCommerceApiTest test`

Expected: PASS.

- [ ] **Step 5: Commit order integration**

```powershell
git add src/main/java/org/example/service/CustomerOrderService.java src/main/java/org/example/web/dto/CustomerOrderResponse.java src/test/java/org/example/service/CustomerOrderServiceTest.java src/test/java/org/example/web/CustomerCommerceApiTest.java
git commit -m "feat: integrate cart and address snapshots with orders"
```

### Task 6: Return Mini Program Payment Parameters in Mock Mode

**Files:**
- Modify: `src/main/java/org/example/payment/PaymentGatewayResult.java`
- Modify: `src/main/java/org/example/payment/ConfiguredPaymentGateway.java`
- Modify: `src/main/java/org/example/service/PaymentService.java`
- Modify: `src/main/java/org/example/web/dto/PaymentResponse.java`
- Test: `src/test/java/org/example/payment/ConfiguredPaymentGatewayTest.java`
- Test: `src/test/java/org/example/service/PaymentSandboxServiceTest.java`

- [ ] **Step 1: Write a failing WeChat mock parameter test**

```java
@Test
void shouldCreateWechatMiniProgramParametersInMockMode() {
    PaymentGatewayResult result = gateway.createPayment(PaymentChannel.WECHAT, 88L, new BigDecimal("12.80"));
    assertThat(result.miniProgram()).isNotNull();
    assertThat(result.miniProgram().packageValue()).isEqualTo("prepay_id=mock-88");
    assertThat(result.miniProgram().signType()).isEqualTo("RSA");
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=ConfiguredPaymentGatewayTest test`

Expected: compilation FAIL because `miniProgram` parameters do not exist.

- [ ] **Step 3: Extend the gateway result without channel-specific fields in services**

```java
public record MiniProgramPaymentParameters(
        String timeStamp, String nonceStr, String packageValue, String signType, String paySign) {}

public record PaymentGatewayResult(
        String payUrl, String qrCode, String prepayId, MiniProgramPaymentParameters miniProgram) {}
```

In mock mode, generate deterministic `prepay_id=mock-{paymentId}` and a non-secret mock signature. Persist the parameters JSON and return it through `PaymentResponse`.

- [ ] **Step 4: Keep simulation environment-protected**

Assert `POST /api/payments/{id}/simulate-success` succeeds only when `gatewayMode` is `mock` or `sandbox` and remains forbidden for `gateway`.

Run: `mvn -q -Dtest=ConfiguredPaymentGatewayTest,PaymentSandboxServiceTest,PaymentApiTest test`

Expected: PASS.

- [ ] **Step 5: Commit mock Mini Program payment support**

```powershell
git add src/main/java/org/example/payment src/main/java/org/example/service/PaymentService.java src/main/java/org/example/web/dto/PaymentResponse.java src/test/java/org/example/payment src/test/java/org/example/service/PaymentSandboxServiceTest.java
git commit -m "feat: add mini program mock payment parameters"
```

### Task 7: Add WeChat Pay API v3 Production Adapter

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/org/example/wechat/WechatPayGateway.java`
- Create: `src/main/java/org/example/wechat/WechatPayCallbackVerifier.java`
- Create: `src/main/java/org/example/web/WechatPayCallbackController.java`
- Modify: `src/main/java/org/example/config/AppProperties.java`
- Modify: `src/main/resources/application-prod.yml`
- Modify: `src/main/java/org/example/service/PaymentService.java`
- Test: `src/test/java/org/example/wechat/WechatPayGatewayTest.java`
- Test: `src/test/java/org/example/web/WechatPayCallbackApiTest.java`

- [ ] **Step 1: Write failing adapter contract tests**

```java
@Test
void shouldMapJsapiPrepayResponseToRequestPaymentFields() {
    when(client.prepay(any())).thenReturn(new PrepayResponse("wx-prepay-1"));
    PaymentGatewayResult result = gateway.createPayment(PaymentChannel.WECHAT, 9L, new BigDecimal("12.80"));
    assertThat(result.prepayId()).isEqualTo("wx-prepay-1");
    assertThat(result.miniProgram().packageValue()).isEqualTo("prepay_id=wx-prepay-1");
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=WechatPayGatewayTest test`

Expected: compilation FAIL because the production adapter does not exist.

- [ ] **Step 3: Add the official SDK dependency and validated configuration**

```xml
<dependency>
  <groupId>com.github.wechatpay-apiv3</groupId>
  <artifactId>wechatpay-java</artifactId>
  <version>0.2.17</version>
</dependency>
```

Add `app.wechat.app-id`, `app.wechat.app-secret`, `app.payment.wechat-mch-id`, `merchant-serial-number`, `private-key-path`, `api-v3-key`, `notify-url`, and `refund-notify-url`. Production startup must fail with a clear configuration error when payment mode is `gateway` and required values are blank.

- [ ] **Step 4: Implement JSAPI prepay and RSA client parameters**

Build a JSAPI request with merchant order number, amount in fen, payer openid, and `https://xwycx.xyz/api/payments/callbacks/wechat`. Use the official SDK signer and verifier; do not implement custom certificate parsing or signature algorithms.

- [ ] **Step 5: Implement raw callback verification before business handling**

`WechatPayCallbackController` receives the raw body and `Wechatpay-*` headers. `WechatPayCallbackVerifier` verifies and decrypts the resource, then creates the existing internal callback command. `PaymentService` rechecks payment ID/order number, amount, channel, merchant number, and callback idempotency before changing payment and customer-order states.

- [ ] **Step 6: Run production compilation and callback tests**

Run: `mvn -q -Dtest=WechatPayGatewayTest,WechatPayCallbackApiTest test`

Run: `mvn -q -Pprod -DskipTests compile`

Expected: both commands PASS.

- [ ] **Step 7: Commit production WeChat Pay support**

```powershell
git add pom.xml src/main/java/org/example/wechat src/main/java/org/example/web/WechatPayCallbackController.java src/main/java/org/example/config/AppProperties.java src/main/resources/application-prod.yml src/main/java/org/example/service/PaymentService.java src/test/java/org/example/wechat src/test/java/org/example/web/WechatPayCallbackApiTest.java
git commit -m "feat: add wechat pay api v3 adapter"
```

### Task 8: Scaffold the Portable Native Mini Program Core

**Files:**
- Create: `wechat-miniprogram/project.config.json`
- Create: `wechat-miniprogram/sitemap.json`
- Create: `wechat-miniprogram/app.js`
- Create: `wechat-miniprogram/app.json`
- Create: `wechat-miniprogram/app.wxss`
- Create: `wechat-miniprogram/config/env.js`
- Create: `wechat-miniprogram/services/request.js`
- Create: `wechat-miniprogram/services/auth.js`
- Create: `wechat-miniprogram/services/products.js`
- Create: `wechat-miniprogram/services/cart.js`
- Create: `wechat-miniprogram/services/addresses.js`
- Create: `wechat-miniprogram/services/orders.js`
- Create: `wechat-miniprogram/services/payments.js`
- Create: `wechat-miniprogram/store/session.js`
- Create: `wechat-miniprogram/platform/login.js`
- Create: `wechat-miniprogram/platform/payment.js`
- Test: `wechat-miniprogram/tests/request.test.js`
- Test: `wechat-miniprogram/tests/session.test.js`

- [ ] **Step 1: Write failing Node tests for token injection and 401 cleanup**

```javascript
test('request injects X-Session-Token and clears expired session', async () => {
  session.set({ token: 'token-1', user: { role: 'CUSTOMER' } })
  wx.request.mockImplementation(({ header, fail }) => {
    expect(header['X-Session-Token']).toBe('token-1')
    fail({ statusCode: 401 })
  })
  await expect(request('/api/auth/me')).rejects.toBeDefined()
  expect(session.get()).toBeNull()
})
```

- [ ] **Step 2: Verify RED**

Run: `node --test wechat-miniprogram/tests/*.test.js`

Expected: FAIL because Mini Program core modules do not exist.

- [ ] **Step 3: Add environment, request, session, login, and payment adapters**

`request.js` prefixes the environment base URL, sends JSON, injects `X-Session-Token`, normalizes `{message}` API errors, and clears session on 401. `platform/login.js` wraps `wx.login`; `platform/payment.js` wraps `wx.requestPayment`. No page may call `wx.request` directly.

- [ ] **Step 4: Add routes and a restrained shared visual system**

Configure tab pages `pages/home/index`, `pages/category/index`, `pages/cart/index`, and `pages/profile/index`. Define stable spacing, color, typography, buttons, empty states, and loading skeletons in `app.wxss`; avoid nested cards and unstable content heights.

- [ ] **Step 5: Run core tests and syntax checks**

Run: `node --test wechat-miniprogram/tests/*.test.js`

Run: `Get-ChildItem wechat-miniprogram -Recurse -Filter *.js | ForEach-Object { node --check $_.FullName }`

Expected: PASS.

- [ ] **Step 6: Commit the Mini Program foundation**

```powershell
git add wechat-miniprogram
git commit -m "feat: scaffold native wechat mini program"
```

### Task 9: Build the Customer Purchase Pages

**Files:**
- Create: `wechat-miniprogram/pages/login/*`
- Create: `wechat-miniprogram/pages/home/*`
- Create: `wechat-miniprogram/pages/category/*`
- Create: `wechat-miniprogram/pages/product-detail/*`
- Create: `wechat-miniprogram/pages/cart/*`
- Create: `wechat-miniprogram/pages/checkout/*`
- Create: `wechat-miniprogram/pages/address-list/*`
- Create: `wechat-miniprogram/pages/address-edit/*`
- Create: `wechat-miniprogram/pages/order-list/*`
- Create: `wechat-miniprogram/pages/order-detail/*`
- Create: `wechat-miniprogram/pages/refund/*`
- Create: `wechat-miniprogram/pages/profile/*`
- Create: `wechat-miniprogram/components/product-card/*`
- Create: `wechat-miniprogram/components/order-status/*`
- Test: `wechat-miniprogram/tests/navigation.test.js`

- [ ] **Step 1: Write failing route and role-navigation tests**

```javascript
test('customer navigation contains purchase flow and excludes admin route', () => {
  const pages = require('../app.json').pages
  expect(pages).toContain('pages/checkout/index')
  expect(pages).toContain('pages/order-detail/index')
  expect(customerMenu({ role: 'CUSTOMER' })).not.toContainEqual(expect.objectContaining({ url: '/pages/admin/home/index' }))
})
```

- [ ] **Step 2: Verify RED**

Run: `node --test wechat-miniprogram/tests/navigation.test.js`

Expected: FAIL because purchase pages are missing.

- [ ] **Step 3: Implement login and session restoration**

The login page presents WeChat login and phone-code login as equal options. Preserve a validated `redirect` page and return there after success. A 401 clears storage and redirects to login; logout clears both server and local sessions.

- [ ] **Step 4: Implement browsing, cart, checkout, and address pages**

Home and category use product APIs with loading, empty, retry, and sold-out states. Product detail uses quantity steppers and add-to-cart. Checkout requires an owned address, shows server-calculated totals, and prevents duplicate submission. Address pages provide create/edit/delete/default behavior.

- [ ] **Step 5: Implement payment, order, and refund pages**

After creating a WeChat payment, mock mode calls the protected simulation endpoint; gateway mode calls `wx.requestPayment` with server-returned fields. Both paths poll order details until `PAID` or a terminal failure. Order pages expose valid actions by status only; refund validates amount and reason.

- [ ] **Step 6: Verify customer flow artifacts**

Run: `node --test wechat-miniprogram/tests/*.test.js`

Run: `Get-ChildItem wechat-miniprogram -Recurse -Filter *.js | ForEach-Object { node --check $_.FullName }`

Expected: PASS, with all paths in `app.json` backed by `.js`, `.json`, `.wxml`, and `.wxss` files.

- [ ] **Step 7: Commit customer pages**

```powershell
git add wechat-miniprogram/pages wechat-miniprogram/components wechat-miniprogram/tests
git commit -m "feat: add mini program customer purchase flow"
```

### Task 10: Build the Role-Gated Administrator Pages

**Files:**
- Create: `wechat-miniprogram/services/admin.js`
- Create: `wechat-miniprogram/pages/admin/home/*`
- Create: `wechat-miniprogram/pages/admin/products/*`
- Create: `wechat-miniprogram/pages/admin/product-edit/*`
- Create: `wechat-miniprogram/pages/admin/inventory/*`
- Create: `wechat-miniprogram/pages/admin/orders/*`
- Create: `wechat-miniprogram/pages/admin/shipment/*`
- Create: `wechat-miniprogram/pages/admin/refunds/*`
- Create: `wechat-miniprogram/pages/admin/payments/*`
- Modify: `src/main/java/org/example/web/AdminController.java`
- Test: `src/test/java/org/example/web/AdminMiniProgramApiTest.java`
- Test: `wechat-miniprogram/tests/admin-guard.test.js`

- [ ] **Step 1: Write failing backend and frontend authorization tests**

```java
@Test
void customerCannotListAdminOrdersOrPayments() throws Exception {
    mockMvc.perform(get("/api/admin/orders").header("X-Session-Token", customerToken))
            .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/payments").header("X-Session-Token", customerToken))
            .andExpect(status().isForbidden());
}
```

```javascript
test('admin guard redirects customer to profile', () => {
  expect(requireAdmin({ role: 'CUSTOMER' })).toBe(false)
  expect(wx.switchTab).toHaveBeenCalledWith({ url: '/pages/profile/index' })
})
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=AdminMiniProgramApiTest test`

Run: `node --test wechat-miniprogram/tests/admin-guard.test.js`

Expected: FAIL because admin list APIs and guard do not exist.

- [ ] **Step 3: Add admin query APIs with existing role enforcement**

Add `GET /api/admin/orders`, `GET /api/admin/refunds`, and `GET /api/admin/payments`. Every method calls `authService.requireAdmin(token)` before querying. Reuse existing product update, stock adjustment, shipment, and refund approval commands.

- [ ] **Step 4: Implement administrator pages and guarded navigation**

Profile shows the workbench entry only for `ADMIN`. Every admin page runs `requireAdmin` in `onShow` before loading data. Pages cover product create/update/on-sale status, stock adjustment, order search and shipment, refund approval, and payment inspection.

- [ ] **Step 5: Run focused verification**

Run: `mvn -q -Dtest=AdminMiniProgramApiTest,CustomerCommerceApiTest test`

Run: `node --test wechat-miniprogram/tests/admin-guard.test.js`

Expected: PASS.

- [ ] **Step 6: Commit administrator pages**

```powershell
git add src/main/java/org/example/web/AdminController.java src/test/java/org/example/web/AdminMiniProgramApiTest.java wechat-miniprogram/services/admin.js wechat-miniprogram/pages/admin wechat-miniprogram/tests/admin-guard.test.js
git commit -m "feat: add mini program admin workbench"
```

### Task 11: Add Production Configuration and Operating Documentation

**Files:**
- Modify: `deploy/nginx.conf`
- Modify: `docs/deployment.md`
- Create: `docs/wechat-miniprogram.md`
- Create: `docs/wechat-pay-v3.md`
- Modify: `README.md`
- Modify: `.gitignore`
- Test: `src/test/java/org/example/config/ProductionConfigurationTest.java`

- [ ] **Step 1: Write a failing production configuration test**

```java
@Test
void gatewayModeRejectsMissingWechatSecrets() {
    assertThatThrownBy(() -> validator.validate(propertiesWithBlankWechatSecrets()))
            .hasMessageContaining("wechat payment configuration");
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=ProductionConfigurationTest test`

Expected: FAIL because production WeChat configuration validation is absent.

- [ ] **Step 3: Complete production configuration and Nginx rules**

Document and configure environment variables for AppID, AppSecret, merchant ID, serial number, private-key path, API v3 key, and callback URLs. Nginx must redirect HTTP to HTTPS, proxy `/api/` to `127.0.0.1:8080`, preserve real IP headers, and use `/etc/letsencrypt/live/xwycx.xyz/` certificate paths.

- [ ] **Step 4: Document developer setup**

`docs/wechat-miniprogram.md` must include importing `wechat-miniprogram/` into WeChat Developer Tools, setting the local base URL, enabling development domain bypass only locally, using code `123456`, and running mock payment. Production docs must explain ICP filing, request-domain allowlisting, website TLS versus merchant certificates, key file permissions, and callback connectivity checks.

- [ ] **Step 5: Ignore generated and secret material**

Add `.superpowers/`, Mini Program local developer settings, private keys, PEM/P12 files, and environment override files to `.gitignore`; do not ignore committed `project.config.json` or source assets.

- [ ] **Step 6: Verify docs and production compilation**

Run: `mvn -q -Dtest=ProductionConfigurationTest test`

Run: `mvn -q -Pprod -DskipTests compile`

Run: `rg -n "AppSecret|API v3|xwycx.xyz|微信开发者工具" docs README.md`

Expected: tests and compile PASS; documentation search finds each required topic without containing real secrets.

- [ ] **Step 7: Commit configuration and docs**

```powershell
git add .gitignore deploy/nginx.conf docs README.md src/test/java/org/example/config/ProductionConfigurationTest.java
git commit -m "docs: add wechat mini program deployment guide"
```

### Task 12: Full Verification and Handoff

**Files:**
- Modify only files needed to fix failures discovered by this task.

- [ ] **Step 1: Run the complete backend suite**

Run:

```powershell
$env:JAVA_HOME=(Resolve-Path .jdk\jdk-21.0.11).Path
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q test
```

Expected: PASS with zero test failures or errors.

- [ ] **Step 2: Compile the production profile**

Run: `mvn -q -Pprod -DskipTests compile`

Expected: PASS.

- [ ] **Step 3: Run all Mini Program static tests**

Run:

```powershell
node --test wechat-miniprogram/tests/*.test.js
Get-ChildItem wechat-miniprogram -Recurse -Filter *.js | ForEach-Object { node --check $_.FullName }
```

Expected: PASS with no syntax errors.

- [ ] **Step 4: Run local API smoke tests**

Start the application and verify:

```text
POST /api/auth/wechat/login
POST /api/auth/sms/login
GET  /api/products
GET  /api/cart
GET  /api/addresses
POST /api/customer-orders
POST /api/customer-orders/{id}/payments
POST /api/payments/{id}/simulate-success
GET  /api/customer-orders/{id}
GET  /api/admin/orders
```

Expected: customer flow reaches `PAID`; customer admin request is 403; admin request succeeds.

- [ ] **Step 5: Verify in WeChat Developer Tools**

Check common phone widths for blank pages, horizontal overflow, overlapping text, unstable card sizes, inaccessible controls, loading states, empty states, API error messages, login redirects, mock payment, order refresh, and administrator route guards.

- [ ] **Step 6: Review the final diff and commit verification fixes**

Run: `git diff --check`

Expected: no whitespace errors. Commit only verified fixes with a focused message such as:

```powershell
git add <verified-files>
git commit -m "test: complete mini program end-to-end verification"
```
