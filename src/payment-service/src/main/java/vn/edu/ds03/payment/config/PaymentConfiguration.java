package vn.edu.ds03.payment.config;


import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentSettings.class)
public class PaymentConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
}
