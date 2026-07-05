package org.example.ai.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiRagApplication.class, args);
    }
}
