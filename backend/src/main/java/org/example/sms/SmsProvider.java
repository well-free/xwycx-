package org.example.sms;

public interface SmsProvider {
    SmsSendResult sendVerificationCode(String phone, String code);
}
