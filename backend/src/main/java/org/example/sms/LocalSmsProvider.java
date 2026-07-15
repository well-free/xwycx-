package org.example.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.sms", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalSmsProvider implements SmsProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalSmsProvider.class);

    @Override
    public SmsSendResult sendVerificationCode(String phone, String code) {
        log.info("local sms code generated phone={} code={}", phone, code);
        return new SmsSendResult("local", "SUCCESS", "local sms sent");
    }
}
