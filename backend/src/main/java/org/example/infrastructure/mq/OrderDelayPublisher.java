package org.example.infrastructure.mq;

import org.example.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
public class OrderDelayPublisher implements OrderEventPublisher {
    private final ApplicationContext applicationContext;
    private final AppProperties properties;

    public OrderDelayPublisher(ApplicationContext applicationContext, AppProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public void sendTimeoutClose(long orderId, Duration delay) {
        Object template = rocketMQTemplate();
        invoke(template, "syncSendDelayTimeMills",
                new Class<?>[]{String.class, Object.class, long.class},
                properties.getMq().getTopic(), String.valueOf(orderId), Math.max(1L, delay.toMillis()));
    }

    @Override
    public void sendCatalogChange(long catalogId) {
        Object template = rocketMQTemplate();
        invoke(template, "syncSend",
                new Class<?>[]{String.class, Object.class},
                properties.getMq().getCatalogTopic(), String.valueOf(catalogId));
    }

    private Object rocketMQTemplate() {
        return applicationContext.getBean("rocketMQTemplate");
    }

    private void invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to publish RocketMQ message", ex);
        }
    }
}
