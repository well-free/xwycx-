package org.example.infrastructure.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("payment_callbacks")
public class PaymentCallbackEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long paymentId;
    private String channel;
    private String notifyId;
    private String channelTradeNo;
    private String eventType;
    private BigDecimal amount;
    private String payload;
    private boolean processed;
    private Instant createdAt;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getPaymentId() { return paymentId; }

    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public String getChannel() { return channel; }

    public void setChannel(String channel) { this.channel = channel; }

    public String getNotifyId() { return notifyId; }

    public void setNotifyId(String notifyId) { this.notifyId = notifyId; }

    public String getChannelTradeNo() { return channelTradeNo; }

    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }

    public String getEventType() { return eventType; }

    public void setEventType(String eventType) { this.eventType = eventType; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPayload() { return payload; }

    public void setPayload(String payload) { this.payload = payload; }

    public boolean isProcessed() { return processed; }

    public void setProcessed(boolean processed) { this.processed = processed; }

    public Instant getCreatedAt() { return createdAt; }

    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
