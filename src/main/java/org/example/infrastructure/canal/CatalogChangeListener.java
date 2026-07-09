package org.example.infrastructure.canal;

import org.example.infrastructure.cache.CacheStore;
import org.example.infrastructure.mq.OrderEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class CatalogChangeListener {
    private static final Logger log = LoggerFactory.getLogger(CatalogChangeListener.class);
    private final CacheStore cacheService;
    private final OrderEventPublisher publisher;

    public CatalogChangeListener(CacheStore cacheService, OrderEventPublisher publisher) {
        this.cacheService = cacheService;
        this.publisher = publisher;
    }

    @Async
    public void onMerchantChanged(long merchantId) {
        cacheService.evict("merchant:" + merchantId);
        cacheService.evict("product:1");
        publisher.sendCatalogChange(merchantId);
        log.info("catalog invalidated for merchant={}", merchantId);
    }
}
