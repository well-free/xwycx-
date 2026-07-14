package org.example.infrastructure.mq;

public record CustomerOrderTimeoutMessage(long orderId) {
    private static final String PREFIX = "customer-order-timeout:";

    public CustomerOrderTimeoutMessage {
        if (orderId <= 0) {
            throw new IllegalArgumentException("customer order id must be positive");
        }
    }

    public String serialize() {
        return PREFIX + orderId;
    }

    public static CustomerOrderTimeoutMessage parse(String payload) {
        if (payload == null || !payload.startsWith(PREFIX)) {
            throw new IllegalArgumentException("invalid customer order timeout message: " + payload);
        }
        try {
            return new CustomerOrderTimeoutMessage(Long.parseLong(payload.substring(PREFIX.length()).trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid customer order timeout message: " + payload, exception);
        }
    }
}
