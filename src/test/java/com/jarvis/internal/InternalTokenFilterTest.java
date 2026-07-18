package com.jarvis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 03 D4 — /internal/**은 서비스 토큰 없이는 401, FE 경로(/api)는 이 필터를 타지 않는다 */
class InternalTokenFilterTest {

    private final InternalTokenFilter filter = new InternalTokenFilter("secret-token", new ObjectMapper());

    @Test
    @DisplayName("올바른 X-Internal-Token이면 통과")
    void validTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/products/search");
        request.setRequestURI("/internal/products/search");
        request.addHeader(InternalTokenFilter.HEADER, "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("토큰 없음/불일치 → 401 INTERNAL_TOKEN_INVALID envelope")
    void invalidTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/cart");
        request.setRequestURI("/internal/cart");
        request.addHeader(InternalTokenFilter.HEADER, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_TOKEN_INVALID");
    }

    @Test
    @DisplayName("토큰 미설정(빈 값)이면 전부 거부 — fail-closed")
    void blankConfiguredTokenRejectsAll() throws Exception {
        InternalTokenFilter unconfigured = new InternalTokenFilter("", new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/cart");
        request.setRequestURI("/internal/cart");
        request.addHeader(InternalTokenFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        unconfigured.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("/api 경로는 필터 대상 아님 (shouldNotFilter)")
    void apiPathSkipsFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/1");
        request.setRequestURI("/api/products/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
