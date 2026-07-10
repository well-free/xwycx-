package org.example.web;

import jakarta.validation.Valid;
import org.example.payment.PaymentChannel;
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

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public PaymentResponse create(@Valid @RequestBody PaymentCreateRequest request) {
        return paymentService.create(request);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable long id) {
        return paymentService.get(id);
    }

    @GetMapping
    public ApiPageResponse<PaymentResponse> list() {
        var items = paymentService.list();
        return new ApiPageResponse<>(items.size(), items);
    }

    @PostMapping("/{id}/close")
    public PaymentResponse close(@PathVariable long id) {
        return paymentService.close(id);
    }

    @PostMapping("/callbacks/{channel}")
    public PaymentResponse callback(@PathVariable PaymentChannel channel, @Valid @RequestBody PaymentCallbackRequest request) {
        return paymentService.handleCallback(channel, request);
    }

    @PostMapping("/{id}/refunds")
    public RefundResponse refund(@PathVariable long id, @Valid @RequestBody RefundCreateRequest request) {
        return paymentService.refund(id, request);
    }

    @GetMapping("/{id}/refunds")
    public ApiPageResponse<RefundResponse> listRefunds(@PathVariable long id) {
        var items = paymentService.listRefunds(id);
        return new ApiPageResponse<>(items.size(), items);
    }
}
