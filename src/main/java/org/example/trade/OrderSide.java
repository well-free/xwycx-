package org.example.trade;

import java.util.Locale;

public enum OrderSide {
    BUY,
    SELL;

    public static OrderSide from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("side is required");
        }
        return OrderSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
