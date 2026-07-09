package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Redis redis = new Redis();
    private final Mq mq = new Mq();
    private final Order order = new Order();

    public Redis getRedis() {
        return redis;
    }

    public Mq getMq() {
        return mq;
    }

    public Order getOrder() {
        return order;
    }

    public static class Redis {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mq {
        private boolean enabled;
        private String topic = "order-timeout-topic";
        private String catalogTopic = "catalog-change-topic";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getCatalogTopic() {
            return catalogTopic;
        }

        public void setCatalogTopic(String catalogTopic) {
            this.catalogTopic = catalogTopic;
        }
    }

    public static class Order {
        private long timeoutSeconds = 15L;
        private long cacheTtlSeconds = 120L;
        private long emptyTtlSeconds = 30L;

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }

        public long getEmptyTtlSeconds() {
            return emptyTtlSeconds;
        }

        public void setEmptyTtlSeconds(long emptyTtlSeconds) {
            this.emptyTtlSeconds = emptyTtlSeconds;
        }
    }
}
