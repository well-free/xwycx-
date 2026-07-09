package org.example.infrastructure.mq;

import org.example.service.OrderCommandService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class OrderTimeoutScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "order-timeout-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectProvider<OrderCommandService> orderCommandServiceProvider;

    public OrderTimeoutScheduler(ObjectProvider<OrderCommandService> orderCommandServiceProvider) {
        this.orderCommandServiceProvider = orderCommandServiceProvider;
    }

    public void schedule(long orderId, Duration delay) {
        executor.schedule(() -> {
            OrderCommandService service = orderCommandServiceProvider.getIfAvailable();
            if (service != null) {
                service.timeoutClose(orderId);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
