package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.example.infrastructure.mq.OrderEventPublisher;
import org.example.infrastructure.mq.OrderTimeoutScheduler;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
import org.example.infrastructure.rate.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:customer_commerce_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
})
@AutoConfigureMockMvc
class CustomerCommerceApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    @MockitoBean
    private OrderTimeoutScheduler orderTimeoutScheduler;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private CustomerOrderTimeoutDispatcher customerOrderTimeoutDispatcher;

    @BeforeEach
    void setUp() {
        doNothing().when(rateLimitService).acquire(anyString(), anyLong(), anyLong());
    }

    @Test
    void shouldServeSeparateLoginPageAndKeepMainPageAsWorkspace() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login.html?redirect=/index.html"));

        String loginHtml = mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(loginHtml).contains("id=\"root\"");
        assertThat(loginHtml).contains("type=\"module\"");
        assertThat(loginHtml).contains("login-page");

        String indexHtml = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(indexHtml).contains("id=\"root\"");
        assertThat(indexHtml).contains("type=\"module\"");

        String productsHtml = mockMvc.perform(get("/products.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(productsHtml).contains("id=\"root\"");

        String ordersHtml = mockMvc.perform(get("/orders.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(ordersHtml).contains("id=\"root\"");

        String adminHtml = mockMvc.perform(get("/admin.html"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(adminHtml).contains("admin-only-page");
        assertThat(adminHtml).contains("id=\"root\"");
    }

    @Test
    void shouldLoginCreateCustomerOrderPayAndRefund() throws Exception {
        String token = login("13800000001");
        long addressId = createAddress(token, "Alice");

        mockMvc.perform(post("/api/cart/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":3}"))
                .andExpect(status().isOk());

        String productsJson = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.read(productsJson, "$.items[0].sku").toString()).isEqualTo("MASK-50");

        String orderJson = mockMvc.perform(post("/api/customer-orders")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{"productId": 1, "quantity": 3}],
                                  "addressId": %d,
                                  "remark": "工作日配送"
                                }
                                """.formatted(addressId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long orderId = ((Number) JsonPath.read(orderJson, "$.id")).longValue();
        assertThat(JsonPath.read(orderJson, "$.status").toString()).isEqualTo("PENDING_PAYMENT");
        assertThat(((Number) JsonPath.read(orderJson, "$.totalAmount")).doubleValue()).isEqualTo(38.40d);
        assertThat(JsonPath.read(orderJson, "$.shippingAddress.receiverName").toString()).isEqualTo("Alice");
        assertThat(JsonPath.read(orderJson, "$.shippingAddress.province").toString()).isEqualTo("Zhejiang");
        assertThat(JsonPath.read(orderJson, "$.shippingAddress.detail").toString()).isEqualTo("No. 1 Road");

        String cartJson = mockMvc.perform(get("/api/cart")
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(((Number) JsonPath.read(cartJson, "$.count")).longValue()).isZero();

        String paymentJson = mockMvc.perform(post("/api/customer-orders/{id}/payments", orderId)
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"ALIPAY\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long paymentId = ((Number) JsonPath.read(paymentJson, "$.id")).longValue();

        mockMvc.perform(post("/api/payments/callbacks/alipay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentId": %d,
                                  "notifyId": "customer-notify-1",
                                  "channelTradeNo": "customer-trade-1",
                                  "amount": 38.40,
                                  "status": "SUCCESS",
                                  "signature": "mock-signature"
                                }
                                """.formatted(paymentId)))
                .andExpect(status().isOk());

        String paidOrderJson = mockMvc.perform(get("/api/customer-orders/{id}", orderId)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.read(paidOrderJson, "$.status").toString()).isEqualTo("PAID");

        mockMvc.perform(post("/api/customer-orders/{id}/refunds", orderId)
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 38.40,
                                  "reason": "customer refund"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectCustomerOrderWithoutLoginAndWhenStockInsufficient() throws Exception {
        mockMvc.perform(post("/api/customer-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{"productId": 1, "quantity": 1}],
                                  "addressId": 1
                                }
                                """))
                .andExpect(status().isUnauthorized());

        String token = login("13800000002");
        long addressId = createAddress(token, "Stock Test");
        mockMvc.perform(post("/api/customer-orders")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{"productId": 1, "quantity": 999999}],
                                  "addressId": %d
                                }
                                """.formatted(addressId)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAllowLocalFixedSmsCodeLoginWithoutSendingSmsFirst() throws Exception {
        String loginJson = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000009\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.read(loginJson, "$.token").toString()).isNotBlank();
        assertThat(JsonPath.read(loginJson, "$.user.phone").toString()).isEqualTo("13800000009");
    }

    @Test
    void shouldProtectAdminApisAndAllowAdminShipment() throws Exception {
        String customerToken = login("13800000003");
        mockMvc.perform(post("/api/admin/products")
                        .header("X-Session-Token", customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "GLOVE-100",
                                  "name": "一次性手套 100只装",
                                  "price": 18.80,
                                  "stock": 200
                                }
                                """))
                .andExpect(status().isForbidden());

        String adminToken = login("13900000000");
        mockMvc.perform(post("/api/admin/products")
                        .header("X-Session-Token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "GLOVE-100",
                                  "name": "一次性手套 100只装",
                                  "price": 18.80,
                                  "stock": 200
                                }
                                """))
                .andExpect(status().isOk());
    }

    private String login(String phone) throws Exception {
        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\"}".formatted(phone)))
                .andExpect(status().isOk());

        String loginJson = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"123456\"}".formatted(phone)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(loginJson, "$.token");
    }

    private long createAddress(String token, String receiverName) throws Exception {
        String addressJson = mockMvc.perform(post("/api/addresses")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "receiverName": "%s",
                                  "receiverPhone": "13800000001",
                                  "province": "Zhejiang",
                                  "city": "Hangzhou",
                                  "district": "Xihu",
                                  "detail": "No. 1 Road",
                                  "defaultAddress": true
                                }
                                """.formatted(receiverName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(addressJson, "$.id")).longValue();
    }
}
