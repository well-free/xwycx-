package org.example.sms;

public record SmsSendResult(String requestId, String status, String message) {
}
