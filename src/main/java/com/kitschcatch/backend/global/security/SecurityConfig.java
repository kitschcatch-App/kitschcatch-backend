package com.kitschcatch.backend.global.security;

import com.kitschcatch.backend.domain.auth.client.KakaoOAuthProperties;
import com.kitschcatch.backend.domain.report.review.ReportReviewAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

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
		ApiAuthenticationExceptionHandler apiAuthenticationExceptionHandler,
		ReportReviewAccess reportReviewAccess
	) throws Exception {
		var reportReview = PathPatternRequestMatcher.withDefaults().matcher("/api/admin/reports/**");
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint(apiAuthenticationExceptionHandler)
				.accessDeniedHandler((request, response, error) -> {
					if (reportReview.matches(request)) apiAuthenticationExceptionHandler.denyReportReview(response);
					else apiAuthenticationExceptionHandler.handle(request, response, error);
				}))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(reportReview).access((authentication, context) -> new AuthorizationDecision(
					authentication.get().getPrincipal() instanceof AuthenticatedUser actor
						&& reportReviewAccess.allowed(actor.userId())))
				.requestMatchers(HttpMethod.GET, "/.well-known/apple-app-site-association").permitAll()
				.requestMatchers(HttpMethod.HEAD, "/.well-known/apple-app-site-association").permitAll()
				.requestMatchers(
					"/api/auth/**",
					"/api/payments/webhooks/toss",
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
