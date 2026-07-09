package org.example.web;

import org.example.trade.CreateOrderRequest;
import org.example.trade.Order;
import org.example.trade.OrderPlacementResult;
import org.example.trade.OrderSide;
import org.example.trade.OrderStatus;
import org.example.trade.Trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonCodec {
    private JsonCodec() {
    }

    public static CreateOrderRequest parseCreateOrderRequest(String json) {
        Map<String, String> values = parseObject(json);
        String symbol = values.get("symbol");
        OrderSide side = OrderSide.from(values.get("side"));
        BigDecimal price = new BigDecimal(required(values, "price"));
        long quantity = Long.parseLong(required(values, "quantity"));
        return new CreateOrderRequest(symbol, side, price, quantity);
    }

    public static String orderJson(Order order) {
        return "{\"id\":" + order.getId()
                + ",\"symbol\":\"" + escape(order.getSymbol()) + "\""
                + ",\"side\":\"" + order.getSide() + "\""
                + ",\"price\":" + price(order.getPrice())
                + ",\"originalQuantity\":" + order.getOriginalQuantity()
                + ",\"filledQuantity\":" + order.getFilledQuantity()
                + ",\"remainingQuantity\":" + order.getRemainingQuantity()
                + ",\"status\":\"" + order.getStatus() + "\""
                + ",\"createdAt\":\"" + order.getCreatedAt() + "\""
                + ",\"updatedAt\":\"" + order.getUpdatedAt() + "\""
                + "}";
    }

    public static String tradeJson(Trade trade) {
        return "{\"id\":" + trade.getId()
                + ",\"symbol\":\"" + escape(trade.getSymbol()) + "\""
                + ",\"buyOrderId\":" + trade.getBuyOrderId()
                + ",\"sellOrderId\":" + trade.getSellOrderId()
                + ",\"price\":" + price(trade.getPrice())
                + ",\"quantity\":" + trade.getQuantity()
                + ",\"executedAt\":\"" + trade.getExecutedAt() + "\""
                + "}";
    }

    public static String placementResponseJson(OrderPlacementResult result) {
        return "{\"order\":" + orderJson(result.getOrder())
                + ",\"trades\":" + tradeArrayJson(result.getTrades())
                + "}";
    }

    public static String ordersResponseJson(List<Order> orders) {
        return "{\"count\":" + orders.size() + ",\"items\":" + orderArrayJson(orders) + "}";
    }

    public static String tradesResponseJson(List<Trade> trades) {
        return "{\"count\":" + trades.size() + ",\"items\":" + tradeArrayJson(trades) + "}";
    }

    public static String errorJson(String message) {
        return "{\"error\":\"" + escape(message) + "\"}";
    }

    public static String okJson(String message) {
        return "{\"message\":\"" + escape(message) + "\"}";
    }

    public static String orderArrayJson(List<Order> orders) {
        List<String> items = new ArrayList<>();
        for (Order order : orders) {
            items.add(orderJson(order));
        }
        return "[" + String.join(",", items) + "]";
    }

    public static String tradeArrayJson(List<Trade> trades) {
        List<String> items = new ArrayList<>();
        for (Trade trade : trades) {
            items.add(tradeJson(trade));
        }
        return "[" + String.join(",", items) + "]";
    }

    public static Map<String, String> parseObject(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json is required");
        }
        Parser parser = new Parser(json);
        return parser.parseObject();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String price(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input.trim();
        }

        private Map<String, String> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, String> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                skipWhitespace();
                ensureEnd();
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = readValue();
                values.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    skipWhitespace();
                    ensureEnd();
                    return values;
                }
                throw error("Expected ',' or '}'");
            }
        }

        private String readValue() {
            if (peek('"')) {
                return readString();
            }
            int start = index;
            while (index < input.length()) {
                char ch = input.charAt(index);
                if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) {
                    break;
                }
                index++;
            }
            String token = input.substring(start, index).trim();
            if (token.isEmpty()) {
                throw error("Expected value");
            }
            if ("null".equals(token)) {
                return null;
            }
            return token;
        }

        private String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < input.length()) {
                char ch = input.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= input.length()) {
                        throw error("Invalid escape sequence");
                    }
                    char esc = input.charAt(index++);
                    switch (esc) {
                        case '"', '\\', '/' -> builder.append(esc);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(readUnicode());
                        default -> throw error("Unsupported escape: " + esc);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicode() {
            if (index + 4 > input.length()) {
                throw error("Invalid unicode escape");
            }
            int codePoint = Integer.parseInt(input.substring(index, index + 4), 16);
            index += 4;
            return (char) codePoint;
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) {
            if (index >= input.length() || input.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < input.length() && input.charAt(index) == expected;
        }

        private void ensureEnd() {
            skipWhitespace();
            if (index != input.length()) {
                throw error("Unexpected trailing content");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + index);
        }
    }
}
