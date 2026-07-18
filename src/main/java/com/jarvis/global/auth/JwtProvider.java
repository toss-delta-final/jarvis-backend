package com.jarvis.global.auth;

import com.jarvis.member.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration accessTokenValidity;

    public JwtProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidity = Duration.ofMinutes(properties.accessTokenMinutes());
    }

    /** AT 생성 — sub=memberId, role claim, HS256 (03 D3) */
    public String createAccessToken(Long memberId, Role role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenValidity.toMillis()))
                .signWith(key, Jwts.SIG.HS256) // 03 §5 — HS256 고정(미지정 시 jjwt가 키 길이로 HS384를 골라버림)
                .compact();
    }

    /**
     * AT 검증·파싱.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 만료 (→ AUTH_TOKEN_EXPIRED)
     * @throws io.jsonwebtoken.JwtException        그 외 무효 (→ AUTH_REQUIRED)
     */
    public AuthUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthUser(Long.valueOf(claims.getSubject()),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
    }
}
