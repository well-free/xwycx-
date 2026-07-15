package org.example.infrastructure.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.example.service.CustomerOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerOrderTimeoutInfrastructureTest {
    @Test
    void listenerUsesDedicatedRocketMqConsumerContract() {
        assertThat(RocketMQListener.class).isAssignableFrom(CustomerOrderTimeoutMessageListener.class);
        RocketMQMessageListener annotation = CustomerOrderTimeoutMessageListener.class
                .getAnnotation(RocketMQMessageListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.topic()).isEqualTo("${app.mq.customer-order-timeout-topic}");
        assertThat(annotation.consumerGroup()).isEqualTo("xwycx-customer-order-timeout");
    }

    @Test
    void typedMessageRejectsTradeOrderPayloadAndListenerRoutesCustomerOrder() {
        assertThatThrownBy(() -> CustomerOrderTimeoutMessage.parse("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid customer order timeout message");

        CustomerOrderService service = mock(CustomerOrderService.class);
        CustomerOrderTimeoutMessageListener listener = new CustomerOrderTimeoutMessageListener(service);

        listener.onMessage(new CustomerOrderTimeoutMessage(456L).serialize());

        verify(service).timeoutCloseCustomerOrder(456L);
    }

    @Test
    void localSchedulerUsesProviderToCloseCustomerOrder() {
        CustomerOrderService service = mock(CustomerOrderService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CustomerOrderService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        CustomerOrderTimeoutScheduler scheduler = new CustomerOrderTimeoutScheduler(provider);

        scheduler.dispatch(789L, Duration.ofMillis(10));

        verify(service, timeout(1000)).timeoutCloseCustomerOrder(789L);
    }
}
