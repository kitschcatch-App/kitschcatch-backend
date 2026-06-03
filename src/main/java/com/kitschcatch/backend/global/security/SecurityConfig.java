package com.kitschcatch.backend.global.security;

import com.kitschcatch.backend.domain.auth.client.KakaoOAuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, KakaoOAuthProperties.class})
public class SecurityConfig {

	@Bean
	public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
		return new JwtTokenProvider(jwtProperties);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		JwtAuthenticationFilter jwtAuthenticationFilter,
		ApiAuthenticationExceptionHandler apiAuthenticationExceptionHandler
	) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint(apiAuthenticationExceptionHandler)
				.accessDeniedHandler(apiAuthenticationExceptionHandler))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
					"/api/auth/**",
					"/ws",
					"/ws/**",
					"/v3/api-docs",
					"/v3/api-docs/**",
					"/swagger-ui.html",
					"/swagger-ui/**"
				).permitAll()
				.anyRequest().authenticated())
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
