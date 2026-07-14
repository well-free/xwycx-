package org.example.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppProperties;
import org.example.config.ProductionConfigurationValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialWechatPaySdkClientTest {
    @Test
    void shouldFailFastWhenGatewayConfigurationIsMissing() {
        AppProperties properties = new AppProperties();
        properties.getPayment().setMode("gateway");
        assertThatThrownBy(() -> new OfficialWechatPaySdkClient(
                properties, new ObjectMapper(), new ProductionConfigurationValidator()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("wechat app id is not configured");
    }
}
