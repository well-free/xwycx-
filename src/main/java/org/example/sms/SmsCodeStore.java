package org.example.sms;

public interface SmsCodeStore {
    void save(String phone, String code);

    boolean consume(String phone, String code);
}
