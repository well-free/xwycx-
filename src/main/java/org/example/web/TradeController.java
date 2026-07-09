package org.example.web;

import org.example.service.OrderCommandService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.TradeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
public class TradeController {
    private final OrderCommandService service;

    public TradeController(OrderCommandService service) {
        this.service = service;
    }

    @GetMapping
    public ApiPageResponse<TradeResponse> listTrades() {
        var items = service.listTrades();
        return new ApiPageResponse<>(items.size(), items);
    }
}
