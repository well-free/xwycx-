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
class OrderApiTest {
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
    void shouldCreateCancelAndQueryOrdersThroughHttp() throws Exception {
        String createBody = """
                {
                  "symbol": "MASK-50",
                  "side": "BUY",
                  "price": 100.00,
                  "quantity": 2
                }
                """;

        var createResponse = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Number orderIdValue = JsonPath.read(createResponse, "$.order.id");
        long orderId = orderIdValue.longValue();

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        var orderResponse = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.read(orderResponse, "$.status").toString()).isEqualTo("CANCELED");

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectInvalidOrderPayload() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "",
                                  "side": "BUY",
                                  "price": -1,
                                  "quantity": 0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
