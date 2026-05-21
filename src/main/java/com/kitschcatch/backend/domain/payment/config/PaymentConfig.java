package com.kitschcatch.backend.domain.payment.config;

import com.kitschcatch.backend.domain.payment.client.TossPaymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
public class PaymentConfig {
}
