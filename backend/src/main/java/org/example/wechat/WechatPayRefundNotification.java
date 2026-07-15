package org.example.wechat;

import org.example.refund.RefundStatus;

public record WechatPayRefundNotification(
        long refundId,
        String channelRefundNo,
        RefundStatus status) {
}
