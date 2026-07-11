package org.example.web.dto;

import org.example.auth.UserRole;

public record AuthUserResponse(long id, String phone, UserRole role) {
}
