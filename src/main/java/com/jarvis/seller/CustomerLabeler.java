package com.jarvis.seller;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 판매자에게 노출할 고객 라벨 (노션 I-14, 2026-08-06 프라이버시 개정).
 *
 * <p><b>왜 memberId를 그대로 주면 안 되나</b> — 리포트의 {@code orderId}+{@code memberId}를 판매자 주문
 * 화면(S-2: {@code orderId}+수령인 실명)과 대조하면 회원이 <b>재식별</b>된다. memberId는 가명이 아니라
 * 재식별 키다.
 *
 * <p>라벨은 {@code HMAC-SHA256(secret, "brandId:memberId")}의 앞 30비트를 Base32로 찍은 6자다.
 * 세 성질을 동시에 만족한다 — <b>결정적</b>(같은 회원=같은 라벨이라 반복 패턴을 표현할 수 있다) ·
 * <b>브랜드별 상이</b>(브랜드 간 대조 추적 차단) · <b>역산 불가</b>(secret 없이 복원 불가).
 *
 * <p>저장하지 않는 계산값이라 <b>DDL 변경이 없다</b>. 라벨이 곧 사례번호이고, 조치가 필요하면
 * 관리자가 같은 HMAC으로 역조회한다.
 */
@Component
public class CustomerLabeler {

    /** RFC 4648 Base32 — 사람이 읽고 옮겨 적는 사례번호라 0/O·1/I가 없는 알파벳을 쓴다 */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int LABEL_LENGTH = 6;

    private final SecretKeySpec key;

    /**
     * 시크릿이 없으면 기동에 실패한다({@code app.internal.token}과 같은 fail-fast) — 없다고
     * memberId로 폴백하면 이 개정이 막으려던 노출이 조용히 되살아난다.
     */
    public CustomerLabeler(@Value("${app.privacy.customer-label-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** @return 6자 라벨. memberId가 null이면(비회원 귀속 행) null — 라벨링할 대상이 없다 */
    public String label(Long brandId, Long memberId) {
        if (memberId == null) {
            return null;
        }
        byte[] mac = hmac(brandId + ":" + memberId);
        // 앞 4바이트(32비트)에서 상위 2비트를 버리고 30비트를 5비트씩 6조각으로 쓴다
        long bits = ((long) (mac[0] & 0xFF) << 24) | ((mac[1] & 0xFF) << 16)
                | ((mac[2] & 0xFF) << 8) | (mac[3] & 0xFF);
        StringBuilder label = new StringBuilder(LABEL_LENGTH);
        for (int chunk = LABEL_LENGTH - 1; chunk >= 0; chunk--) {
            label.append(ALPHABET.charAt((int) ((bits >>> (chunk * 5)) & 0x1F)));
        }
        return label.toString();
    }

    private byte[] hmac(String message) {
        try {
            // Mac은 스레드 안전하지 않아 호출마다 만든다 — 집계 응답당 수십 회라 비용이 문제되지 않는다
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256은 JDK 필수 알고리즘이라 여기 오면 런타임이 깨진 것이다
            throw new IllegalStateException("고객 라벨 생성 실패", e);
        }
    }
}
