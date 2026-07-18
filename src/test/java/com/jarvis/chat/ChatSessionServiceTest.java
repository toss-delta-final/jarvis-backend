package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.chat.dto.ChatSessionResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** CH-1/CH-1b (04 §6) — 세션 발급·소유권 검증·새 대화 정리(I-20) */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock StreamTicketProvider ticketProvider;
    @Mock LlmNotifyClient llmNotifyClient;
    @Mock ChatProperties chatProperties;
    @Mock LlmProperties llmProperties;

    @InjectMocks ChatSessionService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(chatProperties.sessionTtlMinutes()).thenReturn(10L);
        lenient().when(ticketProvider.createTicket(any())).thenReturn("ticket");
        lenient().when(ticketProvider.ttlSeconds()).thenReturn(60L);
        lenient().when(llmProperties.sseUrl()).thenReturn("http://localhost:8000");
    }

    @Test
    @DisplayName("CH-1 — 세션+티켓 동시 발급, 세션·owner 키 TTL 저장")
    void issueSession() {
        when(valueOperations.get("chat:owner:member:1")).thenReturn(null);

        ChatSessionResponse response = service.issueSession(ChatIdentity.member(1L), ChatChannel.SHOPPING);

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.streamTicket()).isEqualTo("ticket");
        assertThat(response.ttlSeconds()).isEqualTo(600L);
        assertThat(response.ticketTtlSeconds()).isEqualTo(60L);
        verify(valueOperations).set(eq("chat:session:" + response.sessionId()),
                eq("member|1|SHOPPING"), eq(Duration.ofMinutes(10)));
        verify(valueOperations).set(eq("chat:owner:member:1"),
                eq(response.sessionId()), eq(Duration.ofMinutes(10)));
        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), any());
    }

    @Test
    @DisplayName("CH-1 — 같은 신원의 기존 세션은 새 대화로 정리 + I-20 NEW_CONVERSATION (05 §1-1)")
    void issueSessionEndsPrevious() {
        when(valueOperations.get("chat:owner:member:1")).thenReturn("old-session");

        service.issueSession(ChatIdentity.member(1L), ChatChannel.SHOPPING);

        verify(redisTemplate).delete("chat:session:old-session");
        verify(llmNotifyClient).notifySessionEnd("old-session", SessionEndReason.NEW_CONVERSATION);
    }

    @Test
    @DisplayName("CH-1b — 세션 유지, TTL sliding 연장 후 티켓 재발급")
    void reissueTicket() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|CS");

        ChatSessionResponse response = service.reissueTicket(ChatIdentity.guest("g-uuid"), "s1");

        assertThat(response.sessionId()).isEqualTo("s1");
        verify(redisTemplate).expire(eq("chat:session:s1"), eq(Duration.ofMinutes(10)));
        verify(redisTemplate).expire(eq("chat:owner:guest:g-uuid"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("CH-1b — 세션 만료·없음이면 404 SESSION_NOT_FOUND (04 §6)")
    void reissueTicketSessionNotFound() {
        when(valueOperations.get(startsWith("chat:session:"))).thenReturn(null);

        assertThatThrownBy(() -> service.reissueTicket(ChatIdentity.member(1L), "gone"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("CH-1b — 발급 신원과 다르면 403 SESSION_FORBIDDEN (sessionId만으론 남의 티켓 불가)")
    void reissueTicketForbidden() {
        when(valueOperations.get("chat:session:s1")).thenReturn("member|1|SHOPPING");

        assertThatThrownBy(() -> service.reissueTicket(ChatIdentity.member(2L), "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_FORBIDDEN);
    }

    @Test
    @DisplayName("CH-1b — 신원 자체가 없으면 403 (게스트 쿠키도 없는 요청)")
    void reissueTicketNoIdentity() {
        assertThatThrownBy(() -> service.reissueTicket(null, "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_FORBIDDEN);
    }

    @Test
    @DisplayName("로그아웃 — 활성 세션 삭제 + I-20 LOGOUT 통지 (05 §2-1)")
    void endSession() {
        when(valueOperations.get("chat:owner:member:1")).thenReturn("s1");

        service.endSession(ChatIdentity.member(1L), SessionEndReason.LOGOUT);

        verify(redisTemplate).delete("chat:session:s1");
        verify(redisTemplate).delete("chat:owner:member:1");
        verify(llmNotifyClient).notifySessionEnd("s1", SessionEndReason.LOGOUT);
    }

    @Test
    @DisplayName("로그아웃 — 활성 세션 없으면 통지 없음(멱등)")
    void endSessionNoActive() {
        when(valueOperations.get("chat:owner:member:1")).thenReturn(null);

        service.endSession(ChatIdentity.member(1L), SessionEndReason.LOGOUT);

        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), any());
    }
}
