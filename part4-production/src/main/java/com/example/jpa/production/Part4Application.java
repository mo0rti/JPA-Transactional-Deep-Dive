package com.example.jpa.production;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableRetry
@EnableAsync
public class Part4Application {

    public static void main(String[] args) {
        SpringApplication.run(Part4Application.class, args);
    }
}
