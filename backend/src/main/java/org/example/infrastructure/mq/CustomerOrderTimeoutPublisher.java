package org.example.infrastructure.mq;

import org.example.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class CustomerOrderTimeoutPublisher implements CustomerOrderTimeoutDispatcher {
    private final ApplicationContext applicationContext;
    private final AppProperties properties;

    public CustomerOrderTimeoutPublisher(ApplicationContext applicationContext, AppProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public void dispatch(long orderId, Duration delay) {
        Object template = applicationContext.getBean("rocketMQTemplate");
        try {
            Method method = template.getClass().getMethod(
                    "syncSendDelayTimeMills", String.class, Object.class, long.class);
            method.invoke(template, properties.getMq().getCustomerOrderTimeoutTopic(),
                    new CustomerOrderTimeoutMessage(orderId).serialize(), Math.max(1L, delay.toMillis()));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to publish customer order timeout", exception);
        }
    }
}
