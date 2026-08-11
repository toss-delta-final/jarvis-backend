package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
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

/** CH-1/CH-1b (04 §6) — 멱등 발급(D5)·채널별 공존·소유권 검증·로그아웃 정리(I-20) */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock StreamTicketProvider ticketProvider;
    @Mock LlmNotifyClient llmNotifyClient;
    @Mock ChatProperties chatProperties;
    @Mock LlmProperties llmProperties;
    @Mock com.jarvis.member.GuestService guestService;

    @InjectMocks ChatSessionService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(chatProperties.sessionTtlMinutes()).thenReturn(10L);
        lenient().when(ticketProvider.createTicket(any(), anyString())).thenReturn("ticket");
        lenient().when(ticketProvider.ttlSeconds()).thenReturn(60L);
        lenient().when(llmProperties.sseUrl()).thenReturn("http://localhost:8000");
    }

    @Test
    @DisplayName("CH-1 — 활성 세션이 없으면 발급, owner 키는 SETNX로 잡는다")
    void issueSession() {
        when(valueOperations.get("chat:owner:member:1:SHOPPING")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("chat:owner:member:1:SHOPPING"), anyString(), any(Duration.class)))
                .thenReturn(true);

        ChatSessionResponse response = service.issueSession(ChatIdentity.member(1L), ChatChannel.SHOPPING);

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.streamTicket()).isEqualTo("ticket");
        assertThat(response.ttlSeconds()).isEqualTo(600L);
        assertThat(response.ticketTtlSeconds()).isEqualTo(60L);
        verify(valueOperations).set(eq("chat:session:" + response.sessionId()),
                eq("member|1|SHOPPING"), eq(Duration.ofMinutes(10)));
        // owner는 세션+1분 — expire 쌍이 원자적이지 않아 owner가 먼저 만료되는 방향을 봉쇄 (07 §3-2)
        verify(valueOperations).setIfAbsent(eq("chat:owner:member:1:SHOPPING"),
                eq(response.sessionId()), eq(Duration.ofMinutes(11)));
        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("CH-1 D5 — 활성 세션이 있으면 축출하지 않고 그대로 반환 + TTL sliding (노션 CH-1 07-31)")
    void issueSessionReusesActive() {
        when(valueOperations.get("chat:owner:member:1:SHOPPING")).thenReturn("live-session");
        when(valueOperations.get("chat:session:live-session")).thenReturn("member|1|SHOPPING");

        ChatSessionResponse response = service.issueSession(ChatIdentity.member(1L), ChatChannel.SHOPPING);

        assertThat(response.sessionId()).isEqualTo("live-session");
        verify(redisTemplate).expire("chat:session:live-session", Duration.ofMinutes(10));
        verify(redisTemplate).expire("chat:owner:member:1:SHOPPING", Duration.ofMinutes(11));
        verify(redisTemplate, never()).delete(anyString());
        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("CH-1 — owner 키만 남고 세션이 만료된 불일치는 새로 발급한다")
    void issueSessionRecoversFromDanglingOwnerKey() {
        when(valueOperations.get("chat:owner:member:1:SHOPPING")).thenReturn("dead-session");
        when(valueOperations.get("chat:session:dead-session")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("chat:owner:member:1:SHOPPING"), anyString(), any(Duration.class)))
                .thenReturn(false);

        ChatSessionResponse response = service.issueSession(ChatIdentity.member(1L), ChatChannel.SHOPPING);

        assertThat(response.sessionId()).isNotEqualTo("dead-session");
        verify(valueOperations).set(eq("chat:owner:member:1:SHOPPING"),
                eq(response.sessionId()), eq(Duration.ofMinutes(11)));
    }

    // CS 채널 제거(2026-08-11) 후에도 "채널이 다르면 서로를 밀어내지 않는다"는 보증은 유지되어야 한다
    @Test
    @DisplayName("CH-1/CH-6 — 채널이 다르면 별개 세션(SHOPPING 세션이 살아 있어도 SELLER는 새로 발급)")
    void issueSessionPerChannel() {
        when(valueOperations.get("chat:owner:member:1:SELLER")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("chat:owner:member:1:SELLER"), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(ticketProvider.createSellerTicket(any(), anyString(), eq(3L))).thenReturn("seller-ticket");

        ChatSessionResponse response = service.issueSellerSession(ChatIdentity.member(1L), 3L);

        verify(valueOperations).set(eq("chat:session:" + response.sessionId()),
                eq("member|1|SELLER|3"), eq(Duration.ofMinutes(10)));
        verify(valueOperations, never()).get("chat:owner:member:1:SHOPPING");
    }

    @Test
    @DisplayName("S-4 — SELLER 세션 발급: brandId는 세션 값에 보관, SELLER 티켓 + /seller/chat 주소")
    void issueSellerSession() {
        when(valueOperations.get("chat:owner:member:7:SELLER")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("chat:owner:member:7:SELLER"), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(ticketProvider.createSellerTicket(any(), anyString(), eq(3L))).thenReturn("seller-ticket");

        ChatSessionResponse response = service.issueSellerSession(ChatIdentity.member(7L), 3L);

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.streamTicket()).isEqualTo("seller-ticket");
        assertThat(response.ttlSeconds()).isEqualTo(600L);
        assertThat(response.ticketTtlSeconds()).isEqualTo(60L);
        assertThat(response.llmSseUrl()).isEqualTo("http://localhost:8000/seller/chat");
        verify(valueOperations).set(eq("chat:session:" + response.sessionId()),
                eq("member|7|SELLER|3"), eq(Duration.ofMinutes(10)));
        verify(valueOperations).setIfAbsent(eq("chat:owner:member:7:SELLER"),
                eq(response.sessionId()), eq(Duration.ofMinutes(11)));
        verify(ticketProvider).createSellerTicket(eq(ChatIdentity.member(7L)), anyString(), eq(3L));
        verify(ticketProvider, never()).createTicket(any(), anyString());
    }

    @Test
    @DisplayName("S-4 D5 — 같은 판매자의 활성 세션도 그대로 반환하며 brandId(SELLER 티켓)를 유지한다")
    void issueSellerSessionReusesActive() {
        when(valueOperations.get("chat:owner:member:7:SELLER")).thenReturn("live-seller-session");
        when(valueOperations.get("chat:session:live-seller-session")).thenReturn("member|7|SELLER|3");
        when(ticketProvider.createSellerTicket(any(), anyString(), eq(3L))).thenReturn("seller-ticket");

        ChatSessionResponse response = service.issueSellerSession(ChatIdentity.member(7L), 3L);

        assertThat(response.sessionId()).isEqualTo("live-seller-session");
        assertThat(response.streamTicket()).isEqualTo("seller-ticket");
        verify(redisTemplate, never()).delete(anyString());
        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("S-4/CH-1b — SELLER 세션 티켓 재발급도 세션 값의 brandId로 SELLER 티켓 유지")
    void reissueTicketKeepsSellerScope() {
        when(valueOperations.get("chat:session:s-seller")).thenReturn("member|7|SELLER|3");
        when(ticketProvider.createSellerTicket(any(), anyString(), eq(3L))).thenReturn("seller-ticket-2");

        ChatSessionResponse response = service.reissueTicket(ChatIdentity.member(7L), "s-seller");

        assertThat(response.streamTicket()).isEqualTo("seller-ticket-2");
        assertThat(response.llmSseUrl()).isEqualTo("http://localhost:8000/seller/chat");
        verify(ticketProvider).createSellerTicket(eq(ChatIdentity.member(7L)), anyString(), eq(3L));
        verify(ticketProvider, never()).createTicket(any(), anyString());
        verify(redisTemplate).expire(eq("chat:session:s-seller"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("CH-1b — 세션 유지, TTL sliding 연장 후 티켓 재발급")
    void reissueTicket() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|SHOPPING");

        ChatSessionResponse response = service.reissueTicket(ChatIdentity.guest("g-uuid"), "s1");

        assertThat(response.sessionId()).isEqualTo("s1");
        verify(redisTemplate).expire(eq("chat:session:s1"), eq(Duration.ofMinutes(10)));
        verify(redisTemplate).expire(eq("chat:owner:guest:g-uuid:SHOPPING"), eq(Duration.ofMinutes(11)));
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
    @DisplayName("로그아웃 — 채널별 활성 세션을 모두 삭제 + 각각 I-20 LOGOUT 통지 (05 §2-1)")
    void endSession() {
        when(valueOperations.get("chat:owner:member:1:SHOPPING")).thenReturn("s1");
        when(valueOperations.get("chat:owner:member:1:SELLER")).thenReturn("s2");

        service.endSession(ChatIdentity.member(1L), SessionEndReason.LOGOUT);

        verify(redisTemplate).delete("chat:session:s1");
        verify(redisTemplate).delete("chat:owner:member:1:SHOPPING");
        verify(redisTemplate).delete("chat:session:s2");
        verify(redisTemplate).delete("chat:owner:member:1:SELLER");
        verify(llmNotifyClient).notifySessionEnd("s1", 1L, SessionEndReason.LOGOUT);
        verify(llmNotifyClient).notifySessionEnd("s2", 1L, SessionEndReason.LOGOUT);
    }

    @Test
    @DisplayName("로그아웃 — 활성 세션 없으면 통지 없음(멱등)")
    void endSessionNoActive() {
        service.endSession(ChatIdentity.member(1L), SessionEndReason.LOGOUT);

        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("게스트 세션 종료 — Redis는 정리하되 I-20은 생략(게스트는 프로필 대상 아님, 노션 I-20 정본)")
    void endSessionGuestSkipsNotify() {
        when(valueOperations.get("chat:owner:guest:g-uuid:SHOPPING")).thenReturn("gs1");
        when(valueOperations.get("chat:owner:guest:g-uuid:SELLER")).thenReturn(null);

        service.endSession(ChatIdentity.guest("g-uuid"), SessionEndReason.LOGOUT);

        verify(redisTemplate).delete("chat:session:gs1");
        verify(redisTemplate).delete("chat:owner:guest:g-uuid:SHOPPING");
        verify(llmNotifyClient, never()).notifySessionEnd(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("CH-7 — 귀속 기록으로 소유권을 확인하고 owner를 회원으로 옮긴다 (이슈 #63)")
    void claimSession() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|SHOPPING");
        when(guestService.isOwnedBy("g-uuid", 7L)).thenReturn(true);
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn(null);

        ChatSessionResponse response = service.claimSession(7L, "s1");

        assertThat(response.sessionId()).isEqualTo("s1");
        verify(llmNotifyClient).notifySessionClaim("s1", "g-uuid", 7L);
        verify(valueOperations).set("chat:session:s1", "member|7|SHOPPING", Duration.ofMinutes(10));
        verify(redisTemplate).delete("chat:owner:guest:g-uuid:SHOPPING");
        verify(valueOperations).set("chat:owner:member:7:SHOPPING", "s1", Duration.ofMinutes(11));
    }

    @Test
    @DisplayName("CH-7 — 내가 은퇴시킨 게스트가 아니면 403 (sessionId만으로 남의 세션을 주울 수 없다)")
    void claimSessionRejectsForeignGuest() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|SHOPPING");
        when(guestService.isOwnedBy("g-uuid", 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.claimSession(7L, "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_FORBIDDEN);
        verify(llmNotifyClient, never()).notifySessionClaim(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("CH-7 — 회원이 이미 그 채널에서 대화 중이면 409, 병합하지 않는다")
    void claimSessionRejectsWhenMemberAlreadyHasSession() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|SHOPPING");
        when(guestService.isOwnedBy("g-uuid", 7L)).thenReturn(true);
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn("existing");

        assertThatThrownBy(() -> service.claimSession(7L, "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_CLAIM_CONFLICT);
        verify(llmNotifyClient, never()).notifySessionClaim(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("CH-7 — 이미 내 소유면 성공 반환 (멱등 — 재시도·더블클릭 안전)")
    void claimSessionIsIdempotent() {
        when(valueOperations.get("chat:session:s1")).thenReturn("member|7|SHOPPING");
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn("s1");

        ChatSessionResponse response = service.claimSession(7L, "s1");

        assertThat(response.sessionId()).isEqualTo("s1");
        // 두 번째 호출은 AI를 다시 부르지 않는다 — 이미 전이된 세션이다
        verify(llmNotifyClient, never()).notifySessionClaim(anyString(), anyString(), anyLong());
        verify(guestService, never()).isOwnedBy(anyString(), anyLong());
    }

    @Test
    @DisplayName("CH-7 — 통지 후 owner 이전이 끊긴 부분 실패를 재시도가 복구한다")
    void claimSessionRecoversHalfDoneOwnerMove() {
        // 세션 값만 회원으로 바뀌고 owner 이전 직전에 끊긴 상태
        when(valueOperations.get("chat:session:s1")).thenReturn("member|7|SHOPPING");
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn(null);

        service.claimSession(7L, "s1");

        verify(valueOperations).set("chat:owner:member:7:SHOPPING", "s1", Duration.ofMinutes(11));
    }

    @Test
    @DisplayName("CH-7 — 같은 채널에 다른 세션이 이미 회원 소유면 409 (승계가 아니라 충돌)")
    void claimSessionRejectsWhenAnotherSessionOwned() {
        when(valueOperations.get("chat:session:s1")).thenReturn("member|7|SHOPPING");
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn("other");

        assertThatThrownBy(() -> service.claimSession(7L, "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_CLAIM_CONFLICT);
    }

    @Test
    @DisplayName("CH-7 — AI가 거부하면 Redis owner를 옮기지 않는다 (부분 성공 고착 방지)")
    void claimSessionKeepsRedisWhenAiRejects() {
        when(valueOperations.get("chat:session:s1")).thenReturn("guest|g-uuid|SHOPPING");
        when(guestService.isOwnedBy("g-uuid", 7L)).thenReturn(true);
        when(valueOperations.get("chat:owner:member:7:SHOPPING")).thenReturn(null);
        doThrow(new BusinessException(ErrorCode.SESSION_ACTIVE))
                .when(llmNotifyClient).notifySessionClaim("s1", "g-uuid", 7L);

        assertThatThrownBy(() -> service.claimSession(7L, "s1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_ACTIVE);
        verify(redisTemplate, never()).delete(anyString());
        verify(valueOperations, never()).set(eq("chat:owner:member:7:SHOPPING"), anyString(), any());
    }

    @Test
    @DisplayName("CH-7 — 세션이 만료됐으면 404 (FE는 CH-1로 새 세션)")
    void claimSessionNotFound() {
        when(valueOperations.get("chat:session:gone")).thenReturn(null);

        assertThatThrownBy(() -> service.claimSession(7L, "gone"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }
}
