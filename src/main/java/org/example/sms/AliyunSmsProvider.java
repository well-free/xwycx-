package org.example.sms;

import org.example.config.AppProperties;
import org.example.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;

@Service
@ConditionalOnProperty(prefix = "app.sms", name = "provider", havingValue = "aliyun")
public class AliyunSmsProvider implements SmsProvider {
    private final AppProperties.Sms properties;

    public AliyunSmsProvider(AppProperties properties) {
        this.properties = properties.getSms();
    }

    @Override
    public SmsSendResult sendVerificationCode(String phone, String code) {
        validateConfig();
        try {
            Object config = newInstance("com.aliyun.teaopenapi.models.Config");
            invoke(config, "setAccessKeyId", String.class, properties.getAliyunAccessKeyId());
            invoke(config, "setAccessKeySecret", String.class, properties.getAliyunAccessKeySecret());
            invoke(config, "setEndpoint", String.class, properties.getAliyunEndpoint());

            Class<?> clientClass = Class.forName("com.aliyun.dysmsapi20170525.Client");
            Object client = clientClass.getConstructor(config.getClass()).newInstance(config);
            Object request = newInstance("com.aliyun.dysmsapi20170525.models.SendSmsRequest");
            invoke(request, "setPhoneNumbers", String.class, phone);
            invoke(request, "setSignName", String.class, properties.getAliyunSignName());
            invoke(request, "setTemplateCode", String.class, properties.getAliyunTemplateCode());
            invoke(request, "setTemplateParam", String.class, "{\"code\":\"" + code + "\"}");

            Object response = clientClass.getMethod("sendSms", request.getClass()).invoke(client, request);
            Object body = response.getClass().getMethod("getBody").invoke(response);
            String responseCode = readString(body, "getCode");
            String message = readString(body, "getMessage");
            String requestId = readString(body, "getRequestId");
            if (!"OK".equalsIgnoreCase(responseCode)) {
                throw BusinessException.conflict("aliyun sms send failed: " + message);
            }
            return new SmsSendResult(requestId, responseCode, message);
        } catch (BusinessException ex) {
            throw ex;
        } catch (ClassNotFoundException ex) {
            throw BusinessException.conflict("aliyun sms sdk is not available");
        } catch (ReflectiveOperationException ex) {
            throw BusinessException.conflict("aliyun sms send failed");
        }
    }

    private void validateConfig() {
        if (isBlank(properties.getAliyunAccessKeyId())
                || isBlank(properties.getAliyunAccessKeySecret())
                || isBlank(properties.getAliyunSignName())
                || isBlank(properties.getAliyunTemplateCode())) {
            throw BusinessException.conflict("aliyun sms config is not complete");
        }
    }

    private Object newInstance(String className) throws ReflectiveOperationException {
        return Class.forName(className).getConstructor().newInstance();
    }

    private void invoke(Object target, String methodName, Class<?> parameterType, Object value)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, parameterType);
        method.invoke(target, value);
    }

    private String readString(Object target, String methodName) throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value == null ? "" : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
