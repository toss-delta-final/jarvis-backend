package com.jarvis.global.config;

import com.jarvis.global.auth.EnvelopeAccessDeniedHandler;
import com.jarvis.global.auth.EnvelopeAuthenticationEntryPoint;
import com.jarvis.global.auth.JwtAuthenticationFilter;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.internal.InternalTokenFilter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 03 D3·§3-1 — JWT STATELESS 체인. permitAll 목록은 03 D3 명세 그대로.
 * 필터발 401/403은 EntryPoint/AccessDeniedHandler가 envelope로 응답.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalTokenFilter internalTokenFilter;
    private final EnvelopeAuthenticationEntryPoint authenticationEntryPoint;
    private final EnvelopeAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // RT 쿠키의 CSRF 표면은 SameSite=Strict로 봉쇄 (03 §3-1)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> {})
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 🔑 상품 하위 경로 중 로그인 필요 항목은 permitAll보다 먼저 (M-7·P-5)
                        // recommended(P-5)는 미구현이지만 규칙 선점 유지 — 아래 /api/products/** permitAll보다
                        // 선행해야 하므로, 지우면 P-5 구현 시 인증 없이 열리는 함정이 된다
                        .requestMatchers("/api/products/recent", "/api/products/recommended").hasRole("USER")
                        // 🔓 permitAll (03 D3) — 단 A-5(me)는 🔑라 /api/auth/** 보다 먼저 매칭
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/products/**", "/api/categories/**", "/api/brands/**").permitAll()
                        .requestMatchers("/api/cart/**").permitAll() // 게스트 쿠키 허용 (02 D30)
                        .requestMatchers(HttpMethod.POST, "/api/events").permitAll() // E-1 인증 선택
                        .requestMatchers(HttpMethod.POST, "/api/chat/sessions", "/api/chat/tickets").permitAll()
                        .requestMatchers("/api/chat/lists/**").permitAll() // CH-5
                        .requestMatchers("/.well-known/**").permitAll() // JWKS (Phase 5)
                        // /internal은 시큐리티가 아니라 InternalTokenFilter가 지킨다 (03 D4 — 3중 방어의 앱 층)
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 역할 가드
                        .requestMatchers("/api/seller/**", "/api/chat/seller/**").hasRole("SELLER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/orders/**", "/api/order-items/**", "/api/claims/**",
                                "/api/reviews/**", "/api/wishlist/**", "/api/addresses/**",
                                "/api/inquiries/**", "/api/members/**").hasRole("USER")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalTokenFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 로컬 개발만 FE(3000) → BE(8080) 직행 허용 — 배포는 nginx 동일 오리진이라 불필요 (03 §5) */
    @Bean
    @Profile("local")
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
