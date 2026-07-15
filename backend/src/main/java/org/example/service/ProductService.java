package org.example.service;

import org.example.infrastructure.cache.BloomFilterService;
import org.example.infrastructure.cache.CacheStore;
import org.example.config.AppProperties;
import org.example.infrastructure.mybatis.entity.ProductEntity;
import org.example.infrastructure.mybatis.mapper.ProductMapper;
import org.example.infrastructure.rate.RateLimitService;
import org.example.web.BusinessException;
import org.example.web.dto.ProductResponse;
import org.example.web.dto.ProductUpsertRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

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

    public List<ProductResponse> listProducts() {
        return productMapper.selectList(null).stream()
                .filter(product -> "ON_SHELF".equals(product.getStatus()))
                .sorted((a, b) -> Integer.compare(b.getSortOrder(), a.getSortOrder()))
                .map(this::toResponse)
                .toList();
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
        a.setSku("MASK-50");
        a.setName("一次性医用口罩 50只装");
        a.setPrice(new BigDecimal("12.80"));
        a.setStock(5000L);
        a.setReservedStock(0L);
        a.setSoldStock(0L);
        a.setHotScore(96);
        a.setMainImage("/assets/mask-50.png");
        a.setDetailImages("/assets/mask-50-detail.png");
        a.setSpec("50只/盒");
        a.setUnit("盒");
        a.setStatus("ON_SHELF");
        a.setSortOrder(100);
        a.setUpdatedAt(Instant.now());
        productMapper.insert(a);
        bloomFilterService.put(String.valueOf(a.getId()));
    }

    public List<ProductResponse> listHotProducts() {
        return productMapper.selectList(null).stream().map(this::toResponse).toList();
    }

    public ProductResponse createProduct(ProductUpsertRequest request) {
        ProductEntity entity = new ProductEntity();
        entity.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        entity.setMerchantId(1L);
        applyProductRequest(entity, request);
        entity.setReservedStock(0L);
        entity.setSoldStock(0L);
        entity.setHotScore(0);
        entity.setUpdatedAt(Instant.now());
        productMapper.insert(entity);
        bloomFilterService.put(String.valueOf(entity.getId()));
        cacheService.evict("product:" + entity.getId());
        return toResponse(entity);
    }

    public ProductResponse updateProduct(long productId, ProductUpsertRequest request) {
        ProductEntity entity = productMapper.selectById(productId);
        if (entity == null) {
            throw BusinessException.notFound("product not found");
        }
        applyProductRequest(entity, request);
        entity.setUpdatedAt(Instant.now());
        productMapper.updateById(entity);
        cacheService.evict("product:" + productId);
        bloomFilterService.put(String.valueOf(entity.getId()));
        return toResponse(entity);
    }

    public ProductResponse adjustStock(long productId, long stock) {
        if (stock < 0) {
            throw BusinessException.badRequest("stock must not be negative");
        }
        ProductEntity entity = productMapper.selectById(productId);
        if (entity == null) {
            throw BusinessException.notFound("product not found");
        }
        entity.setStock(stock);
        entity.setUpdatedAt(Instant.now());
        productMapper.updateById(entity);
        cacheService.evict("product:" + productId);
        return toResponse(entity);
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
                parts[3],
                new BigDecimal(parts[4]),
                Long.parseLong(parts[5]),
                Integer.parseInt(parts[6]),
                parts[7],
                parts[8],
                parts[9],
                parts[10],
                parts[11],
                Integer.parseInt(parts[12]),
                Instant.parse(parts[13]),
                parts.length > 14 ? Long.parseLong(parts[14]) : 0L,
                parts.length > 15 ? Long.parseLong(parts[15]) : 0L);
    }

    private String serialize(ProductResponse response) {
        return response.id() + "|" + response.merchantId() + "|" + response.sku() + "|" + response.name() + "|" + response.price() + "|"
                + response.stock() + "|" + response.hotScore() + "|" + response.mainImage() + "|" + response.detailImages() + "|"
                + response.spec() + "|" + response.unit() + "|" + response.status() + "|" + response.sortOrder() + "|"
                + response.updatedAt() + "|" + response.reservedStock() + "|" + response.soldStock();
    }

    private ProductResponse toResponse(ProductEntity entity) {
        return new ProductResponse(entity.getId(), entity.getMerchantId(), entity.getSku(), entity.getName(), entity.getPrice(),
                entity.getStock(), entity.getHotScore(), entity.getMainImage(), entity.getDetailImages(), entity.getSpec(),
                entity.getUnit(), entity.getStatus(), entity.getSortOrder(), entity.getUpdatedAt(),
                entity.getReservedStock(), entity.getSoldStock());
    }

    private void applyProductRequest(ProductEntity entity, ProductUpsertRequest request) {
        entity.setSku(request.sku().trim().toUpperCase(Locale.ROOT));
        entity.setName(request.name().trim());
        entity.setPrice(request.price());
        entity.setStock(request.stock());
        entity.setMainImage(blankToDefault(request.mainImage(), "/assets/product.png"));
        entity.setDetailImages(blankToDefault(request.detailImages(), ""));
        entity.setSpec(blankToDefault(request.spec(), "常规规格"));
        entity.setUnit(blankToDefault(request.unit(), "件"));
        entity.setStatus(blankToDefault(request.status(), "ON_SHELF").trim().toUpperCase(Locale.ROOT));
        entity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
