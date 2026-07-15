package org.example.wechat;

public interface WechatSessionGateway {
    WechatSession exchange(String code);
}
