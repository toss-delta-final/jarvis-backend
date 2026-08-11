package com.jarvis.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jarvis.chat.dto.ChatSessionResponse;
import com.jarvis.global.auth.EnvelopeAccessDeniedHandler;
import com.jarvis.global.auth.EnvelopeAuthenticationEntryPoint;
import com.jarvis.global.auth.AccessCookieManager;
import com.jarvis.global.auth.TokenEpoch;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.auth.JwtAuthenticationFilter;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.config.SecurityConfig;
import com.jarvis.member.GuestService;
import com.jarvis.seller.SellerBrandResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CH-1 요청 검증 계약 (04 §6 · 노션 CH-1 실패 응답표 2026-07-28) —
 * 잘못된 channel은 어떤 형태든 400 VALIDATION_ERROR envelope 하나로 수렴한다.
 */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtProvider.class,
        EnvelopeAuthenticationEntryPoint.class, EnvelopeAccessDeniedHandler.class,
        GuestCookieManager.class, AccessCookieManager.class})
@TestPropertySource(properties = {
        "jwt.secret=test-hs256-secret-key-at-least-256-bits-long-0123456789",
        "jwt.access-token-minutes=30",
        "jwt.refresh-token-days=14"
})
class ChatControllerTest {

    @Autowired MockMvc mockMvc;
    /** 무효화 마커는 Redis라 목으로 둔다 — 기본값 false(무효화 없음)로 통과 */
    @MockitoBean TokenEpoch tokenEpoch;

    @MockitoBean ChatSessionService chatSessionService;
    @MockitoBean RecommendationListService recommendationListService;
    @MockitoBean GuestService guestService;
    @MockitoBean SellerBrandResolver sellerBrandResolver;

    private void stubIssue() {
        when(guestService.ensureGuest(any())).thenReturn("guest-1");
        when(chatSessionService.issueSession(any(), any())).thenReturn(new ChatSessionResponse(
                "550e8400-e29b-41d4-a716-446655440000", 600, "ticket", 60,
                "https://ai.jarvis.example/chat"));
    }

    @Test
    @DisplayName("CH-1 body 없음 — SHOPPING 기본값으로 200")
    void createSession_noBody_defaultsToShopping() throws Exception {
        stubIssue();

        mockMvc.perform(post("/api/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ttlSeconds").value(600))
                .andExpect(jsonPath("$.data.llmSseUrl").value("https://ai.jarvis.example/chat"));

        verify(chatSessionService).issueSession(any(), eq(ChatChannel.SHOPPING));
    }

    // CS 채널은 2026-08-11 제거 — 문의 챗봇(CH-3) 폐기로 용도가 사라졌다(노션 CH-1 개정)
    @Test
    @DisplayName("CH-1 channel=CS — 400 VALIDATION_ERROR (폐기된 채널)")
    void createSession_cs_400() throws Exception {
        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"CS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(chatSessionService, never()).issueSession(any(), any());
    }

    @Test
    @DisplayName("CH-1 정의에 없는 channel — 400 VALIDATION_ERROR envelope")
    void createSession_unknownChannel_400() throws Exception {
        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SHOPPINGG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(chatSessionService, never()).issueSession(any(), any());
    }

    // SELLER는 enum엔 있지만 입구가 S-4뿐 — CH-1로는 발급 불가(05 §1-3)
    @Test
    @DisplayName("CH-1 channel=SELLER — 400 VALIDATION_ERROR (입구는 S-4뿐)")
    void createSession_sellerChannel_400() throws Exception {
        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SELLER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(chatSessionService, never()).issueSession(any(), any());
    }

    @Test
    @DisplayName("CH-1 JSON 문법 오류 — 400 VALIDATION_ERROR (500 아님)")
    void createSession_malformedJson_400() throws Exception {
        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
