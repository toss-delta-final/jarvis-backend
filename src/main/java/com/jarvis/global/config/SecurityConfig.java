package com.jarvis.global.config;

import com.jarvis.global.auth.EnvelopeAccessDeniedHandler;
import com.jarvis.global.auth.EnvelopeAuthenticationEntryPoint;
import com.jarvis.global.auth.GuestCookieSlidingFilter;
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
    private final GuestCookieSlidingFilter guestCookieSlidingFilter;
    private final EnvelopeAuthenticationEntryPoint authenticationEntryPoint;
    private final EnvelopeAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 토큰을 두지 않는 근거 (2026-08-04 갱신 — 구 근거 "RT만 쿠키"는 AT도 쿠키가 되며 폐기).
                // RT는 SameSite=Strict + Path=/api/auth, AT는 SameSite=Lax다. Lax는 크로스사이트
                // POST·PUT·DELETE에 쿠키를 싣지 않으므로 상태를 바꾸는 요청은 막힌다. 전제는
                // "GET으로 상태를 바꾸는 API를 만들지 않는 것" — 이 규칙이 깨지면 재검토해야 한다.
                .csrf(csrf -> csrf.disable())
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
                        // CH-7 승계 — 회원 AT 필수(게스트는 부를 수 없다). 소유권은 서비스가 귀속 기록으로 검증
                        .requestMatchers(HttpMethod.POST, "/api/chat/sessions/*/claim").authenticated()
                        .requestMatchers("/.well-known/**").permitAll() // JWKS (Phase 5)
                        // /internal은 시큐리티가 아니라 InternalTokenFilter가 지킨다 (03 D4 — 3중 방어의 앱 층)
                        .requestMatchers("/internal/**").permitAll()
                        // 하위 경로까지 열어야 한다 — ALB 타겟인 /actuator/health/liveness(07 §4-1)가
                        // 401을 받으면 인스턴스를 통째로 LB에서 빼버려 헬스 그룹 분리가 역효과를 낸다.
                        // show-details 기본값이 never라 컴포넌트 상세는 노출되지 않는다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // 역할 가드
                        .requestMatchers("/api/seller/**", "/api/chat/seller/**").hasRole("SELLER")
                        // /api/admin/** 가드는 2026-08-11 제거 — AD-1~7 전부 폐기(2026-08-07)로 admin
                        // 컨트롤러가 하나도 없어 죽은 규칙이었다. admin 기능 부활 시 규칙부터 복원할 것
                        .requestMatchers("/api/orders/**", "/api/order-items/**", "/api/claims/**",
                                "/api/reviews/**", "/api/wishlist/**", "/api/addresses/**",
                                "/api/members/**", "/api/profile/**").hasRole("USER")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalTokenFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(guestCookieSlidingFilter, JwtAuthenticationFilter.class);
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
