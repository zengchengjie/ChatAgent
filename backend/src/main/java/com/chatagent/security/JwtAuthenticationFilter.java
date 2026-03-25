package com.chatagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            Optional<io.jsonwebtoken.Claims> claimsOpt = jwtService.parseValid(token);
            if (claimsOpt.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                var claims = claimsOpt.get();
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                Long uid = claims.get("uid", Long.class);
                var authorities =
                        java.util.Collections.singletonList(
                                new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER")));
                var auth =
                        new UsernamePasswordAuthenticationToken(
                                new JwtPrincipal(uid, username, role), null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        filterChain.doFilter(request, response);
    }
}
