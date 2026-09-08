package com.nebdev.devopsagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DevopsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevopsAgentApplication.class, args);
    }
}
