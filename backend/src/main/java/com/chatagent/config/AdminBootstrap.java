package com.chatagent.config;

import com.chatagent.user.User;
import com.chatagent.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AppProperties appProperties;

    @Override
    public void run(ApplicationArguments args) {
        String u = appProperties.getAdmin().getBootstrapUsername();
        if (!userRepository.existsByUsername(u)) {
            User user = new User();
            user.setUsername(u);
            user.setPasswordHash(
                    passwordEncoder.encode(appProperties.getAdmin().getBootstrapPassword()));
            user.setRole("ADMIN");
            userRepository.save(user);
            log.info("Bootstrapped admin user '{}'", u);
        }
    }
}
