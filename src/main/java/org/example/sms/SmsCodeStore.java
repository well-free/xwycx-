package org.example.sms;

import java.time.Duration;

public interface SmsCodeStore {
    void save(String phone, String code);

    boolean consume(String phone, String code);

    boolean restoreIfAbsent(String phone, String code, Duration ttl);
}
