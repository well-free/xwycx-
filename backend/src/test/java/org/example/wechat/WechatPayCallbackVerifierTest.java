package org.example.wechat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatPayCallbackVerifierTest {
    @Test
    void shouldMapVerifiedNotificationToInternalCommand() {
        WechatPaySdkClient client = mock(WechatPaySdkClient.class);
        when(client.verifyNotification("body", "serial", "timestamp", "nonce", "signature"))
                .thenReturn(new WechatPayNotification(
                        9L, "notify-9", "wx-trade-9", new BigDecimal("12.80"), "SUCCESS"));

        var command = new WechatPayCallbackVerifier(client)
                .verify("body", "serial", "timestamp", "nonce", "signature");

        assertThat(command.paymentId()).isEqualTo(9L);
        assertThat(command.notifyId()).isEqualTo("notify-9");
        assertThat(command.amount()).isEqualByComparingTo("12.80");
        assertThat(command.signature()).isEqualTo("wechatpay-v3-verified");
    }
}
