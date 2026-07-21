package com.example.aps.cwp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SchedulerProperties.class)
public class CwpSchedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CwpSchedulerApplication.class, args);
    }
}
