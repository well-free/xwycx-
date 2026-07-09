package org.example.web.dto;

import java.util.List;

public record OrderPlacementResponse(OrderResponse order, List<TradeResponse> trades) {
}
