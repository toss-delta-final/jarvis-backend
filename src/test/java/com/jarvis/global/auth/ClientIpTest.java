package com.jarvis.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 접속자 IP 해석 — 오른쪽 기준 (nginx가 X-Forwarded-For를 이어붙이는 구조) */
class ClientIpTest {

    private static MockHttpServletRequest request(String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        return request;
    }

    @Test
    @DisplayName("오른쪽에서 N번째를 클라이언트로 본다 (ALB+nginx = 2)")
    void readsNthFromRight() {
        // [클라이언트 실제 IP], [ALB IP] 순으로 쌓인다
        assertThat(ClientIp.resolve(request("203.0.113.9, 172.31.0.5"), 2))
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("클라이언트가 헤더를 위조해도 무시된다 — 왼쪽은 신뢰하지 않는다")
    void ignoresSpoofedLeftmostValue() {
        // 공격자가 "1.1.1.1"을 직접 넣어 보내도, 우리 인프라가 오른쪽에 덧붙인 값이 진실이다
        String forwarded = "1.1.1.1, 203.0.113.9, 172.31.0.5";

        assertThat(ClientIp.resolve(request(forwarded), 2)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("홉이 하나 더 끼면 깊이를 늘려 맞춘다 (터널 구성 변경 대비)")
    void deeperChain() {
        String forwarded = "1.1.1.1, 203.0.113.9, 10.1.2.3, 172.31.0.5";

        assertThat(ClientIp.resolve(request(forwarded), 3)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("헤더가 깊이보다 짧으면 가장 왼쪽으로 떨어진다 (로컬 등 홉이 적은 환경)")
    void shorterThanDepthFallsBackToLeftmost() {
        assertThat(ClientIp.resolve(request("203.0.113.9"), 2)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("헤더가 없거나 비면 소켓 주소를 쓴다")
    void noHeaderUsesRemoteAddr() {
        assertThat(ClientIp.resolve(request(null), 2)).isEqualTo("10.0.0.1");
        assertThat(ClientIp.resolve(request("  "), 2)).isEqualTo("10.0.0.1");
    }
}
