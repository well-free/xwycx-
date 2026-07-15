package org.example.infrastructure.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("user_sessions")
public class UserSessionEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long wechatIdentityId;
    private String token;
    private Instant expireAt;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getWechatIdentityId() { return wechatIdentityId; }
    public void setWechatIdentityId(Long wechatIdentityId) { this.wechatIdentityId = wechatIdentityId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
