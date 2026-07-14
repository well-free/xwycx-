package org.example.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.example.config.AppProperties;
import org.example.service.AuthService;
import org.example.web.dto.AuthLoginResponse;
import org.example.web.dto.AuthUserResponse;
import org.example.web.dto.SmsLoginRequest;
import org.example.web.dto.SmsSendRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AppProperties properties;

    public AuthController(AuthService authService, AppProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/sms/send")
    public Map<String, Object> send(@Valid @RequestBody SmsSendRequest request, HttpServletRequest servletRequest) {
        authService.sendSms(request, clientIp(servletRequest));
        return Map.of("message", "sms sent");
    }

    @GetMapping("/sms/config")
    public Map<String, Object> smsConfig() {
        boolean local = "local".equalsIgnoreCase(properties.getSms().getProvider());
        return Map.of(
                "provider", properties.getSms().getProvider(),
                "local", local,
                "localCode", local ? properties.getSms().getLocalCode() : "",
                "codeTtlSeconds", properties.getSms().getCodeTtlSeconds()
        );
    }

    @PostMapping("/sms/login")
    public AuthLoginResponse login(@Valid @RequestBody SmsLoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        authService.logout(token);
        return Map.of("message", "logged out");
    }

    @GetMapping("/me")
    public AuthUserResponse me(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        return authService.me(token);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
