package org.example.web;

import jakarta.validation.Valid;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sms/send")
    public Map<String, Object> send(@Valid @RequestBody SmsSendRequest request) {
        authService.sendSms(request);
        return Map.of("message", "sms sent");
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
}
