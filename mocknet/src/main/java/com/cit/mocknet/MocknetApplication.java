package com.cit.mocknet;

import com.cit.mocknet.config.MocknetProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MocknetProperties.class)
public class MocknetApplication {

    public static void main(String[] args) {
        SpringApplication.run(MocknetApplication.class, args);
    }
}
