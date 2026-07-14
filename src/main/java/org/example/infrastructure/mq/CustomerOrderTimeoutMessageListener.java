package org.example.infrastructure.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.example.service.CustomerOrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.mq", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${app.mq.customer-order-timeout-topic}",
        consumerGroup = "xwycx-customer-order-timeout")
public class CustomerOrderTimeoutMessageListener implements RocketMQListener<String> {
    private final CustomerOrderService customerOrderService;

    public CustomerOrderTimeoutMessageListener(CustomerOrderService customerOrderService) {
        this.customerOrderService = customerOrderService;
    }

    @Override
    public void onMessage(String payload) {
        CustomerOrderTimeoutMessage message = CustomerOrderTimeoutMessage.parse(payload);
        customerOrderService.timeoutCloseCustomerOrder(message.orderId());
    }
}
