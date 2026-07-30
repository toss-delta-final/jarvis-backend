package com.jarvis.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.ChatIdentity;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventAppender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** recommendation_generated 서버 적재 (02 D38·D39, 노션 E-1) */
@ExtendWith(MockitoExtension.class)
class RecommendationEventRecorderTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String REQUEST_ID = "a63be350-ec96-4f44-b3f9-c962b6673a68";

    @Mock BehaviorEventAppender behaviorEventAppender;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks RecommendationEventRecorder recorder;

    @Captor ArgumentCaptor<List<BehaviorEvent>> eventsCaptor;

    private static RecommendationList list(String listId, int itemCount, ChatIdentity identity) {
        return RecommendationList.ofChat(listId, REQUEST_ID, RecommendationListType.PICK_ONE,
                SESSION_ID, identity, null, null, itemCount, LocalDateTime.now());
    }

    @Test
    @DisplayName("적재 — 목록 1개당 1행, 목록 단위 이벤트라 productId는 null")
    void recordsOneRowPerList() throws Exception {
        recorder.recordGenerated(List.of(
                list("list-a", 9, ChatIdentity.member(7L)),
                list("list-b", 3, ChatIdentity.member(7L))));

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        List<BehaviorEvent> events = eventsCaptor.getValue();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(BehaviorEvent::getListId).containsExactly("list-a", "list-b");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("recommendation_generated");
            assertThat(e.getProductId()).isNull();   // 목록 단위 — 특정 상품이 아니다
            assertThat(e.getPosition()).isNull();    // 순위도 없다
            assertThat(e.getSurface()).isEqualTo("CHAT");
            assertThat(e.getRecommendationRequestId()).isEqualTo(REQUEST_ID);
            assertThat(e.getClientEventId()).isNull(); // FE가 보내지 않는다
        });

        Map<String, Object> props = objectMapper.readValue(
                events.get(0).getProperties(), new TypeReference<>() {});
        assertThat(props).containsExactlyEntriesOf(Map.of("itemCount", 9));
    }

    // 서버가 만드는 이벤트라 브라우저 시계가 개입하지 않는다 — 이상치 보정 대상이 아니다(노션 E-1)
    @Test
    @DisplayName("적재 — occurred_at은 적재 시점이며 created_at과 같다")
    void occurredAtEqualsCreatedAt() {
        recorder.recordGenerated(List.of(list("list-a", 1, ChatIdentity.member(7L))));

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        BehaviorEvent event = eventsCaptor.getValue().get(0);
        assertThat(event.getOccurredAt()).isNotNull().isEqualTo(event.getCreatedAt());
    }

    @Test
    @DisplayName("적재 — 신원 스냅샷을 그대로 옮긴다 (회원/게스트/익명)")
    void carriesIdentitySnapshot() {
        recorder.recordGenerated(List.of(
                list("list-a", 1, ChatIdentity.member(7L)),
                list("list-b", 1, ChatIdentity.guest("g-1")),
                list("list-c", 1, null)));   // 세션 만료 후 콜백 = 익명 저장

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        List<BehaviorEvent> events = eventsCaptor.getValue();
        assertThat(events.get(0).getMemberId()).isEqualTo(7L);
        assertThat(events.get(0).getGuestId()).isNull();
        assertThat(events.get(1).getMemberId()).isNull();
        assertThat(events.get(1).getGuestId()).isEqualTo("g-1");
        // 주체 없는 행 — 성과 집계에서 배제된다(노션 I-21 익명 저장 규약)
        assertThat(events.get(2).getMemberId()).isNull();
        assertThat(events.get(2).getGuestId()).isNull();
    }

    // session_key는 NOT NULL인데 이 이벤트엔 FE SDK 세션이 없다 — 채팅 세션 id로 채운다 (02 D39)
    @Test
    @DisplayName("적재 — session_key는 채팅 세션 id로 채운다")
    void sessionKeyFallsBackToChatSession() {
        recorder.recordGenerated(List.of(list("list-a", 1, ChatIdentity.member(7L))));

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getSessionKey()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("적재 — 대상이 없으면 appender를 호출하지 않는다 (재전송으로 전부 걸러진 경우)")
    void skipsWhenNothingFresh() {
        recorder.recordGenerated(List.of());

        verifyNoInteractions(behaviorEventAppender);
    }
}
