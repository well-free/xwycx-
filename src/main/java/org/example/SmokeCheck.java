package org.example;

import org.example.trade.CreateOrderRequest;
import org.example.trade.Order;
import org.example.trade.OrderPlacementResult;
import org.example.trade.OrderService;
import org.example.trade.OrderSide;
import org.example.trade.OrderStatus;
import org.example.trade.Trade;
import org.example.web.TradingHttpServer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public final class SmokeCheck {
    private SmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        testBuyMatchesSell();
        testPartialFill();
        testPricePriorityBeatsTime();
        testTimePriorityAtSamePrice();
        testCancelStopsFurtherMatching();
        testValidation();
        testHttpApi();
        System.out.println("All tests passed");
    }

    private static void testBuyMatchesSell() {
        OrderService service = new OrderService();
        service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 10));
        OrderPlacementResult result = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("100"), 10));
        assertEquals(1, result.getTrades().size(), "trade count");
        assertEquals(OrderStatus.FILLED, result.getOrder().getStatus(), "buy order status");
        assertEquals(2, service.listOrders().size(), "order count");
        assertEquals(1, service.listTrades().size(), "stored trade count");
    }

    private static void testPartialFill() {
        OrderService service = new OrderService();
        OrderPlacementResult sell = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 10));
        OrderPlacementResult buy = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("100"), 4));
        assertEquals(OrderStatus.PARTIALLY_FILLED, sell.getOrder().getStatus(), "sell status");
        assertEquals(6L, sell.getOrder().getRemainingQuantity(), "sell remaining");
        assertEquals(OrderStatus.FILLED, buy.getOrder().getStatus(), "buy status");
        assertEquals(1, service.listTrades().size(), "trade count");
        assertEquals(4L, service.listTrades().get(0).getQuantity(), "trade qty");
    }

    private static void testPricePriorityBeatsTime() {
        OrderService service = new OrderService();
        Order cheaper = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 5)).getOrder();
        Order moreExpensive = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("101"), 5)).getOrder();
        service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("101"), 10));
        List<Trade> trades = service.listTrades();
        assertEquals(2, trades.size(), "trade count");
        assertEquals(cheaper.getId(), trades.get(0).getSellOrderId(), "best price should trade first");
        assertEquals(moreExpensive.getId(), trades.get(1).getSellOrderId(), "second trade");
    }

    private static void testTimePriorityAtSamePrice() {
        OrderService service = new OrderService();
        Order first = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 5)).getOrder();
        Order second = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 5)).getOrder();
        service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("100"), 10));
        List<Trade> trades = service.listTrades();
        assertEquals(2, trades.size(), "trade count");
        assertEquals(first.getId(), trades.get(0).getSellOrderId(), "earlier order should trade first");
        assertEquals(second.getId(), trades.get(1).getSellOrderId(), "later order should trade second");
    }

    private static void testCancelStopsFurtherMatching() {
        OrderService service = new OrderService();
        Order order = service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.SELL, bd("100"), 10)).getOrder();
        service.cancelOrder(order.getId());
        service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("100"), 10));
        assertEquals(OrderStatus.CANCELED, service.getOrder(order.getId()).getStatus(), "canceled status");
        assertEquals(0, service.listTrades().size(), "no trades after cancel");
    }

    private static void testValidation() {
        OrderService service = new OrderService();
        boolean failed = false;
        try {
            service.placeOrder(new CreateOrderRequest("AAPL", OrderSide.BUY, bd("0"), 10));
        } catch (IllegalArgumentException e) {
            failed = true;
        }
        if (!failed) {
            throw new AssertionError("invalid price should fail");
        }
    }

    private static void testHttpApi() throws Exception {
        try (TradingHttpServer server = new TradingHttpServer(0)) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://localhost:" + server.getPort();

            HttpResponse<String> create = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"price\":101,\"quantity\":3}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, create.statusCode(), "create status");
            if (!create.body().contains("\"symbol\":\"AAPL\"")) {
                throw new AssertionError("create response should contain order");
            }
            long orderId = extractFirstOrderId(create.body());

            HttpResponse<String> order = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders/" + orderId))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, order.statusCode(), "get order status");
            if (!order.body().contains("\"id\":" + orderId)) {
                throw new AssertionError("get order should contain id");
            }

            HttpResponse<String> cancel = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders/" + orderId + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, cancel.statusCode(), "cancel status");
            if (!cancel.body().contains("\"status\":\"CANCELED\"")) {
                throw new AssertionError("cancel response should show canceled");
            }

            HttpResponse<String> cancelAgain = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders/" + orderId + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, cancelAgain.statusCode(), "cancel twice status");

            HttpResponse<String> orders = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, orders.statusCode(), "list status");
            if (!orders.body().contains("\"count\":1")) {
                throw new AssertionError("list response should contain count");
            }

            HttpResponse<String> trades = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/trades"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, trades.statusCode(), "trade list status");
        }
    }

    private static long extractFirstOrderId(String body) {
        int keyIndex = body.indexOf("\"order\"");
        if (keyIndex < 0) {
            throw new AssertionError("order response missing order object");
        }
        int idIndex = body.indexOf("\"id\":", keyIndex);
        if (idIndex < 0) {
            throw new AssertionError("order response missing id");
        }
        int start = idIndex + "\"id\":".length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + " expected " + expected + " but was " + actual);
        }
    }
}
