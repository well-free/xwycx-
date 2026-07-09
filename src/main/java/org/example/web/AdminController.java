package org.example.web;

import org.example.infrastructure.canal.CatalogChangeListener;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final CatalogChangeListener catalogChangeListener;

    public AdminController(CatalogChangeListener catalogChangeListener) {
        this.catalogChangeListener = catalogChangeListener;
    }

    @PostMapping("/catalog/change")
    public Map<String, Object> simulateCatalogChange(@RequestParam long merchantId) {
        catalogChangeListener.onMerchantChanged(merchantId);
        return Map.of("message", "catalog change accepted", "merchantId", merchantId);
    }
}
