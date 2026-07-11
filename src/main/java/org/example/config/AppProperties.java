package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private final Redis redis = new Redis();
    private final Mq mq = new Mq();
    private final Order order = new Order();
    private final Payment payment = new Payment();
    private final Sms sms = new Sms();

    public Redis getRedis() {
        return redis;
    }

    public Mq getMq() {
        return mq;
    }

    public Order getOrder() {
        return order;
    }

    public Payment getPayment() {
        return payment;
    }

    public Sms getSms() {
        return sms;
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

    public static class Payment {
        private long timeoutSeconds = 300L;
        private String mode = "mock";
        private String callbackSecret = "mock-signature";
        private String callbackBaseUrl = "https://xwycx.xyz";
        private String alipayAppId = "";
        private String wechatMchId = "";

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getCallbackSecret() {
            return callbackSecret;
        }

        public void setCallbackSecret(String callbackSecret) {
            this.callbackSecret = callbackSecret;
        }

        public String getCallbackBaseUrl() {
            return callbackBaseUrl;
        }

        public void setCallbackBaseUrl(String callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
        }

        public String getAlipayAppId() {
            return alipayAppId;
        }

        public void setAlipayAppId(String alipayAppId) {
            this.alipayAppId = alipayAppId;
        }

        public String getWechatMchId() {
            return wechatMchId;
        }

        public void setWechatMchId(String wechatMchId) {
            this.wechatMchId = wechatMchId;
        }
    }

    public static class Sms {
        private String provider = "local";
        private String localCode = "123456";
        private int codeLength = 6;
        private long codeTtlSeconds = 300L;
        private String aliyunAccessKeyId = "";
        private String aliyunAccessKeySecret = "";
        private String aliyunSignName = "";
        private String aliyunTemplateCode = "";
        private String aliyunEndpoint = "dysmsapi.aliyuncs.com";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getLocalCode() {
            return localCode;
        }

        public void setLocalCode(String localCode) {
            this.localCode = localCode;
        }

        public int getCodeLength() {
            return codeLength;
        }

        public void setCodeLength(int codeLength) {
            this.codeLength = codeLength;
        }

        public long getCodeTtlSeconds() {
            return codeTtlSeconds;
        }

        public void setCodeTtlSeconds(long codeTtlSeconds) {
            this.codeTtlSeconds = codeTtlSeconds;
        }

        public String getAliyunAccessKeyId() {
            return aliyunAccessKeyId;
        }

        public void setAliyunAccessKeyId(String aliyunAccessKeyId) {
            this.aliyunAccessKeyId = aliyunAccessKeyId;
        }

        public String getAliyunAccessKeySecret() {
            return aliyunAccessKeySecret;
        }

        public void setAliyunAccessKeySecret(String aliyunAccessKeySecret) {
            this.aliyunAccessKeySecret = aliyunAccessKeySecret;
        }

        public String getAliyunSignName() {
            return aliyunSignName;
        }

        public void setAliyunSignName(String aliyunSignName) {
            this.aliyunSignName = aliyunSignName;
        }

        public String getAliyunTemplateCode() {
            return aliyunTemplateCode;
        }

        public void setAliyunTemplateCode(String aliyunTemplateCode) {
            this.aliyunTemplateCode = aliyunTemplateCode;
        }

        public String getAliyunEndpoint() {
            return aliyunEndpoint;
        }

        public void setAliyunEndpoint(String aliyunEndpoint) {
            this.aliyunEndpoint = aliyunEndpoint;
        }
    }
}
