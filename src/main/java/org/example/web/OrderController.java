package org.example.web;

import jakarta.validation.Valid;
import org.example.service.OrderCommandService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.OrderCreateRequest;
import org.example.web.dto.OrderPlacementResponse;
import org.example.web.dto.OrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderCommandService service;

    public OrderController(OrderCommandService service) {
        this.service = service;
    }

    @PostMapping
    public OrderPlacementResponse place(@Valid @RequestBody OrderCreateRequest request) {
        return service.place(request);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping
    public ApiPageResponse<OrderResponse> list() {
        var items = service.listOrders();
        return new ApiPageResponse<>(items.size(), items);
    }
}
