package org.example.sms;

import java.security.SecureRandom;

public final class SmsCodeGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SmsCodeGenerator() {
    }

    public static String generate(int length) {
        if (length < 4 || length > 8) {
            throw new IllegalArgumentException("sms code length must be between 4 and 8");
        }
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
