package com.chatagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChatAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatAgentApplication.class, args);
    }
}
