package org.example.web;

import jakarta.validation.Valid;
import org.example.payment.PaymentChannel;
import org.example.service.AuthService;
import org.example.service.CustomerOrderService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.CustomerOrderCreateRequest;
import org.example.web.dto.CustomerOrderResponse;
import org.example.web.dto.PaymentResponse;
import org.example.web.dto.RefundCreateRequest;
import org.example.web.dto.RefundResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/customer-orders")
public class CustomerOrderController {
    private final AuthService authService;
    private final CustomerOrderService customerOrderService;

    public CustomerOrderController(AuthService authService, CustomerOrderService customerOrderService) {
        this.authService = authService;
        this.customerOrderService = customerOrderService;
    }

    @PostMapping
    public CustomerOrderResponse create(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                        @Valid @RequestBody CustomerOrderCreateRequest request) {
        return customerOrderService.create(authService.requireUser(token), request);
    }

    @GetMapping
    public ApiPageResponse<CustomerOrderResponse> list(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        var items = customerOrderService.list(authService.requireUser(token));
        return new ApiPageResponse<>(items.size(), items);
    }

    @GetMapping("/{id}")
    public CustomerOrderResponse get(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                     @PathVariable long id) {
        return customerOrderService.get(authService.requireUser(token), id);
    }

    @PostMapping("/{id}/cancel")
    public CustomerOrderResponse cancel(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                        @PathVariable long id) {
        return customerOrderService.cancel(authService.requireUser(token), id);
    }

    @PostMapping("/{id}/payments")
    public PaymentResponse createPayment(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                         @PathVariable long id,
                                         @Valid @RequestBody Map<String, PaymentChannel> request) {
        return customerOrderService.createPayment(authService.requireUser(token), id, request.get("channel"));
    }

    @PostMapping("/{id}/refunds")
    public RefundResponse refund(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                 @PathVariable long id,
                                 @Valid @RequestBody RefundCreateRequest request) {
        return customerOrderService.refund(authService.requireUser(token), id, request);
    }
}
