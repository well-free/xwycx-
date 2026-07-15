package org.example.web;

import org.example.service.OrderCommandService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.TradeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
@ConditionalOnProperty(prefix = "app.matching", name = "enabled", havingValue = "true")
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
