package org.example.payment;

public record PaymentGatewayResult(String payUrl, String qrCode) {
}
