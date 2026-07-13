package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.example.infrastructure.mq.OrderEventPublisher;
import org.example.infrastructure.mq.OrderTimeoutScheduler;
import org.example.infrastructure.rate.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class PaymentApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @MockBean
    private OrderTimeoutScheduler orderTimeoutScheduler;

    @MockBean
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        doNothing().when(rateLimitService).acquire(anyString(), anyLong(), anyLong());
    }

    @Test
    void shouldCreateCallbackQueryAndRefundPaymentThroughHttp() throws Exception {
        String adminToken = loginAdmin();
        long orderId = createOrder();

        String paymentBody = """
                {
                  "orderId": %d,
                  "channel": "ALIPAY"
                }
                """.formatted(orderId);
        String paymentJson = mockMvc.perform(post("/api/payments")
                        .header("X-Session-Token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long paymentId = ((Number) JsonPath.read(paymentJson, "$.id")).longValue();
        assertThat(JsonPath.read(paymentJson, "$.status").toString()).isEqualTo("PAYING");

        String callbackBody = """
                {
                  "paymentId": %d,
                  "notifyId": "api-notify-1",
                  "channelTradeNo": "api-trade-1",
                  "amount": 20.00,
                  "status": "SUCCESS",
                  "signature": "mock-signature"
                }
                """.formatted(paymentId);
        mockMvc.perform(post("/api/payments/callbacks/ALIPAY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(callbackBody))
                .andExpect(status().isOk());

        String queryJson = mockMvc.perform(get("/api/payments/{id}", paymentId)
                        .header("X-Session-Token", adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.read(queryJson, "$.status").toString()).isEqualTo("SUCCESS");

        mockMvc.perform(post("/api/payments/{id}/refunds", paymentId)
                        .header("X-Session-Token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20.00,
                                  "reason": "api refund"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/payments")
                        .header("X-Session-Token", adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/payments/{id}/refunds", paymentId)
                        .header("X-Session-Token", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectCallbackWithInvalidSignature() throws Exception {
        String adminToken = loginAdmin();
        long orderId = createOrder();
        String paymentJson = mockMvc.perform(post("/api/payments")
                        .header("X-Session-Token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": %d,
                                  "channel": "WECHAT"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long paymentId = ((Number) JsonPath.read(paymentJson, "$.id")).longValue();

        mockMvc.perform(post("/api/payments/callbacks/WECHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentId": %d,
                                  "notifyId": "api-notify-2",
                                  "channelTradeNo": "api-trade-2",
                                  "amount": 20.00,
                                  "status": "SUCCESS",
                                  "signature": "bad"
                                }
                """.formatted(paymentId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldSimulateSuccessfulPaymentForMockOrSandboxMode() throws Exception {
        String adminToken = loginAdmin();
        long orderId = createOrder();
        String paymentJson = mockMvc.perform(post("/api/payments")
                        .header("X-Session-Token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": %d,
                                  "channel": "ALIPAY"
                                }
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long paymentId = ((Number) JsonPath.read(paymentJson, "$.id")).longValue();

        String simulatedJson = mockMvc.perform(post("/api/payments/{id}/simulate-success", paymentId)
                        .header("X-Session-Token", adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.read(simulatedJson, "$.status").toString()).isEqualTo("SUCCESS");
        assertThat(JsonPath.read(simulatedJson, "$.channelTradeNo").toString()).contains("SIM-ALIPAY-");
    }

    private long createOrder() throws Exception {
        String createResponse = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "MASK-50",
                                  "side": "BUY",
                                  "price": 10.00,
                                  "quantity": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(createResponse, "$.order.id")).longValue();
    }

    private String loginAdmin() throws Exception {
        mockMvc.perform(post("/api/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900000000\"}"))
                .andExpect(status().isOk());
        String loginJson = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900000000\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(loginJson, "$.token");
    }
}
