package org.example.infrastructure.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("payment_orders")
public class PaymentOrderEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long orderId;
    private String channel;
    private BigDecimal amount;
    private String status;
    private String channelTradeNo;
    private String payUrl;
    private String qrCode;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expireAt;
    private Instant paidAt;
    private Instant closedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }

    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getChannel() { return channel; }

    public void setChannel(String channel) { this.channel = channel; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public String getChannelTradeNo() { return channelTradeNo; }

    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }

    public String getPayUrl() { return payUrl; }

    public void setPayUrl(String payUrl) { this.payUrl = payUrl; }

    public String getQrCode() { return qrCode; }

    public void setQrCode(String qrCode) { this.qrCode = qrCode; }

    public Long getVersion() { return version; }

    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getExpireAt() { return expireAt; }

    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }

    public Instant getPaidAt() { return paidAt; }

    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public Instant getClosedAt() { return closedAt; }

    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
