package org.example.infrastructure.mq;

import org.example.service.CustomerOrderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class CustomerOrderTimeoutScheduler implements CustomerOrderTimeoutDispatcher {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "customer-order-timeout-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectProvider<CustomerOrderService> customerOrderServiceProvider;

    public CustomerOrderTimeoutScheduler(ObjectProvider<CustomerOrderService> customerOrderServiceProvider) {
        this.customerOrderServiceProvider = customerOrderServiceProvider;
    }

    @Override
    public void dispatch(long orderId, Duration delay) {
        executor.schedule(() -> {
            CustomerOrderService service = customerOrderServiceProvider.getIfAvailable();
            if (service != null) {
                service.timeoutCloseCustomerOrder(orderId);
            }
        }, Math.max(1L, delay.toMillis()), TimeUnit.MILLISECONDS);
    }
}
