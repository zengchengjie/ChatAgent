package com.chatagent.auth;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginResponse {
    String token;
    String username;
    Long userId;
    String role;
}
