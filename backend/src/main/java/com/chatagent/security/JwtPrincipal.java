package com.chatagent.security;

public record JwtPrincipal(Long userId, String username, String role) {}
