package com.cit.clsnet;

import com.cit.clsnet.config.ClsNetProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ClsNetProperties.class)
public class ClsNetApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClsNetApplication.class, args);
    }
}
