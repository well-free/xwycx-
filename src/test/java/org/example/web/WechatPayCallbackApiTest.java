package org.example.web;

import org.example.payment.PaymentChannel;
import org.example.service.PaymentService;
import org.example.wechat.WechatPayCallbackVerifier;
import org.example.web.dto.PaymentCallbackRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WechatPayCallbackController.class)
@TestPropertySource(properties = "app.payment.mode=gateway")
class WechatPayCallbackApiTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WechatPayCallbackVerifier verifier;

    @MockBean
    private PaymentService paymentService;

    @Test
    void shouldVerifyRawWechatCallbackBeforeBusinessHandling() throws Exception {
        PaymentCallbackRequest command = new PaymentCallbackRequest(
                9L, "notify-9", "wx-trade-9", new BigDecimal("12.80"), "SUCCESS", "verified");
        when(verifier.verify(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(command);

        mockMvc.perform(post("/api/payments/callbacks/wechat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Wechatpay-Serial", "serial-1")
                        .header("Wechatpay-Timestamp", "1710000000")
                        .header("Wechatpay-Nonce", "nonce-1")
                        .header("Wechatpay-Signature", "signature-1")
                        .content("{\"id\":\"notify-9\",\"resource\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(paymentService).handleVerifiedCallback(PaymentChannel.WECHAT, command);
    }
}
