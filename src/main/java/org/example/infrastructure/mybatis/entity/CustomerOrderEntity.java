package org.example.infrastructure.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("customer_orders")
public class CustomerOrderEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long addressId;
    private String shippingSnapshot;
    private BigDecimal totalAmount;
    private String status;
    private String remark;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;
    private Instant shippedAt;
    private Instant canceledAt;
    private String shippingCarrier;
    private String trackingNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }
    public String getShippingSnapshot() { return shippingSnapshot; }
    public void setShippingSnapshot(String shippingSnapshot) { this.shippingSnapshot = shippingSnapshot; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getShippedAt() { return shippedAt; }
    public void setShippedAt(Instant shippedAt) { this.shippedAt = shippedAt; }
    public Instant getCanceledAt() { return canceledAt; }
    public void setCanceledAt(Instant canceledAt) { this.canceledAt = canceledAt; }
    public String getShippingCarrier() { return shippingCarrier; }
    public void setShippingCarrier(String shippingCarrier) { this.shippingCarrier = shippingCarrier; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
}
