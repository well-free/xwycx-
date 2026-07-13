package org.example.service;

import org.example.auth.UserRole;
import org.example.infrastructure.mybatis.entity.UserEntity;
import org.example.infrastructure.mybatis.entity.SmsCodeEntity;
import org.example.infrastructure.mybatis.entity.WechatIdentityEntity;
import org.example.infrastructure.mybatis.mapper.SmsCodeMapper;
import org.example.infrastructure.mybatis.mapper.WechatIdentityMapper;
import org.example.infrastructure.mybatis.mapper.UserMapper;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.rate.RateLimitService;
import org.example.sms.SmsCodeStore;
import org.example.web.BusinessException;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.SmsLoginRequest;
import org.example.web.dto.WechatBindPhoneRequest;
import org.example.web.dto.WechatLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@ActiveProfiles("local")
@Transactional
@Import(WechatAuthServiceTest.RecordingLockConfiguration.class)
class WechatAuthServiceTest {
    @Autowired
    private WechatAuthService wechatAuthService;

    @Autowired
    private AuthService authService;

    @Autowired
    private SmsCodeStore smsCodeStore;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WechatIdentityMapper identityMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingLockService recordingLockService;

    @SpyBean
    private SmsCodeMapper smsCodeMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private RateLimitService rateLimitService;

    @Test
    void shouldReuseUserForSameAppidAndOpenid() {
        AuthLoginResponse first = wechatAuthService.login(new WechatLoginRequest("reuse-code"));
        AuthLoginResponse second = wechatAuthService.login(new WechatLoginRequest("reuse-code"));

        assertThat(second.user().id()).isEqualTo(first.user().id());
        assertThat(second.user().phone()).isEmpty();
        assertThat(userMapper.selectById(first.user().id()).getPhone())
                .startsWith("wx_")
                .hasSizeLessThanOrEqualTo(32);
    }

    @Test
    void shouldBindVerifiedNewPhone() {
        AuthLoginResponse login = wechatAuthService.login(new WechatLoginRequest("new-phone-code"));
        smsCodeStore.save("13800000018", "654321");

        AuthLoginResponse bound = wechatAuthService.bindPhone(
                login.token(), new WechatBindPhoneRequest("13800000018", "654321"));

        assertThat(bound.user().id()).isEqualTo(login.user().id());
        assertThat(bound.user().phone()).isEqualTo("13800000018");
        assertThat(bound.user().role()).isEqualTo(UserRole.CUSTOMER);
        assertThatThrownBy(() -> wechatAuthService.bindPhone(
                bound.token(), new WechatBindPhoneRequest("13800000018", "654321")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("invalid sms code");
    }

    @Test
    void shouldReassignIdentityToExistingSmsUserWithoutChangingRole() {
        AuthLoginResponse existing = authService.login(new SmsLoginRequest("13900000000", "123456"));
        AuthLoginResponse temporary = wechatAuthService.login(new WechatLoginRequest("existing-phone-code"));
        smsCodeStore.save("13900000000", "654321");

        AuthLoginResponse bound = wechatAuthService.bindPhone(
                temporary.token(), new WechatBindPhoneRequest("13900000000", "654321"));

        assertThat(bound.user().id()).isEqualTo(existing.user().id());
        assertThat(bound.user().role()).isEqualTo(UserRole.ADMIN);
        assertThat(userMapper.selectById(temporary.user().id())).isNull();
        assertThatThrownBy(() -> authService.me(temporary.token()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("login required");

        AuthLoginResponse repeated = wechatAuthService.login(new WechatLoginRequest("existing-phone-code"));
        assertThat(repeated.user().id()).isEqualTo(existing.user().id());
    }

    @Test
    void shouldRejectInvalidPhoneCode() {
        AuthLoginResponse login = wechatAuthService.login(new WechatLoginRequest("invalid-bind-code"));

        assertThatThrownBy(() -> wechatAuthService.bindPhone(
                login.token(), new WechatBindPhoneRequest("13800000019", "000000")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("invalid sms code");
    }

    @Test
    void shouldTransferCartAddressesAndOrdersWhenMergingReservedUser() {
        AuthLoginResponse target = authService.login(new SmsLoginRequest("13800000021", "123456"));
        AuthLoginResponse source = wechatAuthService.login(new WechatLoginRequest("merge-data-code"));
        long now = System.currentTimeMillis();
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                now + 1, target.user().id(), 1L, 2L);
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                now + 2, source.user().id(), 1L, 3L);
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                now + 3, source.user().id(), 2L, 4L);
        jdbcTemplate.update("insert into shipping_addresses values (?, ?, ?, ?, '', '', '', ?, false, current_timestamp, current_timestamp)",
                now + 4, source.user().id(), "Receiver", "13800000021", "Address");
        jdbcTemplate.update("insert into customer_orders (id, order_no, user_id, address_id, total_amount, status, version, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 10.00, 'PENDING_PAYMENT', 0, current_timestamp, current_timestamp)",
                now + 5, "MERGE-" + now, source.user().id(), now + 4);
        smsCodeStore.save("13800000021", "654321");

        wechatAuthService.bindPhone(source.token(), new WechatBindPhoneRequest("13800000021", "654321"));

        assertThat(jdbcTemplate.queryForObject(
                "select quantity from cart_items where user_id=? and product_id=1", Long.class, target.user().id()))
                .isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from cart_items where product_id=2", Long.class)).isEqualTo(target.user().id());
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from shipping_addresses where id=?", Long.class, now + 4)).isEqualTo(target.user().id());
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from customer_orders where id=?", Long.class, now + 5)).isEqualTo(target.user().id());
        assertThat(userMapper.selectById(source.user().id())).isNull();
    }

    @Test
    void shouldBindOnlyIdentityRecordedOnWechatSession() {
        AuthLoginResponse sourceLogin = wechatAuthService.login(new WechatLoginRequest("identity-one"));
        WechatIdentityEntity firstIdentity = identityMapper.selectOne(null);
        UserEntity source = userMapper.selectById(sourceLogin.user().id());

        WechatIdentityEntity secondIdentity = new WechatIdentityEntity();
        secondIdentity.setId(firstIdentity.getId() + 1);
        secondIdentity.setUserId(source.getId());
        secondIdentity.setAppid("local-app");
        secondIdentity.setOpenid("local-identity-two");
        secondIdentity.setCreatedAt(firstIdentity.getCreatedAt());
        secondIdentity.setUpdatedAt(firstIdentity.getUpdatedAt());
        identityMapper.insert(secondIdentity);
        AuthLoginResponse secondSession = authService.createSession(source, secondIdentity.getId());
        AuthLoginResponse target = authService.login(new SmsLoginRequest("13900000000", "123456"));
        smsCodeStore.save("13900000000", "654321");

        wechatAuthService.bindPhone(secondSession.token(), new WechatBindPhoneRequest("13900000000", "654321"));

        assertThat(identityMapper.selectById(secondIdentity.getId()).getUserId()).isEqualTo(target.user().id());
        assertThat(identityMapper.selectById(firstIdentity.getId()).getUserId()).isEqualTo(source.getId());
        assertThat(userMapper.selectById(source.getId())).isNotNull();
    }

    @Test
    void shouldRejectPhoneBindingFromSmsSession() {
        AuthLoginResponse smsLogin = authService.login(new SmsLoginRequest("13800000023", "123456"));
        smsCodeStore.save("13800000024", "654321");

        assertThatThrownBy(() -> wechatAuthService.bindPhone(
                smsLogin.token(), new WechatBindPhoneRequest("13800000024", "654321")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("wechat login required");
    }

    @Test
    void shouldRestoreConsumedCodeWhenMergeTransactionFails() {
        AuthLoginResponse target = authService.login(new SmsLoginRequest("13800000025", "123456"));
        AuthLoginResponse source = wechatAuthService.login(new WechatLoginRequest("rollback-code"));
        long id = System.currentTimeMillis();
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                id + 1, target.user().id(), 1L, Long.MAX_VALUE);
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                id + 2, source.user().id(), 1L, 1L);
        smsCodeMapper.insert(smsAudit("13800000025", "654321", Instant.now().plusSeconds(30)));
        smsCodeStore.save("13800000025", "654321");

        assertThatThrownBy(() -> wechatAuthService.bindPhone(
                source.token(), new WechatBindPhoneRequest("13800000025", "654321")))
                .isInstanceOf(ArithmeticException.class);

        assertThat(smsCodeStore.consume("13800000025", "654321")).isTrue();
    }

    @Test
    void shouldRejectConflictingBindForEstablishedSourceWithoutChangingDataOrSession() {
        AuthLoginResponse sourceLogin = wechatAuthService.login(new WechatLoginRequest("established-source"));
        UserEntity source = userMapper.selectById(sourceLogin.user().id());
        source.setPhone("13800000026");
        userMapper.updateById(source);
        WechatIdentityEntity identity = identityMapper.selectOne(null);
        AuthLoginResponse target = authService.login(new SmsLoginRequest("13800000027", "123456"));
        long cartId = System.currentTimeMillis();
        jdbcTemplate.update("insert into cart_items values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                cartId, source.getId(), 2L, 4L);
        smsCodeStore.save("13800000027", "654321");

        assertThatThrownBy(() -> wechatAuthService.bindPhone(
                sourceLogin.token(), new WechatBindPhoneRequest("13800000027", "654321")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("phone belongs to another user");

        assertThat(identityMapper.selectById(identity.getId()).getUserId()).isEqualTo(source.getId());
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from cart_items where id=?", Long.class, cartId)).isEqualTo(source.getId());
        assertThat(authService.me(sourceLogin.token()).id()).isEqualTo(source.getId());
        assertThat(userMapper.selectById(source.getId())).isNotNull();
        assertThat(userMapper.selectById(target.user().id())).isNotNull();
    }

    @Test
    void shouldAcquireIdentityThenPhoneLockBeforeVerifyingBind() {
        AuthLoginResponse login = wechatAuthService.login(new WechatLoginRequest("lock-order"));
        WechatIdentityEntity identity = identityMapper.selectOne(null);
        recordingLockService.clear();
        smsCodeStore.save("13800000028", "654321");

        wechatAuthService.bindPhone(login.token(), new WechatBindPhoneRequest(" 13800000028 ", "654321"));

        assertThat(recordingLockService.keys()).containsExactly(
                "wechat:bind:identity:" + identity.getId(),
                "wechat:bind:phone:13800000028");
    }

    @Test
    void shouldCaptureRemainingSmsAuditTtlAtVerificationTime() {
        String phone = "13800000029";
        String code = "654321";
        Instant expireAt = Instant.now().plusSeconds(30);
        SmsCodeEntity audit = smsAudit(phone, code, expireAt);
        smsCodeMapper.insert(audit);
        smsCodeStore.save(phone, code);

        AuthService.SmsVerification verification = authService.verifySmsCode(phone, code);

        assertThat(verification.remainingTtl()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(verification.remainingTtl()).isGreaterThan(Duration.ofSeconds(20));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackMergeAndRestoreCodeWhenAuditUpdateFails() {
        AuthLoginResponse source = wechatAuthService.login(new WechatLoginRequest("audit-failure"));
        long sourceUserId = source.user().id();
        WechatIdentityEntity identity = identityMapper.selectOne(null);
        String phone = "13800000031";
        String code = "654321";
        SmsCodeEntity audit = smsAudit(phone, code, Instant.now().plusSeconds(30));
        smsCodeMapper.insert(audit);
        smsCodeStore.save(phone, code);
        doThrow(new IllegalStateException("audit update failed"))
                .when(smsCodeMapper).updateById(org.mockito.ArgumentMatchers.<SmsCodeEntity>argThat(
                        value -> value != null && audit.getId().equals(value.getId())));

        try {
            assertThatThrownBy(() -> wechatAuthService.bindPhone(
                    source.token(), new WechatBindPhoneRequest(phone, code)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("audit update failed");

            assertThat(userMapper.selectById(sourceUserId).getPhone()).startsWith("wx_");
            assertThat(identityMapper.selectById(identity.getId()).getUserId()).isEqualTo(sourceUserId);
            assertThat(smsCodeStore.consume(phone, code)).isTrue();
        } finally {
            jdbcTemplate.update("delete from user_sessions where user_id=?", sourceUserId);
            jdbcTemplate.update("delete from wechat_identities where user_id=?", sourceUserId);
            jdbcTemplate.update("delete from users where id=?", sourceUserId);
            jdbcTemplate.update("delete from sms_codes where id=?", audit.getId());
        }
    }

    private SmsCodeEntity smsAudit(String phone, String code, Instant expireAt) {
        SmsCodeEntity audit = new SmsCodeEntity();
        audit.setId(System.nanoTime());
        audit.setPhone(phone);
        audit.setCode(code);
        audit.setConsumed(false);
        audit.setProvider("local");
        audit.setStatus("SUCCESS");
        audit.setCreatedAt(Instant.now());
        audit.setExpireAt(expireAt);
        return audit;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingLockConfiguration {
        @Bean
        @Primary
        RecordingLockService recordingLockService() {
            return new RecordingLockService();
        }
    }

    static class RecordingLockService implements DistributedLockService {
        private final List<String> keys = new ArrayList<>();

        @Override
        public synchronized <T> T withLock(String key, Supplier<T> action) {
            keys.add(key);
            return action.get();
        }

        synchronized List<String> keys() {
            return List.copyOf(keys);
        }

        synchronized void clear() {
            keys.clear();
        }
    }
}
