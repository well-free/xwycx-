package org.example.web;

import org.example.infrastructure.canal.CatalogChangeListener;
import org.example.service.AuthService;
import org.example.service.CustomerOrderService;
import org.example.service.ProductService;
import org.example.service.PaymentService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.CustomerOrderResponse;
import org.example.web.dto.ProductResponse;
import org.example.web.dto.ProductUpsertRequest;
import org.example.web.dto.ShipmentRequest;
import org.example.web.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final PaymentService paymentService;

    public AdminController(CatalogChangeListener catalogChangeListener,
                           AuthService authService,
                           ProductService productService,
                           CustomerOrderService customerOrderService,
                           PaymentService paymentService) {
        this.catalogChangeListener = catalogChangeListener;
        this.authService = authService;
        this.productService = productService;
        this.customerOrderService = customerOrderService;
        this.paymentService = paymentService;
    }

    @GetMapping("/orders")
    public ApiPageResponse<?> listOrders(
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        var items = customerOrderService.list(authService.requireAdmin(token));
        return new ApiPageResponse<>(items.size(), items);
    }

    @GetMapping("/refunds")
    public ApiPageResponse<?> listRefunds(
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        authService.requireAdmin(token);
        var items = paymentService.listRefunds();
        return new ApiPageResponse<>(items.size(), items);
    }

    @GetMapping("/payments")
    public ApiPageResponse<?> listPayments(
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        authService.requireAdmin(token);
        var items = paymentService.list();
        return new ApiPageResponse<>(items.size(), items);
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
                                      @PathVariable long id,
                                      @Valid @RequestBody ShipmentRequest request) {
        return customerOrderService.ship(authService.requireAdmin(token), id, request);
    }

    @PostMapping("/refunds/{id}/approve")
    public RefundResponse approveRefund(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                        @PathVariable long id) {
        authService.requireAdmin(token);
        return paymentService.approveRefund(id);
    }
}
