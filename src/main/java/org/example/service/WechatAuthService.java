package org.example.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.example.auth.UserRole;
import org.example.infrastructure.lock.DistributedLockService;
import org.example.infrastructure.mybatis.entity.CustomerOrderEntity;
import org.example.infrastructure.mybatis.entity.ShippingAddressEntity;
import org.example.infrastructure.mybatis.entity.UserEntity;
import org.example.infrastructure.mybatis.entity.UserSessionEntity;
import org.example.infrastructure.mybatis.entity.WechatIdentityEntity;
import org.example.infrastructure.mybatis.mapper.CustomerOrderMapper;
import org.example.infrastructure.mybatis.mapper.ShippingAddressMapper;
import org.example.infrastructure.mybatis.mapper.UserMapper;
import org.example.infrastructure.mybatis.mapper.WechatIdentityMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.web.BusinessException;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.WechatBindPhoneRequest;
import org.example.web.dto.WechatLoginRequest;
import org.example.wechat.WechatSession;
import org.example.wechat.WechatSessionGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class WechatAuthService {
    private static final String RESERVED_PHONE_PREFIX = "wx_";

    private final WechatSessionGateway sessionGateway;
    private final WechatIdentityMapper identityMapper;
    private final UserMapper userMapper;
    private final AuthService authService;
    private final RateLimitService rateLimitService;
    private final CartService cartService;
    private final ShippingAddressMapper shippingAddressMapper;
    private final CustomerOrderMapper customerOrderMapper;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;

    public WechatAuthService(WechatSessionGateway sessionGateway,
                             WechatIdentityMapper identityMapper,
                             UserMapper userMapper,
                             AuthService authService,
                             RateLimitService rateLimitService,
                             CartService cartService,
                             ShippingAddressMapper shippingAddressMapper,
                             CustomerOrderMapper customerOrderMapper,
                             DistributedLockService distributedLockService,
                             TransactionTemplate transactionTemplate) {
        this.sessionGateway = sessionGateway;
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.authService = authService;
        this.rateLimitService = rateLimitService;
        this.cartService = cartService;
        this.shippingAddressMapper = shippingAddressMapper;
        this.customerOrderMapper = customerOrderMapper;
        this.distributedLockService = distributedLockService;
        this.transactionTemplate = transactionTemplate;
    }

    public AuthLoginResponse login(WechatLoginRequest request) {
        return login(request, "unknown");
    }

    public AuthLoginResponse login(WechatLoginRequest request, String clientIp) {
        String rateLimitIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
        rateLimitService.acquire("auth:wechat:login:ip:" + rateLimitIp, 10L, 1L);
        WechatSession wechatSession = sessionGateway.exchange(request.code());
        String lockKey = "wechat:identity:" + wechatSession.appId() + ":" + wechatSession.openId();
        return distributedLockService.withLock(lockKey,
                () -> transactionTemplate.execute(status -> loginTransaction(wechatSession)));
    }

    private AuthLoginResponse loginTransaction(WechatSession wechatSession) {
        WechatIdentityEntity identity = findIdentity(wechatSession.appId(), wechatSession.openId());
        UserEntity user;
        if (identity == null) {
            user = createTemporaryUser(wechatSession);
            identity = createIdentity(user, wechatSession);
        } else {
            user = userMapper.selectById(identity.getUserId());
            if (user == null) {
                throw BusinessException.unauthorized("login required");
            }
        }
        return authService.createSession(user, identity.getId());
    }

    public AuthLoginResponse bindPhone(String token, WechatBindPhoneRequest request) {
        UserSessionEntity session = authService.loadValidSession(token);
        if (session.getWechatIdentityId() == null) {
            throw BusinessException.unauthorized("wechat login required");
        }
        UserEntity currentUser = userMapper.selectById(session.getUserId());
        if (currentUser == null) {
            throw BusinessException.unauthorized("login required");
        }
        rateLimitService.acquire("wechat:bind-phone:" + currentUser.getId(), 5L, 1L);
        String phone = authService.normalizePhone(request.phone());
        String identityLock = "wechat:bind:identity:" + session.getWechatIdentityId();
        String phoneLock = "wechat:bind:phone:" + phone;
        return distributedLockService.withLock(identityLock,
                () -> distributedLockService.withLock(phoneLock,
                        () -> bindPhoneWithinLocks(token, session.getWechatIdentityId(), phone, request.code())));
    }

    private AuthLoginResponse bindPhoneWithinLocks(String token, long identityId, String phone, String code) {
        AuthService.SmsVerification verification = authService.verifySmsCode(phone, code);

        try {
            BindParticipants participants = resolveBindParticipants(token, identityId, verification.phone());
            Supplier<AuthLoginResponse> transaction = () -> transactionTemplate.execute(status -> {
                AuthLoginResponse response = bindPhoneTransaction(
                        token, identityId, verification.phone(), participants);
                authService.completeSmsVerification(verification);
                return response;
            });
            return participants.requiresMerge()
                    ? cartService.withUserLocks(participants.userIds(), transaction)
                    : transaction.get();
        } catch (RuntimeException exception) {
            authService.restoreSmsVerification(verification);
            throw exception;
        }
    }

    private BindParticipants resolveBindParticipants(String token, long identityId, String phone) {
        UserSessionEntity session = authService.loadValidSession(token);
        if (!Objects.equals(session.getWechatIdentityId(), identityId)) {
            throw BusinessException.unauthorized("wechat login required");
        }
        UserEntity source = userMapper.selectById(session.getUserId());
        WechatIdentityEntity identity = identityMapper.selectOne(new LambdaQueryWrapper<WechatIdentityEntity>()
                .eq(WechatIdentityEntity::getId, identityId)
                .eq(WechatIdentityEntity::getUserId, session.getUserId())
                .last("limit 1"));
        if (source == null || identity == null) {
            throw BusinessException.unauthorized("wechat login required");
        }
        UserEntity phoneOwner = findPhoneOwner(phone);
        long targetUserId = phoneOwner == null ? source.getId() : phoneOwner.getId();
        return new BindParticipants(source.getId(), targetUserId);
    }

    private AuthLoginResponse bindPhoneTransaction(String token,
                                                   long identityId,
                                                   String phone,
                                                   BindParticipants participants) {
        UserSessionEntity session = authService.loadValidSession(token);
        if (!Objects.equals(session.getWechatIdentityId(), identityId)
                || !Objects.equals(session.getUserId(), participants.sourceUserId())) {
            throw BusinessException.unauthorized("wechat login required");
        }
        UserEntity currentUser = userMapper.selectById(session.getUserId());
        WechatIdentityEntity identity = identityMapper.selectOne(new LambdaQueryWrapper<WechatIdentityEntity>()
                .eq(WechatIdentityEntity::getId, identityId)
                .eq(WechatIdentityEntity::getUserId, session.getUserId())
                .last("limit 1"));
        if (currentUser == null || identity == null) {
            throw BusinessException.unauthorized("wechat login required");
        }
        UserEntity phoneOwner = findPhoneOwner(phone);
        long actualTargetUserId = phoneOwner == null ? currentUser.getId() : phoneOwner.getId();
        if (actualTargetUserId != participants.targetUserId()) {
            throw BusinessException.conflict("phone ownership changed");
        }
        if (phoneOwner == null || phoneOwner.getId().equals(currentUser.getId())) {
            currentUser.setPhone(phone);
            currentUser.setUpdatedAt(Instant.now());
            userMapper.updateById(currentUser);
            return authService.createSession(currentUser, identity.getId());
        }

        if (!isReservedPhone(currentUser.getPhone())) {
            throw BusinessException.conflict("phone belongs to another user");
        }

        transferOwnedData(currentUser.getId(), phoneOwner.getId());
        identity.setUserId(phoneOwner.getId());
        identity.setUpdatedAt(Instant.now());
        identityMapper.updateById(identity);
        authService.invalidateSessions(currentUser.getId());
        Long remainingIdentities = identityMapper.selectCount(new LambdaQueryWrapper<WechatIdentityEntity>()
                .eq(WechatIdentityEntity::getUserId, currentUser.getId()));
        if (isReservedPhone(currentUser.getPhone()) && remainingIdentities == 0) {
            userMapper.deleteById(currentUser.getId());
        }
        return authService.createSession(phoneOwner, identity.getId());
    }

    private void transferOwnedData(long sourceUserId, long targetUserId) {
        cartService.mergeCartOwnershipLocked(sourceUserId, targetUserId);
        shippingAddressMapper.update(null, new LambdaUpdateWrapper<ShippingAddressEntity>()
                .eq(ShippingAddressEntity::getUserId, sourceUserId)
                .set(ShippingAddressEntity::getUserId, targetUserId)
                .set(ShippingAddressEntity::getUpdatedAt, Instant.now()));
        customerOrderMapper.update(null, new LambdaUpdateWrapper<CustomerOrderEntity>()
                .eq(CustomerOrderEntity::getUserId, sourceUserId)
                .set(CustomerOrderEntity::getUserId, targetUserId)
                .set(CustomerOrderEntity::getUpdatedAt, Instant.now()));
    }

    private UserEntity findPhoneOwner(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getPhone, phone)
                .last("limit 1"));
    }

    private WechatIdentityEntity findIdentity(String appId, String openId) {
        return identityMapper.selectOne(new LambdaQueryWrapper<WechatIdentityEntity>()
                .eq(WechatIdentityEntity::getAppid, appId)
                .eq(WechatIdentityEntity::getOpenid, openId)
                .last("limit 1"));
    }

    private UserEntity createTemporaryUser(WechatSession session) {
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(IdWorker.getId());
        user.setPhone(reservedPhone(session.appId(), session.openId()));
        user.setRole(UserRole.CUSTOMER.name());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    private WechatIdentityEntity createIdentity(UserEntity user, WechatSession session) {
        Instant now = Instant.now();
        WechatIdentityEntity identity = new WechatIdentityEntity();
        identity.setId(IdWorker.getId());
        identity.setUserId(user.getId());
        identity.setAppid(session.appId());
        identity.setOpenid(session.openId());
        identity.setUnionid(session.unionId());
        identity.setCreatedAt(now);
        identity.setUpdatedAt(now);
        identityMapper.insert(identity);
        return identity;
    }

    static boolean isReservedPhone(String phone) {
        return phone != null && phone.startsWith(RESERVED_PHONE_PREFIX);
    }

    private record BindParticipants(long sourceUserId, long targetUserId) {
        boolean requiresMerge() {
            return sourceUserId != targetUserId;
        }

        List<Long> userIds() {
            return requiresMerge() ? List.of(sourceUserId, targetUserId) : List.of(sourceUserId);
        }
    }

    private static String reservedPhone(String appId, String openId) {
        return RESERVED_PHONE_PREFIX + sha256(appId + ":" + openId).substring(0, 29);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
