package org.example.web;

import com.jayway.jsonpath.JsonPath;
import org.example.infrastructure.rate.RateLimitService;
import org.example.sms.SmsCodeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class WechatAuthApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsCodeStore smsCodeStore;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    void shouldIgnoreForwardedHeadersFromUntrustedRemoteAddress() throws Exception {
        mockMvc.perform(post("/api/auth/wechat/login")
                        .header("X-Forwarded-For", "203.0.113.10, 10.0.0.2")
                        .header("X-Real-IP", "198.51.100.20")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.30");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"rate-limit-code\"}"))
                .andExpect(status().isOk());

        verify(rateLimitService).acquire("auth:wechat:login:ip:192.0.2.30", 10L, 1L);
    }

    @Test
    void shouldUseSanitizedRealIpBehindLoopbackProxy() throws Exception {
        mockMvc.perform(post("/api/auth/wechat/login")
                        .header("X-Forwarded-For", "203.0.113.10")
                        .header("X-Real-IP", " 198.51.100.20 ")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"proxy-code\"}"))
                .andExpect(status().isOk());

        verify(rateLimitService).acquire("auth:wechat:login:ip:198.51.100.20", 10L, 1L);
    }

    @Test
    void shouldLoginAndBindPhoneThroughHttp() throws Exception {
        String loginJson = mockMvc.perform(post("/api/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"api-code\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginJson, "$.token");
        assertThat(JsonPath.read(loginJson, "$.user.phone").toString()).isEmpty();

        smsCodeStore.save("13800000020", "654321");
        String bindJson = mockMvc.perform(post("/api/auth/wechat/bind-phone")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000020\",\"code\":\"654321\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(bindJson, "$.token").toString()).isNotBlank();
        assertThat(JsonPath.read(bindJson, "$.user.phone").toString()).isEqualTo("13800000020");
    }

    @Test
    void shouldRejectBindWithoutSessionToken() throws Exception {
        mockMvc.perform(post("/api/auth/wechat/bind-phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000020\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldValidateWechatRequests() throws Exception {
        mockMvc.perform(post("/api/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + "a".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/wechat/bind-phone")
                        .header("X-Session-Token", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"123\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/wechat/bind-phone")
                        .header("X-Session-Token", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000020\",\"code\":\"12345678901234567\"}"))
                .andExpect(status().isBadRequest());
    }
}
