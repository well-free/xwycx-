package org.example.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialWechatPaySdkClientTest {
    @Test
    void shouldFailFastWhenGatewayConfigurationIsMissing() {
        assertThatThrownBy(() -> new OfficialWechatPaySdkClient(new AppProperties(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("wechat app id is not configured");
    }
}
