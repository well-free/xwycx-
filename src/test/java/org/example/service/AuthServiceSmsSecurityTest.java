package org.example.service;

import org.example.config.AppProperties;
import org.example.infrastructure.cache.CacheStore;
import org.example.infrastructure.mybatis.mapper.SmsCodeMapper;
import org.example.infrastructure.mybatis.mapper.UserMapper;
import org.example.infrastructure.mybatis.mapper.UserSessionMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.sms.SmsCodeStore;
import org.example.sms.SmsProvider;
import org.example.web.BusinessException;
import org.example.web.dto.SmsLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthServiceSmsSecurityTest {
    @Test
    void shouldRejectFixedLocalCodeInProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AppProperties properties = new AppProperties();
        properties.getSms().setProvider("aliyun");
        AuthService authService = new AuthService(
                mock(UserMapper.class),
                mock(SmsCodeMapper.class),
                mock(UserSessionMapper.class),
                mock(RateLimitService.class),
                environment,
                mock(SmsProvider.class),
                mock(SmsCodeStore.class),
                mock(CacheStore.class),
                properties);

        assertThatThrownBy(() -> authService.login(new SmsLoginRequest("13800000011", "123456")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("invalid sms code");
    }
}
