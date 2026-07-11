package org.example.web;

import org.example.infrastructure.canal.CatalogChangeListener;
import org.example.service.AuthService;
import org.example.service.CustomerOrderService;
import org.example.service.ProductService;
import org.example.web.dto.CustomerOrderResponse;
import org.example.web.dto.ProductResponse;
import org.example.web.dto.ProductUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final CatalogChangeListener catalogChangeListener;
    private final AuthService authService;
    private final ProductService productService;
    private final CustomerOrderService customerOrderService;

    public AdminController(CatalogChangeListener catalogChangeListener,
                           AuthService authService,
                           ProductService productService,
                           CustomerOrderService customerOrderService) {
        this.catalogChangeListener = catalogChangeListener;
        this.authService = authService;
        this.productService = productService;
        this.customerOrderService = customerOrderService;
    }

    @PostMapping("/catalog/change")
    public Map<String, Object> simulateCatalogChange(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                                     @RequestParam long merchantId) {
        authService.requireAdmin(token);
        catalogChangeListener.onMerchantChanged(merchantId);
        return Map.of("message", "catalog change accepted", "merchantId", merchantId);
    }

    @PostMapping("/products")
    public ProductResponse createProduct(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                         @Valid @RequestBody ProductUpsertRequest request) {
        authService.requireAdmin(token);
        return productService.createProduct(request);
    }

    @PutMapping("/products/{id}")
    public ProductResponse updateProduct(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                         @PathVariable long id,
                                         @Valid @RequestBody ProductUpsertRequest request) {
        authService.requireAdmin(token);
        return productService.updateProduct(id, request);
    }

    @PostMapping("/products/{id}/stock")
    public ProductResponse adjustStock(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                       @PathVariable long id,
                                       @RequestParam long stock) {
        authService.requireAdmin(token);
        return productService.adjustStock(id, stock);
    }

    @PostMapping("/orders/{id}/ship")
    public CustomerOrderResponse ship(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                      @PathVariable long id) {
        return customerOrderService.ship(authService.requireAdmin(token), id);
    }

    @PostMapping("/refunds/{id}/approve")
    public Map<String, Object> approveRefund(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                             @PathVariable long id) {
        authService.requireAdmin(token);
        return Map.of("message", "refund approved", "refundId", id);
    }
}
