package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.auth.UserRole;
import org.example.config.AppProperties;
import org.example.infrastructure.cache.CacheStore;
import org.example.infrastructure.mybatis.entity.SmsCodeEntity;
import org.example.infrastructure.mybatis.entity.UserEntity;
import org.example.infrastructure.mybatis.entity.UserSessionEntity;
import org.example.infrastructure.mybatis.mapper.SmsCodeMapper;
import org.example.infrastructure.mybatis.mapper.UserMapper;
import org.example.infrastructure.mybatis.mapper.UserSessionMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.sms.SmsCodeGenerator;
import org.example.sms.SmsCodeStore;
import org.example.sms.SmsProvider;
import org.example.sms.SmsSendResult;
import org.example.web.BusinessException;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.SmsLoginRequest;
import org.example.web.dto.SmsSendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final long SESSION_SECONDS = 7 * 24 * 3600L;

    private final UserMapper userMapper;
    private final SmsCodeMapper smsCodeMapper;
    private final UserSessionMapper sessionMapper;
    private final RateLimitService rateLimitService;
    private final Environment environment;
    private final SmsProvider smsProvider;
    private final SmsCodeStore smsCodeStore;
    private final CacheStore cacheStore;
    private final AppProperties properties;

    public AuthService(UserMapper userMapper,
                       SmsCodeMapper smsCodeMapper,
                       UserSessionMapper sessionMapper,
                       RateLimitService rateLimitService,
                       Environment environment,
                       SmsProvider smsProvider,
                       SmsCodeStore smsCodeStore,
                       CacheStore cacheStore,
                       AppProperties properties) {
        this.userMapper = userMapper;
        this.smsCodeMapper = smsCodeMapper;
        this.sessionMapper = sessionMapper;
        this.rateLimitService = rateLimitService;
        this.environment = environment;
        this.smsProvider = smsProvider;
        this.smsCodeStore = smsCodeStore;
        this.cacheStore = cacheStore;
        this.properties = properties;
    }

    public void sendSms(SmsSendRequest request) {
        sendSms(request, "");
    }

    public void sendSms(SmsSendRequest request, String clientIp) {
        String phone = normalizePhone(request.phone());
        applySmsRateLimits(phone, clientIp);
        String codeValue = createSmsCode();
        SmsSendResult result = smsProvider.sendVerificationCode(phone, codeValue);
        smsCodeStore.save(phone, codeValue);

        Instant now = Instant.now();
        SmsCodeEntity code = new SmsCodeEntity();
        code.setId(IdWorker.getId());
        code.setPhone(phone);
        code.setCode(codeValue);
        code.setConsumed(false);
        code.setProvider(properties.getSms().getProvider());
        code.setProviderRequestId(result.requestId());
        code.setStatus(result.status());
        code.setCreatedAt(now);
        code.setExpireAt(now.plusSeconds(properties.getSms().getCodeTtlSeconds()));
        smsCodeMapper.insert(code);
        log.info("sms code sent phone={} provider={} requestId={}",
                phone, properties.getSms().getProvider(), result.requestId());
    }

    public AuthLoginResponse login(SmsLoginRequest request) {
        SmsVerification verification = verifySmsCode(request.phone(), request.code());
        UserEntity user = findOrCreateUser(verification.phone());
        AuthLoginResponse response = createSession(user);
        completeSmsVerification(verification);
        return response;
    }

    AuthLoginResponse createSession(UserEntity user) {
        return createSession(user, null);
    }

    AuthLoginResponse createSession(UserEntity user, Long wechatIdentityId) {
        UserSessionEntity session = new UserSessionEntity();
        session.setId(IdWorker.getId());
        session.setUserId(user.getId());
        session.setWechatIdentityId(wechatIdentityId);
        session.setToken(UUID.randomUUID().toString().replace("-", ""));
        session.setCreatedAt(Instant.now());
        session.setExpireAt(session.getCreatedAt().plusSeconds(SESSION_SECONDS));
        sessionMapper.insert(session);
        String phoneForLog = WechatAuthService.isReservedPhone(user.getPhone()) ? "wechat-unbound" : user.getPhone();
        log.info("user logged in userId={} phone={} role={}", user.getId(), phoneForLog, user.getRole());
        return new AuthLoginResponse(session.getToken(), toResponse(user));
    }

    public AuthUserResponse requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw BusinessException.unauthorized("login required");
        }
        return toResponse(requireUserEntity(token));
    }

    UserEntity requireUserEntity(String token) {
        UserSessionEntity session = loadValidSession(token);
        UserEntity user = userMapper.selectById(session.getUserId());
        if (user == null) {
            throw BusinessException.unauthorized("login required");
        }
        return user;
    }

    UserSessionEntity loadValidSession(String token) {
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
        return session;
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
        return properties.getSms().getLocalCode().equals(code)
                && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    private String createSmsCode() {
        if (isLocalProfile()) {
            return properties.getSms().getLocalCode();
        }
        return SmsCodeGenerator.generate(properties.getSms().getCodeLength());
    }

    private void applySmsRateLimits(String phone, String clientIp) {
        rateLimitService.acquire("sms:send:" + phone, 1L, 60L);
        if (!isLocalProfile()) {
            rejectIfPresent("sms:cooldown:" + phone);
            increaseCounter("sms:daily:" + phone, 10, Duration.ofDays(1));
            if (clientIp != null && !clientIp.isBlank()) {
                increaseCounter("sms:ip:" + clientIp, 20, Duration.ofMinutes(1));
            }
            cacheStore.put("sms:cooldown:" + phone, "1", Duration.ofSeconds(60));
        }
    }

    private void rejectIfPresent(String key) {
        if (cacheStore.get(key).isPresent()) {
            throw BusinessException.tooManyRequests("too many requests");
        }
    }

    private void increaseCounter(String key, int max, Duration ttl) {
        int count = cacheStore.get(key).map(Integer::parseInt).orElse(0);
        if (count >= max) {
            throw BusinessException.tooManyRequests("too many requests");
        }
        cacheStore.put(key, String.valueOf(count + 1), ttl);
    }

    private boolean isLocalProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    SmsVerification verifySmsCode(String rawPhone, String code) {
        String phone = normalizePhone(rawPhone);
        boolean verified = smsCodeStore.consume(phone, code);
        if (!verified && !canUseLocalFixedCode(code)) {
            throw BusinessException.badRequest("invalid sms code");
        }
        if (!verified) {
            log.info("local fixed sms code accepted phone={}", phone);
        }
        SmsCodeEntity audit = verified ? findLatestUnconsumedSmsAudit(phone, code) : null;
        Duration remainingTtl = audit == null
                ? Duration.ZERO
                : Duration.between(Instant.now(), audit.getExpireAt());
        if (remainingTtl.isNegative()) {
            remainingTtl = Duration.ZERO;
        }
        return new SmsVerification(phone, code, verified,
                audit == null ? null : audit.getId(), remainingTtl);
    }

    void completeSmsVerification(SmsVerification verification) {
        if (verification.storeConsumed() && verification.auditId() != null) {
            SmsCodeEntity audit = new SmsCodeEntity();
            audit.setId(verification.auditId());
            audit.setConsumed(true);
            smsCodeMapper.updateById(audit);
        }
    }

    void restoreSmsVerification(SmsVerification verification) {
        if (verification.storeConsumed()) {
            smsCodeStore.restoreIfAbsent(
                    verification.phone(), verification.code(), verification.remainingTtl());
        }
    }

    private SmsCodeEntity findLatestUnconsumedSmsAudit(String phone, String code) {
        return smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCodeEntity>()
                .eq(SmsCodeEntity::getPhone, phone)
                .eq(SmsCodeEntity::getCode, code)
                .eq(SmsCodeEntity::isConsumed, false)
                .orderByDesc(SmsCodeEntity::getCreatedAt)
                .last("limit 1"));
    }

    void invalidateSessions(long userId) {
        sessionMapper.delete(new LambdaQueryWrapper<UserSessionEntity>()
                .eq(UserSessionEntity::getUserId, userId));
    }

    String normalizePhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (!value.matches("^1\\d{10}$")) {
            throw BusinessException.badRequest("invalid phone");
        }
        return value;
    }

    AuthUserResponse toResponse(UserEntity user) {
        String phone = WechatAuthService.isReservedPhone(user.getPhone()) ? "" : user.getPhone();
        return new AuthUserResponse(user.getId(), phone, UserRole.valueOf(user.getRole()));
    }

    record SmsVerification(String phone, String code, boolean storeConsumed,
                           Long auditId, Duration remainingTtl) {
    }
}
