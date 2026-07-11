package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.auth.UserRole;
import org.example.infrastructure.mybatis.entity.SmsCodeEntity;
import org.example.infrastructure.mybatis.entity.UserEntity;
import org.example.infrastructure.mybatis.entity.UserSessionEntity;
import org.example.infrastructure.mybatis.mapper.SmsCodeMapper;
import org.example.infrastructure.mybatis.mapper.UserMapper;
import org.example.infrastructure.mybatis.mapper.UserSessionMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.web.BusinessException;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.SmsLoginRequest;
import org.example.web.dto.SmsSendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String LOCAL_SMS_CODE = "123456";
    private static final long SESSION_SECONDS = 7 * 24 * 3600L;

    private final UserMapper userMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final UserSessionMapper sessionMapper;
    private final RateLimitService rateLimitService;
    private final Environment environment;

    public AuthService(UserMapper userMapper,
                       SmsCodeMapper smsCodeMapper,
                       UserSessionMapper sessionMapper,
                       RateLimitService rateLimitService,
                       Environment environment) {
        this.userMapper = userMapper;
        this.smsCodeMapper = smsCodeMapper;
        this.sessionMapper = sessionMapper;
        this.rateLimitService = rateLimitService;
        this.environment = environment;
    }

    public void sendSms(SmsSendRequest request) {
        String phone = normalizePhone(request.phone());
        rateLimitService.acquire("sms:send:" + phone, 1L, 60L);
        SmsCodeEntity code = new SmsCodeEntity();
        code.setId(IdWorker.getId());
        code.setPhone(phone);
        code.setCode(LOCAL_SMS_CODE);
        code.setConsumed(false);
        code.setCreatedAt(Instant.now());
        code.setExpireAt(code.getCreatedAt().plusSeconds(300));
        smsCodeMapper.insert(code);
        log.info("sms code generated phone={}", phone);
    }

    public AuthLoginResponse login(SmsLoginRequest request) {
        String phone = normalizePhone(request.phone());
        SmsCodeEntity code = smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCodeEntity>()
                .eq(SmsCodeEntity::getPhone, phone)
                .eq(SmsCodeEntity::getCode, request.code())
                .eq(SmsCodeEntity::isConsumed, false)
                .ge(SmsCodeEntity::getExpireAt, Instant.now())
                .orderByDesc(SmsCodeEntity::getCreatedAt)
                .last("limit 1"));
        if (code == null) {
            if (!canUseLocalFixedCode(request.code())) {
                throw BusinessException.badRequest("invalid sms code");
            }
        } else {
            code.setConsumed(true);
            smsCodeMapper.updateById(code);
        }

        UserEntity user = findOrCreateUser(phone);
        UserSessionEntity session = new UserSessionEntity();
        session.setId(IdWorker.getId());
        session.setUserId(user.getId());
        session.setToken(UUID.randomUUID().toString().replace("-", ""));
        session.setCreatedAt(Instant.now());
        session.setExpireAt(session.getCreatedAt().plusSeconds(SESSION_SECONDS));
        sessionMapper.insert(session);
        log.info("user logged in userId={} phone={} role={}", user.getId(), user.getPhone(), user.getRole());
        return new AuthLoginResponse(session.getToken(), toResponse(user));
    }

    public AuthUserResponse requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw BusinessException.unauthorized("login required");
        }
        UserSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<UserSessionEntity>()
                .eq(UserSessionEntity::getToken, token)
                .ge(UserSessionEntity::getExpireAt, Instant.now())
                .last("limit 1"));
        if (session == null) {
            throw BusinessException.unauthorized("login required");
        }
        UserEntity user = userMapper.selectById(session.getUserId());
        if (user == null) {
            throw BusinessException.unauthorized("login required");
        }
        return toResponse(user);
    }

    public AuthUserResponse requireAdmin(String token) {
        AuthUserResponse user = requireUser(token);
        if (user.role() != UserRole.ADMIN) {
            throw BusinessException.forbidden("admin role required");
        }
        return user;
    }

    public AuthUserResponse me(String token) {
        return requireUser(token);
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        UserSessionEntity session = sessionMapper.selectOne(new LambdaQueryWrapper<UserSessionEntity>()
                .eq(UserSessionEntity::getToken, token)
                .last("limit 1"));
        if (session != null) {
            sessionMapper.deleteById(session.getId());
        }
    }

    private UserEntity findOrCreateUser(String phone) {
        UserEntity existing = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getPhone, phone)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(IdWorker.getId());
        user.setPhone(phone);
        user.setRole("13900000000".equals(phone) ? UserRole.ADMIN.name() : UserRole.CUSTOMER.name());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    private boolean canUseLocalFixedCode(String code) {
        return LOCAL_SMS_CODE.equals(code)
                && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    private String normalizePhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (!value.matches("^1\\d{10}$")) {
            throw BusinessException.badRequest("invalid phone");
        }
        return value;
    }

    private AuthUserResponse toResponse(UserEntity user) {
        return new AuthUserResponse(user.getId(), user.getPhone(), UserRole.valueOf(user.getRole()));
    }
}
