package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.global.event.dto.EventBatchRequest.EventItem;
import com.jarvis.member.GuestRepository;
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

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock BehaviorEventAppender behaviorEventAppender;
    @Mock GuestRepository guestRepository;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks EventService eventService;

    @Captor ArgumentCaptor<List<BehaviorEvent>> eventsCaptor;

    private static EventItem item(String id, String eventType, Long productId,
                                  Map<String, Object> properties) {
        return new EventItem(id, "sess-1", eventType, productId, properties, "2026-07-23T10:00:00");
    }

    @Test
    @DisplayName("E-1 — 회원 배치 적재: 필드 매핑 + created_at=서버 수신 시각(occurredAt 미사용)")
    void memberBatchHappyPath() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("uuid-1", "product_view", 10L, null),
                item("uuid-2", "search", null, Map.of("keyword", "노트북"))));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        List<BehaviorEvent> saved = eventsCaptor.getValue();
        assertThat(saved).hasSize(2);

        BehaviorEvent view = saved.get(0);
        assertThat(view.getMemberId()).isEqualTo(1L);
        assertThat(view.getGuestId()).isNull();
        assertThat(view.getSessionKey()).isEqualTo("sess-1");
        assertThat(view.getClientEventId()).isEqualTo("uuid-1");
        assertThat(view.getEventType()).isEqualTo("product_view");
        assertThat(view.getProductId()).isEqualTo(10L);
        assertThat(view.getProperties()).isNull(); // properties 없으면 null 적재
        // created_at은 FE occurredAt이 아니라 서버 수신 시각 (04 §8 ④)
        assertThat(view.getCreatedAt()).isBetween(before, LocalDateTime.now());

        Map<String, Object> searchProps = objectMapper.readValue(
                saved.get(1).getProperties(), new TypeReference<>() {});
        assertThat(searchProps).containsExactlyEntriesOf(Map.of("keyword", "노트북"));
    }

    @Test
    @DisplayName("E-1 — 게스트 귀속: 쿠키 guest_id가 DB에 있으면 귀속, 없으면 무주체(null) 처리")
    void guestAttribution() {
        when(guestRepository.existsById("g-known")).thenReturn(true);
        eventService.collect(new EventBatchRequest(
                List.of(item("u-1", "page_view", null, null))), null, "g-known", "1.2.3.4");

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getGuestId()).isEqualTo("g-known");
        assertThat(eventsCaptor.getValue().get(0).getMemberId()).isNull();

        when(guestRepository.existsById("g-stale")).thenReturn(false);
        eventService.collect(new EventBatchRequest(
                List.of(item("u-2", "page_view", null, null))), null, "g-stale", "1.2.3.4");

        verify(behaviorEventAppender, times(2)).append(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getGuestId()).isNull();
    }

    @Test
    @DisplayName("E-1 — 8종 화이트리스트 외 eventType은 폐기, 전부 폐기되면 appender 미호출")
    void unknownEventTypesDropped() {
        EventBatchRequest mixed = new EventBatchRequest(List.of(
                item("u-1", "wishlist_add", 10L, null),   // 8종 미포함 (M-5 주석)
                item("u-2", "login", null, null)));

        eventService.collect(mixed, 1L, null, "1.2.3.4");

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getEventType)
                .containsExactly("login");

        eventService.collect(new EventBatchRequest(
                List.of(item("u-3", "unknown_type", null, null))), 1L, null, "1.2.3.4");
        verify(behaviorEventAppender, times(1)).append(eventsCaptor.capture()); // 두 번째 호출 없음
    }

    @Test
    @DisplayName("E-1 — 배치 내 중복 client_event_id는 1건만 적재, id=null은 중복 검사 제외 (02 D35)")
    void inBatchDuplicatesDropped() {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("dup-1", "page_view", null, null),
                item("dup-1", "page_view", null, null),
                item(null, "page_view", null, null),
                item(null, "page_view", null, null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        // dup-1은 1건, id=null 2건은 모두 유지 → 총 3건
        assertThat(eventsCaptor.getValue()).hasSize(3);
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getClientEventId)
                .containsExactly("dup-1", null, null);
    }

    @Test
    @DisplayName("E-1 — session_start에는 properties가 없어도 서버가 ipHash 주입 (04 §8 ③)")
    void sessionStartInjectsIpHash() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("u-1", "session_start", null, null),
                item("u-2", "session_start", null, Map.of("referrer", "home"))));

        eventService.collect(request, null, null, "1.2.3.4");

        verify(behaviorEventAppender).append(eventsCaptor.capture());
        String expectedHash = TokenHasher.sha256Hex("1.2.3.4");

        Map<String, Object> bare = objectMapper.readValue(
                eventsCaptor.getValue().get(0).getProperties(), new TypeReference<>() {});
        assertThat(bare).containsExactlyEntriesOf(Map.of("ipHash", expectedHash));

        Map<String, Object> merged = objectMapper.readValue(
                eventsCaptor.getValue().get(1).getProperties(), new TypeReference<>() {});
        assertThat(merged).containsEntry("ipHash", expectedHash)
                .containsEntry("referrer", "home");
    }

    @Test
    @DisplayName("E-1 — 유효 이벤트가 0건이면 appender를 호출하지 않는다")
    void noValidEventsSkipsAppend() {
        eventService.collect(new EventBatchRequest(
                List.of(item("u-1", "not_in_whitelist", null, null))), 1L, null, "1.2.3.4");

        verifyNoInteractions(behaviorEventAppender);
    }
}
