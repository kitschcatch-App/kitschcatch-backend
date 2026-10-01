// 소셜 인증 설정을 등록한다.
package com.kitschcatch.backend.domain.auth.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SocialOAuthProperties.class)
public class SocialAuthConfiguration {}
