package com.jarvis.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventPublisher;
import com.jarvis.global.event.EventAttribution;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 장바구니 행동 이벤트 서버 적재 (노션 E-1·C-2·C-4 2026-08-06) */
@ExtendWith(MockitoExtension.class)
class CartEventRecorderTest {

    @Mock BehaviorEventPublisher behaviorEventPublisher;
    @Mock RecommendationAttributionResolver attributionResolver;

    @Captor ArgumentCaptor<List<BehaviorEvent>> eventsCaptor;

    private CartEventRecorder recorder() {
        return new CartEventRecorder(behaviorEventPublisher, attributionResolver, new ObjectMapper());
    }

    private static CartEventRecorder.CartEvent event(String sessionKey, String listId) {
        return new CartEventRecorder.CartEvent(CartEventRecorder.ADD_EVENT_TYPE, 1L, null,
                sessionKey, 10L, 77L, 2, 14000, listId);
    }

    @Test
    @DisplayName("적재 — properties에 quantity·price·optionId가 실린다 (노션 E-1 properties 계약)")
    void writesContractProperties() {
        recorder().record(event("sess-1", null));

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getEventType()).isEqualTo("add_to_cart");
        assertThat(saved.getProductId()).isEqualTo(10L);
        assertThat(saved.getProperties())
                .contains("\"quantity\":2").contains("\"price\":14000").contains("\"optionId\":77");
        // 서버 적재 3종은 발생 시각 = 수신 시각이다 — 브라우저 시계가 개입하지 않아 이상치 보정이 없다
        assertThat(saved.getOccurredAt()).isEqualTo(saved.getCreatedAt());
    }

    // 분석 때문에 사용자의 담기를 실패시키지 않는다 — 컬럼이 NOT NULL이라 채울 수도 없다
    @Test
    @DisplayName("적재 — sessionKey가 없으면 이벤트만 건너뛴다 (담기·삭제는 이미 성공했다)")
    void skipsWithoutSessionKey() {
        recorder().record(event(null, null));
        recorder().record(event("  ", null));

        verifyNoInteractions(behaviorEventPublisher);
    }

    // 이벤트 귀속은 소유자를 검증하지 않는다(E-1 ③.5) — cart_item 저장용 3규칙과 분리된 경로다
    @Test
    @DisplayName("적재 — listId가 있으면 서버가 지면·순위를 도출해 붙인다")
    void attachesServerDerivedAttribution() {
        RecommendationAttributionResolver.Snapshot snapshot =
                RecommendationAttributionResolver.Snapshot.EMPTY;
        when(attributionResolver.snapshot(List.of("list-1"))).thenReturn(snapshot);
        when(attributionResolver.resolve(snapshot, "list-1", 10L))
                .thenReturn(new EventAttribution("req-1", "list-1", "CHAT", 2));

        recorder().record(event("sess-1", "list-1"));

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getListId()).isEqualTo("list-1");
        assertThat(saved.getSurface()).isEqualTo("CHAT");
        assertThat(saved.getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("적재 — 추천 경유가 아니면 목록을 조회하지 않는다")
    void skipsAttributionLookupWithoutListId() {
        recorder().record(event("sess-1", null));

        verify(behaviorEventPublisher).publish(anyList());
        verifyNoInteractions(attributionResolver);
    }

    // 흩어놓으면 한 곳만 빠뜨려도 세션 지표가 조용히 틀린다 (노션 I-2 한계 3가지)
    @Test
    @DisplayName("세션 판정 — chat: sentinel은 브라우저 세션이 아니다")
    void chatSentinelIsNotBrowserSession() {
        assertThat(CartEventRecorder.isBrowserSession("s_7f3a9b")).isTrue();
        assertThat(CartEventRecorder.isBrowserSession("chat:f4d1b6c2")).isFalse();
        assertThat(CartEventRecorder.isBrowserSession(null)).isFalse();
    }
}
