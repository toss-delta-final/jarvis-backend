package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.global.event.dto.EventBatchRequest.EventItem;
import com.jarvis.global.event.dto.EventBatchRequest.Recommendation;
import com.jarvis.member.GuestRepository;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock BehaviorEventPublisher behaviorEventPublisher;
    @Mock GuestRepository guestRepository;
    @Mock RecommendationAttributionResolver attributionResolver;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks EventService eventService;

    @Captor ArgumentCaptor<List<BehaviorEvent>> eventsCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(attributionResolver.snapshot(any()))
                .thenReturn(RecommendationAttributionResolver.Snapshot.EMPTY);
        lenient().when(attributionResolver.resolve(any(), any(), any()))
                .thenReturn(EventAttribution.NONE);
    }

    /** 정상 시각 = 수신 시각보다 살짝 과거 (행동이 먼저, 도착이 나중) */
    private static OffsetDateTime justNow() {
        return OffsetDateTime.now().minusSeconds(2);
    }

    private static EventItem item(String id, String eventType, Long productId,
                                  Map<String, Object> properties) {
        return new EventItem(id, "sess-1", eventType, productId, null, properties, justNow(), null);
    }

    @Test
    @DisplayName("E-1 — 회원 배치 적재: 필드 매핑 + occurred_at(FE 발생)·created_at(서버 수신) 모두 저장")
    void memberBatchHappyPath() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        OffsetDateTime clientTime = justNow();
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("uuid-1", "sess-1", "product_view", 10L, null,
                        Map.of("price", 1000), clientTime, null),
                item("uuid-2", "search", null, Map.of("query", "노트북", "resultsCount", 12))));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        List<BehaviorEvent> saved = eventsCaptor.getValue();
        assertThat(saved).hasSize(2);

        BehaviorEvent view = saved.get(0);
        assertThat(view.getMemberId()).isEqualTo(1L);
        assertThat(view.getGuestId()).isNull();
        assertThat(view.getSessionKey()).isEqualTo("sess-1");
        assertThat(view.getClientEventId()).isEqualTo("uuid-1");
        assertThat(view.getEventType()).isEqualTo("product_view");
        assertThat(view.getProductId()).isEqualTo(10L);
        // created_at은 서버 수신 시각, occurred_at은 FE가 보낸 시각 (02 D38)
        assertThat(view.getCreatedAt()).isBetween(before, LocalDateTime.now());
        assertThat(view.getOccurredAt()).isEqualTo(
                clientTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        assertThat(view.getProperties()).doesNotContain("_timeShifted");

        Map<String, Object> searchProps = objectMapper.readValue(
                saved.get(1).getProperties(), new TypeReference<>() {});
        assertThat(searchProps).containsEntry("query", "노트북").containsEntry("resultsCount", 12);
    }

    @Test
    @DisplayName("E-1 — 게스트 귀속: 쿠키 guest_id가 DB에 있으면 귀속, 없으면 무주체(null) 처리")
    void guestAttribution() {
        when(guestRepository.existsById("g-known")).thenReturn(true);
        eventService.collect(new EventBatchRequest(
                List.of(item("u-1", "page_view", null, Map.of("pageType", "home")))),
                null, "g-known", "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getGuestId()).isEqualTo("g-known");
        assertThat(eventsCaptor.getValue().get(0).getMemberId()).isNull();

        when(guestRepository.existsById("g-stale")).thenReturn(false);
        eventService.collect(new EventBatchRequest(
                List.of(item("u-2", "page_view", null, Map.of("pageType", "home")))),
                null, "g-stale", "1.2.3.4");

        verify(behaviorEventPublisher, times(2)).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue().get(0).getGuestId()).isNull();
    }

    @Test
    @DisplayName("E-1 — 화이트리스트 외 eventType은 폐기, 전부 폐기되면 appender 미호출")
    void unknownEventTypesDropped() {
        EventBatchRequest mixed = new EventBatchRequest(List.of(
                item("u-1", "wishlist_add", 10L, null),   // 12종 미포함 (M-5 주석)
                item("u-2", "login", null, null)));

        eventService.collect(mixed, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getEventType)
                .containsExactly("login");

        eventService.collect(new EventBatchRequest(
                List.of(item("u-3", "unknown_type", null, null))), 1L, null, "1.2.3.4");
        verify(behaviorEventPublisher, times(1)).publish(eventsCaptor.capture()); // 두 번째 호출 없음
    }

    // D38로 늘어난 추천 4종 — 이게 CTR의 분자·분모다
    @Test
    @DisplayName("E-1 — 추천 4종(impression·visible·click·dismiss)을 수용한다")
    void acceptsRecommendationEventTypes() {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("u-1", "recommendation_impression", null, null),
                new EventItem("u-2", "sess-1", "product_visible", 10L, null,
                        Map.of("visibleRatio", 0.75, "visibleMs", 1200), justNow(), null),
                item("u-3", "product_click", 10L, null),
                item("u-4", "recommendation_dismiss", null, null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        List<BehaviorEvent> saved = eventsCaptor.getValue();
        assertThat(saved).extracting(BehaviorEvent::getEventType)
                .containsExactly("recommendation_impression", "product_visible",
                        "product_click", "recommendation_dismiss");
        // 필수 키가 없는 타입은 표시할 게 없어 properties도 null이다 (기존 관례)
        assertThat(saved.get(0).getProperties()).isNull();
        assertThat(saved.get(2).getProperties()).isNull();
        assertThat(saved.get(3).getProperties()).isNull();
        // product_visible은 필수 2키(visibleRatio·visibleMs)를 채웠으니 _incomplete가 없다
        assertThat(saved.get(1).getProperties()).doesNotContain("_incomplete")
                .contains("visibleRatio").contains("visibleMs");
    }

    // id는 @NotBlank라 누락은 컨트롤러 검증에서 400으로 걸린다 — 서비스는 중복만 본다 (02 D35)
    @Test
    @DisplayName("E-1 — 배치 내 중복 client_event_id는 1건만 적재 (02 D35)")
    void inBatchDuplicatesDropped() {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("dup-1", "page_view", null, Map.of("pageType", "home")),
                item("dup-1", "page_view", null, Map.of("pageType", "home")),
                item("uniq-1", "page_view", null, Map.of("pageType", "home"))));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getClientEventId)
                .containsExactly("dup-1", "uniq-1");
    }

    @Test
    @DisplayName("E-1 — session_start에는 properties가 없어도 서버가 ipHash 주입 (04 §8 ③)")
    void sessionStartInjectsIpHash() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("u-1", "session_start", null, null),
                item("u-2", "session_start", null, Map.of("referrer", "home"))));

        eventService.collect(request, null, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
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

        verifyNoInteractions(behaviorEventPublisher);
    }

    // E-1은 인증이 없어 여기로 받으면 추천 발생 수(CTR 분모)를 마음대로 부풀릴 수 있다 (02 D38)
    @Test
    @DisplayName("E-1 — 서버 전용 recommendation_generated가 HTTP로 들어오면 그 건만 드롭")
    void serverOnlyEventTypeDropped() {
        EventBatchRequest mixed = new EventBatchRequest(List.of(
                item("u-1", "recommendation_generated", null, Map.of("itemCount", 9)),
                new EventItem("u-2", "sess-1", "product_view", 10L, null,
                        Map.of("price", 1000), justNow(), null)));

        eventService.collect(mixed, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getEventType)
                .containsExactly("product_view");
    }

    // 2026-08-06 이관 — 서버가 CartService에서 적재하므로 FE 전송분은 여기서 버린다.
    // 드롭을 서버 적재와 같은 배포에서 켜야 그 사이 중복(1건 담기 → 2행)이 안 생긴다.
    @Test
    @DisplayName("E-1 — 장바구니 2종도 서버 전용이라 HTTP로 들어오면 드롭 (FE track 제거 전이어도)")
    void cartEventTypesDropped() {
        EventBatchRequest mixed = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "add_to_cart", 10L, null,
                        Map.of("quantity", 1, "price", 1000), justNow(), null),
                new EventItem("u-2", "sess-1", "remove_from_cart", 10L, null,
                        Map.of("quantity", 1, "price", 1000), justNow(), null),
                new EventItem("u-3", "sess-1", "product_view", 10L, null,
                        Map.of("price", 1000), justNow(), null)));

        eventService.collect(mixed, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getEventType)
                .containsExactly("product_view");
    }

    // 2026-08-11 이관 — 결제 성사의 정본은 서버 트랜잭션이라 OrderService에서 적재한다.
    @Test
    @DisplayName("E-1 — purchase_complete도 서버 전용이라 HTTP로 들어오면 드롭 (2026-08-11 이관)")
    void purchaseCompleteDropped() {
        EventBatchRequest mixed = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "purchase_complete", 10L, null,
                        Map.of("orderId", 1001, "amount", 24000), justNow(), null),
                new EventItem("u-2", "sess-1", "product_view", 10L, null,
                        Map.of("price", 1000), justNow(), null)));

        eventService.collect(mixed, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        assertThat(eventsCaptor.getValue())
                .extracting(BehaviorEvent::getEventType)
                .containsExactly("product_view");
    }

    // 정상이면 occurred_at은 created_at보다 살짝 과거다 — 미래면 브라우저 시계를 믿지 않는다
    @Test
    @DisplayName("E-1 — occurredAt이 수신 시각보다 미래면 수신 시각으로 대체 + _timeShifted")
    void futureOccurredAtIsShifted() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "login", null, null, null,
                        OffsetDateTime.now().plusHours(2), null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getOccurredAt()).isEqualTo(saved.getCreatedAt());
        Map<String, Object> props = objectMapper.readValue(
                saved.getProperties(), new TypeReference<>() {});
        assertThat(props).containsEntry("_timeShifted", true);
    }

    @Test
    @DisplayName("E-1 — occurredAt이 3일 이상 과거면 수신 시각으로 대체 + _timeShifted")
    void staleOccurredAtIsShifted() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "login", null, null, null,
                        OffsetDateTime.now().minusDays(4), null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getOccurredAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getProperties()).contains("_timeShifted");
    }

    // 3일 안쪽 지연(오프라인 후 뒤늦은 전송)은 정상 — 보낸 값을 그대로 쓴다
    @Test
    @DisplayName("E-1 — 하루 전 발생 이벤트는 그대로 보존한다 (지연 도착은 정상)")
    void lateButValidOccurredAtKept() {
        OffsetDateTime yesterday = OffsetDateTime.now().minusDays(1);
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "login", null, null, null, yesterday, null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getOccurredAt()).isEqualTo(
                yesterday.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        assertThat(saved.getProperties()).isNull(); // 표시할 게 없으면 properties도 없다
    }

    @Test
    @DisplayName("E-1 — occurredAt이 없으면 수신 시각으로 채우고 _timeShifted를 남긴다")
    void missingOccurredAtIsShifted() {
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "login", null, null, null, null, null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getOccurredAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getProperties()).contains("_timeShifted");
    }

    // 빠진 행이 들어오면 에러 없이 숫자만 틀린다 — 버리지 않고 표시를 남긴다 (노션 E-1)
    @Test
    @DisplayName("E-1 — 필수 properties 키가 빠지면 저장하되 _incomplete를 붙인다")
    void missingRequiredPropertiesMarked() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                item("u-1", "checkout_start", null, Map.of("amount", 50000)), // productIds 누락
                item("u-2", "product_view", 10L, Map.of("price", 1000))));    // 완전

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        Map<String, Object> incomplete = objectMapper.readValue(
                eventsCaptor.getValue().get(0).getProperties(), new TypeReference<>() {});
        assertThat(incomplete).containsEntry("_incomplete", true).containsEntry("amount", 50000);
        assertThat(eventsCaptor.getValue().get(1).getProperties()).doesNotContain("_incomplete");
    }

    @Test
    @DisplayName("E-1 — schemaVersion은 전용 컬럼 없이 properties에 담긴다 (02 D38)")
    void schemaVersionStoredInProperties() throws Exception {
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "login", null, 2, null, justNow(), null)));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        Map<String, Object> props = objectMapper.readValue(
                eventsCaptor.getValue().get(0).getProperties(), new TypeReference<>() {});
        assertThat(props).containsEntry("schemaVersion", 2);
    }

    // FE가 보낸 문맥을 그대로 쓰지 않는다 — 서버가 도출한 값만 저장한다 (E-1 ③.5)
    @Test
    @DisplayName("E-1 — 추천 귀속은 서버가 도출한 값으로 채운다")
    void attributionComesFromServer() {
        when(attributionResolver.resolve(any(), anyString(), anyLong()))
                .thenReturn(new EventAttribution("req-1", "list-1", "CHAT", 3));
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "product_click", 10L, null, null, justNow(),
                        new Recommendation("FE가-보낸-값", "list-1"))));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getRecommendationRequestId()).isEqualTo("req-1"); // FE 값이 아니다
        assertThat(saved.getListId()).isEqualTo("list-1");
        assertThat(saved.getSurface()).isEqualTo("CHAT");
        assertThat(saved.getPosition()).isEqualTo(3);
    }

    @Test
    @DisplayName("E-1 — 귀속 실패(목록 없음·상품 불일치)면 문맥만 비우고 이벤트 본체는 저장")
    void attributionFailureKeepsEventBody() {
        EventBatchRequest request = new EventBatchRequest(List.of(
                new EventItem("u-1", "sess-1", "product_click", 10L, null, null, justNow(),
                        new Recommendation("req-x", "list-gone"))));

        eventService.collect(request, 1L, null, "1.2.3.4");

        verify(behaviorEventPublisher).publish(eventsCaptor.capture());
        BehaviorEvent saved = eventsCaptor.getValue().get(0);
        assertThat(saved.getEventType()).isEqualTo("product_click"); // 본체는 살아남는다
        assertThat(saved.getProductId()).isEqualTo(10L);
        assertThat(saved.getRecommendationRequestId()).isNull();
        assertThat(saved.getListId()).isNull();
        assertThat(saved.getPosition()).isNull();
    }
}
