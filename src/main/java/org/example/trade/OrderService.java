package org.example.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

public final class OrderService {
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final Map<String, OrderBook> books = new LinkedHashMap<>();
    private long nextOrderId = 1L;
    private long nextTradeId = 1L;

    public synchronized OrderPlacementResult placeOrder(CreateOrderRequest request) {
        validateRequest(request);
        String symbol = normalizeSymbol(request.getSymbol());
        Instant now = Instant.now();
        Order order = new Order(nextOrderId++, symbol, request.getSide(), request.getPrice(), request.getQuantity(), now);
        List<Trade> createdTrades = new ArrayList<>();
        OrderBook book = books.computeIfAbsent(symbol, ignored -> new OrderBook());

        if (order.getSide() == OrderSide.BUY) {
            matchBuyOrder(order, book, createdTrades);
            if (order.isOpen() && order.getRemainingQuantity() > 0) {
                book.addBuy(order);
            }
        } else {
            matchSellOrder(order, book, createdTrades);
            if (order.isOpen() && order.getRemainingQuantity() > 0) {
                book.addSell(order);
            }
        }

        orders.put(order.getId(), order);
        return new OrderPlacementResult(order, createdTrades);
    }

    public synchronized Order cancelOrder(long orderId) {
        Order order = requireOrder(orderId);
        if (!order.isOpen()) {
            throw new IllegalStateException("order is not open");
        }
        order.cancel(Instant.now());
        return order;
    }

    public synchronized Order getOrder(long orderId) {
        return requireOrder(orderId);
    }

    public synchronized List<Order> listOrders() {
        return new ArrayList<>(orders.values());
    }

    public synchronized List<Trade> listTrades() {
        return new ArrayList<>(trades);
    }

    private void matchBuyOrder(Order incoming, OrderBook book, List<Trade> createdTrades) {
        while (incoming.getRemainingQuantity() > 0) {
            Order bestSell = book.pollBestSell();
            if (bestSell == null || bestSell.getPrice().compareTo(incoming.getPrice()) > 0) {
                break;
            }
            book.sellOrders().poll();
            long quantity = Math.min(incoming.getRemainingQuantity(), bestSell.getRemainingQuantity());
            executeTrade(incoming, bestSell, quantity, bestSell.getPrice(), createdTrades);
            if (bestSell.isOpen() && bestSell.getRemainingQuantity() > 0) {
                book.addSell(bestSell);
            }
        }
    }

    private void matchSellOrder(Order incoming, OrderBook book, List<Trade> createdTrades) {
        while (incoming.getRemainingQuantity() > 0) {
            Order bestBuy = book.pollBestBuy();
            if (bestBuy == null || bestBuy.getPrice().compareTo(incoming.getPrice()) < 0) {
                break;
            }
            book.buyOrders().poll();
            long quantity = Math.min(incoming.getRemainingQuantity(), bestBuy.getRemainingQuantity());
            executeTrade(bestBuy, incoming, quantity, bestBuy.getPrice(), createdTrades);
            if (bestBuy.isOpen() && bestBuy.getRemainingQuantity() > 0) {
                book.addBuy(bestBuy);
            }
        }
    }

    private void executeTrade(Order buyOrder, Order sellOrder, long quantity, BigDecimal price, List<Trade> createdTrades) {
        Instant now = Instant.now();
        buyOrder.fill(quantity, now);
        sellOrder.fill(quantity, now);
        Trade trade = new Trade(nextTradeId++, buyOrder.getSymbol(), buyOrder.getId(), sellOrder.getId(), price, quantity, now);
        trades.add(trade);
        createdTrades.add(trade);
    }

    private Order requireOrder(long orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new NoSuchElementException("order not found: " + orderId);
        }
        return order;
    }

    private void validateRequest(CreateOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.getSymbol() == null || request.getSymbol().trim().isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.getSide() == null) {
            throw new IllegalArgumentException("side is required");
        }
        if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (request.getQuantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
