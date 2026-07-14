package org.example.web.dto;

public record AuthLoginResponse(String token, AuthUserResponse user) {
}
