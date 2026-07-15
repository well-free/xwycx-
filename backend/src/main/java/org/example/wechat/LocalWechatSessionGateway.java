package org.example.wechat;

import org.example.web.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalWechatSessionGateway implements WechatSessionGateway {
    @Override
    public WechatSession exchange(String code) {
        if (code == null || code.isBlank()) {
            throw BusinessException.badRequest("invalid wechat code");
        }
        return new WechatSession("local-app", "local-" + code, null);
    }
}
