package org.example.infrastructure.mq;

import org.example.service.OrderCommandService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class OrderTimeoutMessageListener {
    private final OrderCommandService orderCommandService;

    public OrderTimeoutMessageListener(OrderCommandService orderCommandService) {
        this.orderCommandService = orderCommandService;
    }

    public void onMessage(String message) {
        try {
            orderCommandService.timeoutClose(Long.parseLong(message.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid timeout message: " + message, ex);
        }
    }
}
