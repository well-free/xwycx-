package org.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureCleanupTest {
    @Test
    void legacyStandaloneCompatibilityClassesShouldNotExist() {
        assertClassMissing("org.example.SmokeCheck");
        assertClassMissing("org.example.web.TradingHttpServer");
        assertClassMissing("org.example.web.JsonCodec");
        assertClassMissing("org.example.trade.Order");
        assertClassMissing("org.example.trade.Trade");
        assertClassMissing("org.example.trade.OrderBook");
        assertClassMissing("org.example.trade.OrderService");
        assertClassMissing("org.example.trade.CreateOrderRequest");
        assertClassMissing("org.example.trade.OrderPlacementResult");
    }

    private void assertClassMissing(String className) {
        assertThatThrownBy(() -> Class.forName(className))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
