package org.example.trade;

import java.util.Comparator;
import java.util.PriorityQueue;

final class OrderBook {
    private final PriorityQueue<Order> buyOrders;
    private final PriorityQueue<Order> sellOrders;

    OrderBook() {
        this.buyOrders = new PriorityQueue<>(Comparator
                .comparing(Order::getPrice).reversed()
                .thenComparing(Order::getCreatedAt)
                .thenComparingLong(Order::getId));
        this.sellOrders = new PriorityQueue<>(Comparator
                .comparing(Order::getPrice)
                .thenComparing(Order::getCreatedAt)
                .thenComparingLong(Order::getId));
    }

    PriorityQueue<Order> buyOrders() {
        return buyOrders;
    }

    PriorityQueue<Order> sellOrders() {
        return sellOrders;
    }

    Order pollBestBuy() {
        return pollBestActive(buyOrders);
    }

    Order pollBestSell() {
        return pollBestActive(sellOrders);
    }

    void addBuy(Order order) {
        if (order.isOpen() && order.getRemainingQuantity() > 0) {
            buyOrders.add(order);
        }
    }

    void addSell(Order order) {
        if (order.isOpen() && order.getRemainingQuantity() > 0) {
            sellOrders.add(order);
        }
    }

    private Order pollBestActive(PriorityQueue<Order> queue) {
        while (!queue.isEmpty()) {
            Order order = queue.peek();
            if (order.isOpen() && order.getRemainingQuantity() > 0) {
                return order;
            }
            queue.poll();
        }
        return null;
    }
}
