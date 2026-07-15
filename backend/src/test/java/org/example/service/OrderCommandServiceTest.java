package org.example.service;

import org.example.infrastructure.mq.OrderEventPublisher;
import org.example.infrastructure.mq.OrderTimeoutScheduler;
import org.example.infrastructure.mybatis.entity.OrderEntity;
import org.example.infrastructure.mybatis.mapper.OrderMapper;
import org.example.infrastructure.mybatis.mapper.TradeMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.trade.OrderSide;
import org.example.trade.OrderStatus;
import org.example.web.dto.OrderCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(properties = {
        "app.redis.enabled=false",
        "app.mq.enabled=false"
})
@Transactional
class OrderCommandServiceTest {
    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private TradeMapper tradeMapper;

    @MockitoBean
    private OrderEventPublisher orderEventPublisher;

    @MockitoBean
    private OrderTimeoutScheduler orderTimeoutScheduler;

    @MockitoBean
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        doNothing().when(rateLimitService).acquire(anyString(), anyLong(), anyLong());
    }

    @Test
    void buyOrderShouldEatSellOrder() {
        var sell = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.SELL, new BigDecimal("100"), 5));
        var buy = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("120"), 3));

        assertThat(buy.order().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(buy.trades()).hasSize(1);
        assertThat(buy.trades().getFirst().quantity()).isEqualTo(3);
        assertThat(buy.trades().getFirst().buyOrderId()).isEqualTo(buy.order().id());
        assertThat(buy.trades().getFirst().sellOrderId()).isEqualTo(sell.order().id());

        OrderEntity sellOrder = orderMapper.selectById(sell.order().id());
        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED.name());
        assertThat(sellOrder.getRemainingQuantity()).isEqualTo(2);
        assertThat(tradeMapper.selectList(null)).hasSize(1);
    }

    @Test
    void shouldSupportPartialFill() {
        var sell = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.SELL, new BigDecimal("100"), 2));
        var buy = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("110"), 5));

        assertThat(buy.order().status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(buy.order().remainingQuantity()).isEqualTo(3);
        assertThat(orderMapper.selectById(sell.order().id()).getStatus()).isEqualTo(OrderStatus.FILLED.name());
        assertThat(buy.trades()).hasSize(1);
        assertThat(buy.trades().getFirst().quantity()).isEqualTo(2);
    }

    @Test
    void shouldMatchEarlierOrderFirstAtSamePrice() throws Exception {
        var first = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.SELL, new BigDecimal("100"), 1));
        Thread.sleep(10L);
        var second = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.SELL, new BigDecimal("100"), 1));

        var buy = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("100"), 1));

        assertThat(buy.trades()).hasSize(1);
        assertThat(buy.trades().getFirst().sellOrderId()).isEqualTo(first.order().id());

        OrderEntity firstOrder = orderMapper.selectById(first.order().id());
        OrderEntity secondOrder = orderMapper.selectById(second.order().id());
        assertThat(firstOrder.getStatus()).isEqualTo(OrderStatus.FILLED.name());
        assertThat(secondOrder.getStatus()).isEqualTo(OrderStatus.NEW.name());
    }

    @Test
    void canceledOrderShouldNotMatchAgain() {
        var sell = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.SELL, new BigDecimal("100"), 1));

        orderCommandService.cancel(sell.order().id());

        var buy = orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("120"), 1));

        assertThat(buy.trades()).isEmpty();
        assertThat(buy.order().status()).isEqualTo(OrderStatus.NEW);
        assertThat(orderMapper.selectById(sell.order().id()).getStatus()).isEqualTo(OrderStatus.CANCELED.name());
    }

    @Test
    void shouldRejectInvalidPriceOrQuantity() {
        assertThatThrownBy(() -> orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, BigDecimal.ZERO, 1)))
                .hasMessageContaining("price must be positive");

        assertThatThrownBy(() -> orderCommandService.place(new OrderCreateRequest("MASK-50", OrderSide.BUY, new BigDecimal("1"), 0)))
                .hasMessageContaining("quantity must be positive");
    }
}
