package org.example.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.example.service.WechatAuthService;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.WechatBindPhoneRequest;
import org.example.web.dto.WechatLoginRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
@RequestMapping("/api/auth/wechat")
public class WechatAuthController {
    private final WechatAuthService wechatAuthService;

    public WechatAuthController(WechatAuthService wechatAuthService) {
        this.wechatAuthService = wechatAuthService;
    }

    @PostMapping("/login")
    public AuthLoginResponse login(@Valid @RequestBody WechatLoginRequest request,
                                   HttpServletRequest servletRequest) {
        return wechatAuthService.login(request, clientIp(servletRequest));
    }

    @PostMapping("/bind-phone")
    public AuthLoginResponse bindPhone(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @Valid @RequestBody WechatBindPhoneRequest request) {
        return wechatAuthService.bindPhone(token, request);
    }

    private String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (isLoopback(remoteAddress)) {
            String realIp = sanitizedIp(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
        }
        return remoteAddress;
    }

    private boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private String sanitizedIp(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || !candidate.matches("[0-9A-Fa-f:.]{2,45}")) {
            return null;
        }
        try {
            InetAddress.getByName(candidate);
            return candidate;
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
