package com.jarvis.chat;

import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 스트림 티켓 발급 (03 D5, 05 §1-0) — 단명 RS256 JWT. private key는 Spring만 보관,
 * FastAPI는 JWKS(public key)로 검증만 한다. 신원(sub)은 서버가 검증해 채운다.
 */
@Component
public class StreamTicketProvider {

    public static final String ISSUER = "jarvis-spring-auth";
    public static final String AUDIENCE = "jarvis-fastapi-ai";
    public static final String SCOPE_CHAT_STREAM = "chat:stream";
    public static final String ROLE_SELLER = "seller";

    private static final String CLAIM_SUB_TYPE = "sub_type";
    private static final String CLAIM_SCOPE = "scope";
    // 노션 CH-6 계약(2026-07-18 확정): 판매자 티켓은 role=seller + brandId — FastAPI가 노션 기준으로 검증
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_BRAND_ID = "brandId";

    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;
    private final String kid;
    private final Duration ttl;

    public StreamTicketProvider(StreamTicketProperties properties) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            this.privateKey = (RSAPrivateCrtKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(properties.privateKey())));
            this.publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "STREAM_TICKET_PRIVATE_KEY가 유효한 base64(PKCS#8 DER) RSA 키가 아닙니다", e);
        }
        this.kid = properties.kid();
        this.ttl = Duration.ofSeconds(properties.ttlSeconds());
    }

    /** 티켓 claim은 05 §1-0 고정 — sub/sub_type/iss/aud/scope/exp */
    public String createTicket(ChatIdentity identity) {
        return buildTicket(identity, null);
    }

    /** S-4 SELLER 스코프 티켓 (04 §7) — brandId는 BE가 DB에서 도출한 값만(클라이언트/LLM 주장 무시) */
    public String createSellerTicket(ChatIdentity identity, Long brandId) {
        return buildTicket(identity, brandId);
    }

    private String buildTicket(ChatIdentity identity, Long brandId) {
        Date now = new Date();
        var builder = Jwts.builder()
                .header().keyId(kid).and()
                .subject(identity.sub())
                .claim(CLAIM_SUB_TYPE, identity.subType())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim(CLAIM_SCOPE, SCOPE_CHAT_STREAM);
        if (brandId != null) {
            builder.claim(CLAIM_ROLE, ROLE_SELLER).claim(CLAIM_BRAND_ID, brandId);
        }
        return builder
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl.toMillis()))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /** RFC 7517 JWK Set — public key만 노출. FastAPI가 kid로 키를 찾아 검증 (05 §1-0) */
    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", kid,
                "n", base64Url(publicKey.getModulus()),
                "e", base64Url(publicKey.getPublicExponent()))));
    }

    /** JWK 정수 인코딩 — unsigned big-endian, 부호용 선행 0x00 제거 (RFC 7518 §2) */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
