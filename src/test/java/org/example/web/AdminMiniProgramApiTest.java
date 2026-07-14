package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.example.infrastructure.mq.CustomerOrderTimeoutDispatcher;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "app.redis.enabled=false", "app.mq.enabled=false" })
@AutoConfigureMockMvc
class AdminMiniProgramApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean private OrderEventPublisher orderEventPublisher;
    @MockBean private OrderTimeoutScheduler orderTimeoutScheduler;
    @MockBean private CustomerOrderTimeoutDispatcher customerOrderTimeoutDispatcher;
    @MockBean private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        doNothing().when(rateLimitService).acquire(anyString(), anyLong(), anyLong());
    }

    @Test
    void customerCannotListAdminOrdersRefundsOrPayments() throws Exception {
        String token = login("13800000888");
        mockMvc.perform(get("/api/admin/orders").header("X-Session-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/refunds").header("X-Session-Token", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/payments").header("X-Session-Token", token)).andExpect(status().isForbidden());
    }

    @Test
    void adminCanListOperationalData() throws Exception {
        String token = login("13900000000");
        mockMvc.perform(get("/api/admin/orders").header("X-Session-Token", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/refunds").header("X-Session-Token", token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/payments").header("X-Session-Token", token)).andExpect(status().isOk());
    }

    private String login(String phone) throws Exception {
        String body = mockMvc.perform(post("/api/auth/sms/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"123456\"}".formatted(phone)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
