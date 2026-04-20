package com.chatagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    @Value("${app.jwt.secret:default-secret-key-for-development-only-change-in-production}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    private final ConcurrentHashMap.KeySetView<String, Boolean> blacklistedJti =
            ConcurrentHashMap.newKeySet();

    public String createToken(String username, Long userId, String role) {
        long now = System.currentTimeMillis();
        long exp = now + jwtExpirationMs;
        String jti = UUID.randomUUID().toString();
        return Jwts.builder()
                .id(jti)
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(exp))
                .signWith(signingKey())
                .compact();
    }

    public Optional<Claims> parseValid(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(signingKey())
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
            String jti = claims.getId();
            if (jti != null && blacklistedJti.contains(jti)) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ExpiredJwtException e) {
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void blacklistJti(String jti) {
        if (jti != null) {
            blacklistedJti.add(jti);
        }
    }

    private SecretKey signingKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
