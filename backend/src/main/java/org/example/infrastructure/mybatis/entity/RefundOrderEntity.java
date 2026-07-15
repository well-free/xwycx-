package org.example.infrastructure.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("refund_orders")
public class RefundOrderEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private String reason;
    private String status;
    private String channelRefundNo;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getPaymentId() { return paymentId; }

    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }

    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public String getChannelRefundNo() { return channelRefundNo; }

    public void setChannelRefundNo(String channelRefundNo) { this.channelRefundNo = channelRefundNo; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }

    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
