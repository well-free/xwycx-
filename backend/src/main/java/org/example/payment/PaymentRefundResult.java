package org.example.payment;

import org.example.refund.RefundStatus;

public record PaymentRefundResult(String channelRefundNo, RefundStatus status) {
}
