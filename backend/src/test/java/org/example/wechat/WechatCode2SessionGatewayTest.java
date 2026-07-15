package org.example.wechat;

import org.example.config.AppProperties;
import org.example.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatCode2SessionGatewayTest {
    @Test
    void shouldRejectBlankCredentialsAndUnsafeUrlAtConstruction() {
        AppProperties properties = validProperties();
        properties.getWechat().setAppSecret("");
        assertThatThrownBy(() -> new WechatCode2SessionGateway(properties, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);

        properties.getWechat().setAppSecret("secret");
        properties.getWechat().setCode2SessionUrl("http://api.weixin.qq.com/sns/jscode2session");
        assertThatThrownBy(() -> new WechatCode2SessionGateway(properties, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectAnotherHttpsHostBeforeBuildingRequest() {
        AppProperties properties = validProperties();
        properties.getWechat().setCode2SessionUrl("https://attacker.example/sns/jscode2session");

        assertThatThrownBy(() -> new WechatCode2SessionGateway(properties, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("wechat code2session URL is not allowed");
    }

    @Test
    void shouldRejectMalformedWechatResponse() {
        AppProperties properties = validProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(withSuccess("{\"errcode\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        WechatCode2SessionGateway gateway = new WechatCode2SessionGateway(properties, builder.build());

        assertThatThrownBy(() -> gateway.exchange("code"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("invalid wechat code");
        server.verify();
    }

    @Test
    void shouldTranslateTransportFailureToServiceUnavailable() {
        AppProperties properties = validProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("jscode2session")))
                .andRespond(request -> { throw new IOException("secret transport detail"); });
        WechatCode2SessionGateway gateway = new WechatCode2SessionGateway(properties, builder.build());

        assertThatThrownBy(() -> gateway.exchange("code"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getStatus())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo("wechat service unavailable");
                });
        server.verify();
    }

    private AppProperties validProperties() {
        AppProperties properties = new AppProperties();
        properties.getWechat().setAppId("app-id");
        properties.getWechat().setAppSecret("secret");
        properties.getWechat().setCode2SessionUrl("https://api.weixin.qq.com/sns/jscode2session");
        return properties;
    }
}
