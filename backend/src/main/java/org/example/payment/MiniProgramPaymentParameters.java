package org.example.payment;

public record MiniProgramPaymentParameters(
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign) {
}
