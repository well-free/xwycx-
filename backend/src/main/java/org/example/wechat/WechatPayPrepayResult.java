package org.example.wechat;

public record WechatPayPrepayResult(
        String prepayId,
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign) {
}
