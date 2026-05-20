package com.kitschcatch.backend.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
	String secret,
	Duration accessTokenTtl,
	Duration refreshTokenTtl
) {
}
