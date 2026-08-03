package com.jsd.aird;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JsdAirdApplication {

    public static void main(String[] args) {
        SpringApplication.run(JsdAirdApplication.class, args);
    }
}
