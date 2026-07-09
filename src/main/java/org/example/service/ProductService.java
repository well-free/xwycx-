package org.example.service;

import org.example.infrastructure.cache.BloomFilterService;
import org.example.infrastructure.cache.CacheStore;
import org.example.config.AppProperties;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.web.BusinessException;
import org.example.web.dto.ProductResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ProductService {
    private static final String NULL_MARKER = "__NULL__";
    private final ProductMapper productMapper;
    private final CacheStore cacheService;
    private final BloomFilterService bloomFilterService;
    private final RateLimitService rateLimitService;
    private final AppProperties properties;

    public ProductService(ProductMapper productMapper,
                          CacheStore cacheService,
                          BloomFilterService bloomFilterService,
                          RateLimitService rateLimitService,
                          AppProperties properties) {
        this.productMapper = productMapper;
        this.cacheService = cacheService;
        this.bloomFilterService = bloomFilterService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    public ProductResponse getProduct(long productId) {
        rateLimitService.acquire("product:get:" + productId, 50L, 10L);
        String cacheKey = "product:" + productId;
        return cacheService.get(cacheKey)
                .map(value -> {
                    if (NULL_MARKER.equals(value)) {
                        throw new BusinessException(HttpStatus.NOT_FOUND, "product not found");
                    }
                    return deserialize(value);
                })
                .orElseGet(() -> loadProduct(productId, cacheKey));
    }

    public ProductResponse updateHotScore(long productId, int score) {
        ProductEntity entity = productMapper.selectById(productId);
        if (entity == null) {
            throw BusinessException.notFound("product not found");
        }
        entity.setHotScore(score);
        entity.setUpdatedAt(Instant.now());
        productMapper.updateById(entity);
        cacheService.evict("product:" + productId);
        bloomFilterService.put(String.valueOf(entity.getId()));
        return toResponse(entity);
    }

    public void seedDemoProducts() {
        if (productMapper.selectCount(null) > 0) {
            return;
        }
        ProductEntity a = new ProductEntity();
        a.setId(1L);
        a.setMerchantId(1L);
        a.setName("Apple Juice");
        a.setPrice(new BigDecimal("19.9"));
        a.setStock(1000L);
        a.setHotScore(87);
        a.setUpdatedAt(Instant.now());
        productMapper.insert(a);
        bloomFilterService.put(String.valueOf(a.getId()));
    }

    public List<ProductResponse> listHotProducts() {
        return productMapper.selectList(null).stream().map(this::toResponse).toList();
    }

    private ProductResponse loadProduct(long productId, String cacheKey) {
        if (!bloomFilterService.mightContain(String.valueOf(productId))) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "product not found");
        }
        ProductEntity entity = productMapper.selectById(productId);
        if (entity == null) {
            cacheService.put(cacheKey, NULL_MARKER, Duration.ofSeconds(30));
            throw new BusinessException(HttpStatus.NOT_FOUND, "product not found");
        }
        ProductResponse response = toResponse(entity);
        cacheService.put(cacheKey, serialize(response), Duration.ofSeconds(properties.getOrder().getCacheTtlSeconds()));
        return response;
    }

    private ProductResponse deserialize(String value) {
        String[] parts = value.split("\\|", -1);
        return new ProductResponse(
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1]),
                parts[2],
                new BigDecimal(parts[3]),
                Long.parseLong(parts[4]),
                Integer.parseInt(parts[5]),
                Instant.parse(parts[6]));
    }

    private String serialize(ProductResponse response) {
        return response.id() + "|" + response.merchantId() + "|" + response.name() + "|" + response.price() + "|"
                + response.stock() + "|" + response.hotScore() + "|" + response.updatedAt();
    }

    private ProductResponse toResponse(ProductEntity entity) {
        return new ProductResponse(entity.getId(), entity.getMerchantId(), entity.getName(), entity.getPrice(),
                entity.getStock(), entity.getHotScore(), entity.getUpdatedAt());
    }
}
