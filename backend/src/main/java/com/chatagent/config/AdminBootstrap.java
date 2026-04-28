package com.chatagent.config;

import com.chatagent.user.User;
import com.chatagent.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap-username:admin}")
    private String bootstrapUsername;

    @Value("${app.admin.bootstrap-password:admin}")
    private String bootstrapPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername(bootstrapUsername)) {
            User user = new User();
            user.setUsername(bootstrapUsername);
            user.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            user.setRole("ADMIN");
            userRepository.save(user);
            log.info("Bootstrapped admin user '{}'", bootstrapUsername);
        }
    }
}
