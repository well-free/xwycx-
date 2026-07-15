package org.example.payment;

public record PaymentGatewayResult(
        String payUrl,
        String qrCode,
        String prepayId,
        MiniProgramPaymentParameters miniProgram) {
    public PaymentGatewayResult(String payUrl, String qrCode) {
        this(payUrl, qrCode, null, null);
    }
}
