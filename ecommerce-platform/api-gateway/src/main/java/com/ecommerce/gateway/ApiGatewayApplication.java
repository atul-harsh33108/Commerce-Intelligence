package com.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The front door.
 *
 * <p>{@code @ConfigurationPropertiesScan} rather than {@code @EnableConfigurationProperties} on each
 * type. Without either, a {@code @ConfigurationProperties} record is an ordinary class that nothing
 * instantiates, and the failure is the least helpful one Spring produces — "required a bean of type
 * JwtProperties that could not be found", which reads as a missing component rather than a missing
 * registration.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
