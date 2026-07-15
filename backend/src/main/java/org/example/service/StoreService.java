package org.example.service;

import org.example.infrastructure.mybatis.entity.StoreSettingsEntity;
import org.example.infrastructure.mybatis.mapper.StoreSettingsMapper;
import org.example.web.BusinessException;
import org.example.web.dto.StoreResponse;
import org.springframework.stereotype.Service;

@Service
public class StoreService {
    public static final long SINGLE_STORE_ID = 1L;

    private final StoreSettingsMapper storeSettingsMapper;

    public StoreService(StoreSettingsMapper storeSettingsMapper) {
        this.storeSettingsMapper = storeSettingsMapper;
    }

    public StoreResponse getStore() {
        StoreSettingsEntity store = storeSettingsMapper.selectById(SINGLE_STORE_ID);
        if (store == null) {
            throw BusinessException.serviceUnavailable("store configuration is missing");
        }
        return new StoreResponse(store.getId(), store.getStoreName(), store.getLogoUrl(),
                store.getCustomerServicePhone(), store.getShippingAddress(), store.getRefundAddress(),
                store.getBusinessStatus(), store.getUpdatedAt());
    }
}
