package com.chatagent.auth;

import com.chatagent.security.JwtService;
import com.chatagent.user.User;
import com.chatagent.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        Authentication auth =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));
        UserDetails ud = (UserDetails) auth.getPrincipal();
        User user =
                userRepository
                        .findByUsername(ud.getUsername())
                        .orElseThrow();
        String token = jwtService.createToken(user.getUsername(), user.getId(), user.getRole());
        return ResponseEntity.ok(
                LoginResponse.builder()
                        .token(token)
                        .username(user.getUsername())
                        .userId(user.getId())
                        .role(user.getRole())
                        .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Optional<Claims> c = jwtService.parseValid(token);
            c.ifPresent(claims -> jwtService.blacklistJti(claims.getId()));
        }
        return ResponseEntity.noContent().build();
    }
}
