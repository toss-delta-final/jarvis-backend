package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 03 D5 — 티켓 claim 고정(05 §1-0) + JWKS로 복원한 public key가 서명을 검증하는지 */
class StreamTicketProviderTest {

    private static final String KID = "test-kid";

    private StreamTicketProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        provider = new StreamTicketProvider(new StreamTicketProperties(privateKeyBase64, KID, 60));
    }

    @Test
    @DisplayName("티켓 claim — sub/sub_type/iss/aud/scope/exp(+60s), 헤더 kid (05 §1-0)")
    void ticketClaims() throws Exception {
        String ticket = provider.createTicket(ChatIdentity.member(123L));

        Jws<Claims> jws = Jwts.parser().verifyWith(publicKeyFromJwks()).build()
                .parseSignedClaims(ticket);
        Claims claims = jws.getPayload();

        assertThat(jws.getHeader().getKeyId()).isEqualTo(KID);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(claims.getSubject()).isEqualTo("123");
        assertThat(claims.get("sub_type", String.class)).isEqualTo("member");
        assertThat(claims.getIssuer()).isEqualTo(StreamTicketProvider.ISSUER);
        assertThat(claims.getAudience()).contains(StreamTicketProvider.AUDIENCE);
        assertThat(claims.get("scope", String.class)).isEqualTo(StreamTicketProvider.SCOPE_CHAT_STREAM);
        long lifetime = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(lifetime).isEqualTo(60);
    }

    @Test
    @DisplayName("S-4 SELLER 티켓 — role/brandId claim 추가(노션 CH-6), 일반 티켓엔 없음")
    void sellerTicket() throws Exception {
        String sellerTicket = provider.createSellerTicket(ChatIdentity.member(7L), 77L);
        String normalTicket = provider.createTicket(ChatIdentity.member(7L));

        Claims sellerClaims = Jwts.parser().verifyWith(publicKeyFromJwks()).build()
                .parseSignedClaims(sellerTicket).getPayload();
        Claims normalClaims = Jwts.parser().verifyWith(publicKeyFromJwks()).build()
                .parseSignedClaims(normalTicket).getPayload();

        assertThat(sellerClaims.get("role", String.class))
                .isEqualTo(StreamTicketProvider.ROLE_SELLER);
        assertThat(sellerClaims.get("brandId", Long.class)).isEqualTo(77L);
        assertThat(sellerClaims.get("scope", String.class))
                .isEqualTo(StreamTicketProvider.SCOPE_CHAT_STREAM);
        assertThat(normalClaims.get("role")).isNull();
        assertThat(normalClaims.get("brandId")).isNull();
    }

    @Test
    @DisplayName("게스트도 동일 경로 — sub_type:guest (03 D5)")
    void guestTicket() throws Exception {
        String ticket = provider.createTicket(ChatIdentity.guest("guest-uuid"));

        Claims claims = Jwts.parser().verifyWith(publicKeyFromJwks()).build()
                .parseSignedClaims(ticket).getPayload();

        assertThat(claims.getSubject()).isEqualTo("guest-uuid");
        assertThat(claims.get("sub_type", String.class)).isEqualTo("guest");
    }

    /** FastAPI가 하는 일 그대로 — JWKS의 n/e에서 public key 복원 */
    @SuppressWarnings("unchecked")
    private PublicKey publicKeyFromJwks() throws Exception {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) provider.jwks().get("keys");
        Map<String, Object> jwk = keys.get(0);
        assertThat(jwk.get("kid")).isEqualTo(KID);
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("n")));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }
}
