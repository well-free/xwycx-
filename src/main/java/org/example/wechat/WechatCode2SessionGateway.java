package org.example.wechat;

import org.example.config.AppProperties;
import org.example.web.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

@Component
@Profile("!local")
public class WechatCode2SessionGateway implements WechatSessionGateway {
    private final AppProperties properties;
    private final RestClient restClient;

    public WechatCode2SessionGateway(AppProperties properties, RestClient.Builder restClientBuilder) {
        validateConfiguration(properties.getWechat());
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    WechatCode2SessionGateway(AppProperties properties, RestClient restClient) {
        validateConfiguration(properties.getWechat());
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public WechatSession exchange(String code) {
        if (code == null || code.isBlank()) {
            throw BusinessException.badRequest("invalid wechat code");
        }
        AppProperties.Wechat wechat = properties.getWechat();
        Code2SessionResponse response;
        try {
            response = restClient.get()
                    .uri(wechat.getCode2SessionUrl(), uriBuilder -> uriBuilder
                            .queryParam("appid", wechat.getAppId())
                            .queryParam("secret", wechat.getAppSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(Code2SessionResponse.class);
        } catch (RestClientException exception) {
            throw BusinessException.serviceUnavailable("wechat service unavailable");
        }
        if (response == null || (response.errcode() != null && response.errcode() != 0)
                || response.openid() == null || response.openid().isBlank()) {
            throw BusinessException.badRequest("invalid wechat code");
        }
        return new WechatSession(wechat.getAppId(), response.openid(), response.unionid());
    }

    private record Code2SessionResponse(String openid, String unionid, Integer errcode) {
    }

    private static void validateConfiguration(AppProperties.Wechat wechat) {
        if (wechat.getAppId() == null || wechat.getAppId().isBlank()
                || wechat.getAppSecret() == null || wechat.getAppSecret().isBlank()) {
            throw new IllegalStateException("wechat credentials are required");
        }
        try {
            URI uri = URI.create(wechat.getCode2SessionUrl());
            if (!"https".equals(uri.getScheme())
                    || !"api.weixin.qq.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/sns/jscode2session".equals(uri.getPath())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalStateException("wechat code2session URL is not allowed");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("wechat code2session URL is not allowed");
        }
    }
}
