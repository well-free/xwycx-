package org.example.web;

import jakarta.validation.Valid;
import org.example.payment.PaymentChannel;
import org.example.service.AuthService;
import org.example.service.PaymentService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.PaymentCallbackRequest;
import org.example.web.dto.PaymentCreateRequest;
import org.example.web.dto.PaymentResponse;
import org.example.web.dto.RefundCreateRequest;
import org.example.web.dto.RefundResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final AuthService authService;

    public PaymentController(PaymentService paymentService, AuthService authService) {
        this.paymentService = paymentService;
        this.authService = authService;
    }

    @PostMapping
    public PaymentResponse create(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token,
                                  @Valid @RequestBody PaymentCreateRequest request) {
        authService.requireAdmin(token);
        return paymentService.create(request);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token,
                               @PathVariable long id) {
        return paymentService.get(authService.requireUser(token), id);
    }

    @GetMapping
    public ApiPageResponse<PaymentResponse> list(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token) {
        var items = paymentService.list(authService.requireUser(token));
        return new ApiPageResponse<>(items.size(), items);
    }

    @PostMapping("/{id}/close")
    public PaymentResponse close(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token,
                                 @PathVariable long id) {
        return paymentService.close(authService.requireUser(token), id);
    }

    @PostMapping("/callbacks/{channel}")
    public PaymentResponse callback(@PathVariable String channel, @Valid @RequestBody PaymentCallbackRequest request) {
        return paymentService.handleCallback(PaymentChannel.valueOf(channel.trim().toUpperCase()), request);
    }

    @PostMapping("/{id}/refunds")
    public RefundResponse refund(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token,
                                 @PathVariable long id,
                                 @Valid @RequestBody RefundCreateRequest request) {
        return paymentService.refund(authService.requireUser(token), id, request);
    }

    @GetMapping("/{id}/refunds")
    public ApiPageResponse<RefundResponse> listRefunds(@org.springframework.web.bind.annotation.RequestHeader(value = "X-Session-Token", required = false) String token,
                                                       @PathVariable long id) {
        paymentService.get(authService.requireUser(token), id);
        var items = paymentService.listRefunds(id);
        return new ApiPageResponse<>(items.size(), items);
    }
}
