package com.jarvis.global.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.cart.CartEventRecorder;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.member.GuestRepository;
import com.jarvis.order.OrderEventRecorder;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import com.jarvis.recommendation.RecommendationEventRecorder;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * E-1 서버 처리 (04 §8, 노션 E-1): ① 신원은 JWT·쿠키에서 주입(body 무시) ② 화이트리스트 외 폐기
 * ③ session_start에 ipHash 주입 ③.5 추천 귀속을 서버가 도출 ④ occurred_at(FE 발생 시각)과
 * created_at(서버 수신 시각)을 모두 적재.
 * <p>
 * 설계 원칙: **개별 이벤트가 이상해도 배치를 실패시키지 않는다** — 문제 있는 건은 그 건만
 * 조용히 버리거나 표시를 남기고 202를 낸다. 분석용 수집이 사용자 플로우를 막으면 안 된다.
 * 게스트 존재 확인은 도메인 규칙이 아닌 FK 정합용 조회라 GuestRepository 직접 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    /**
     * FE가 보내는 10종 (노션 E-1 — 2026-08-06 add_to_cart 이관으로 12종→11종,
     * 2026-08-11 purchase_complete 이관으로 11종→10종)
     */
    private static final Set<String> EVENT_TYPES = Set.of(
            "session_start", "page_view", "search", "product_view",
            "checkout_start", "login",
            "recommendation_impression", "product_visible", "product_click",
            "recommendation_dismiss");
    /**
     * 서버만 적재하는 3종 (02 D38, 노션 E-1 2026-08-06) — HTTP로 들어오면 화이트리스트와 무관하게
     * 드롭한다. E-1은 인증이 없어 여기서 받으면 추천 발생 수·담기 수를 마음대로 부풀릴 수 있고,
     * server-side로 옮긴 의미가 사라진다.
     *
     * <p><b>장바구니 2종은 서버 적재와 같은 배포에서 드롭을 켠다</b>(2026-08-07 개정). 노션 구 문구는
     * "FE track 제거 배포 이후"였는데, 그러면 그 사이 기간에 FE 전송과 서버 적재가 겹쳐 한 번 담았는데
     * 2행이 쌓인다(양쪽이 각자 만든 {@code client_event_id}가 달라 UNIQUE로 못 막는다). 경계할 실패는
     * <b>적재 없이 드롭만 켜는 것</b>이므로 기준을 "서버 적재가 있는 상태에서만 켠다"로 바꿨다.
     */
    private static final Set<String> SERVER_ONLY_EVENT_TYPES = Set.of(
            RecommendationEventRecorder.EVENT_TYPE,
            CartEventRecorder.ADD_EVENT_TYPE,
            CartEventRecorder.REMOVE_EVENT_TYPE,
            // purchase_complete도 2026-08-11 서버 이관(노션 E-1) — 결제 성사의 정본은 서버 트랜잭션이고,
            // FE 발사는 유실·미발사로 coverage가 0이었다. cart 2종과 같은 규칙으로 드롭을 동시 활성화한다
            OrderEventRecorder.EVENT_TYPE);
    private static final String SESSION_START = "session_start";

    /**
     * 타입별 필수 properties 키 (노션 E-1 「properties 계약」). 빠져도 버리지 않고 저장하되
     * `_incomplete`를 붙인다 — checkout_start.productIds는 판매자 퍼널 3단의 근거이고
     * product_visible.visibleRatio는 CTR의 분모라, 빠진 행이 들어오면 에러 없이 숫자만 틀린다.
     */
    private static final Map<String, Set<String>> REQUIRED_PROPERTIES = Map.of(
            "page_view", Set.of("pageType"),
            "search", Set.of("query", "resultsCount"),
            "product_view", Set.of("price"),
            // add_to_cart·remove_from_cart·purchase_complete는 서버 전용이 되어 이 경로로 못 들어온다 —
            // 필수 키는 CartEventRecorder·OrderEventRecorder가 항상 채운다 (노션 E-1 2026-08-06·08-11)
            "checkout_start", Set.of("amount", "productIds"),
            "product_visible", Set.of("visibleRatio", "visibleMs"));

    /** 이 이상 과거면 브라우저 시계를 믿지 않는다 (노션 E-1 이상치 처리) */
    private static final Duration MAX_CLOCK_LAG = Duration.ofDays(3);

    private final BehaviorEventPublisher behaviorEventPublisher;
    private final GuestRepository guestRepository;
    private final RecommendationAttributionResolver attributionResolver;
    private final ObjectMapper objectMapper;

    public void collect(EventBatchRequest request, Long memberId, String guestId, String clientIp) {
        String validGuestId = resolveGuestId(guestId);
        LocalDateTime receivedAt = LocalDateTime.now();
        List<EventBatchRequest.EventItem> accepted = accept(request);
        if (accepted.isEmpty()) {
            return;
        }
        // 배치의 listId를 한 번에 조회해 이벤트당 쿼리를 피한다 (③.5)
        RecommendationAttributionResolver.Snapshot snapshot =
                attributionResolver.snapshot(accepted.stream()
                        .map(EventBatchRequest.EventItem::recommendation)
                        .filter(r -> r != null && r.listId() != null)
                        .map(EventBatchRequest.Recommendation::listId)
                        .toList());

        List<BehaviorEvent> events = new ArrayList<>();
        for (EventBatchRequest.EventItem item : accepted) {
            Map<String, Object> properties = properties(item, clientIp);
            LocalDateTime occurredAt = resolveOccurredAt(item, receivedAt, properties);
            EventAttribution attribution = attributionResolver.resolve(snapshot,
                    item.recommendation() == null ? null : item.recommendation().listId(),
                    item.productId());
            events.add(BehaviorEvent.record(memberId, validGuestId, item.sessionKey(), item.id(),
                    item.eventType(), item.productId(), serialize(properties), occurredAt,
                    attribution, receivedAt));
        }
        // 토픽 우선, 실패하면 DB 직접 적재 — 둘 다 실패하면 예외가 올라가 500이 된다(08 D7).
        // 노션 E-1의 "저장 실패 시 500"이 그때 발동한다: 202는 어딘가에 영속됐다는 뜻이다
        behaviorEventPublisher.publish(events);
    }

    /** ②·중복 — 타입 화이트리스트와 배치 내 중복만 걸러낸다. 나머지 이상은 표시를 남기고 통과 */
    private List<EventBatchRequest.EventItem> accept(EventBatchRequest request) {
        Set<String> seenIds = new HashSet<>();
        List<EventBatchRequest.EventItem> accepted = new ArrayList<>();
        for (EventBatchRequest.EventItem item : request.events()) {
            if (SERVER_ONLY_EVENT_TYPES.contains(item.eventType())) {
                log.warn("서버 전용 eventType이 E-1로 들어옴 — 드롭 (위조 시도 가능): {}", item.eventType());
                continue;
            }
            if (!EVENT_TYPES.contains(item.eventType())) {
                log.warn("화이트리스트 외 eventType 폐기: {}", item.eventType());
                continue;
            }
            if (item.id() != null && !seenIds.add(item.id())) {
                continue; // 배치 내 중복 (02 D35)
            }
            accepted.add(item);
        }
        return accepted;
    }

    /**
     * 브라우저 시계를 그대로 믿지 않는다 (노션 E-1 이상치 처리). 정상이면 occurred_at은 항상
     * created_at보다 살짝 과거다(행동이 먼저, 도착이 나중). 수신 시각보다 **미래**거나 3일 이상
     * **과거**면 수신 시각으로 대체하고 `_timeShifted`를 남긴다.
     * 값이 아예 없을 때도 같은 처리 — 클라이언트 시각이 없다는 뜻이므로 표시 없이 넘기지 않는다.
     */
    private LocalDateTime resolveOccurredAt(EventBatchRequest.EventItem item,
                                            LocalDateTime receivedAt,
                                            Map<String, Object> properties) {
        if (item.occurredAt() == null) {
            properties.put("_timeShifted", true);
            return receivedAt;
        }
        LocalDateTime occurredAt = item.occurredAt().atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        if (occurredAt.isAfter(receivedAt)
                || occurredAt.isBefore(receivedAt.minus(MAX_CLOCK_LAG))) {
            properties.put("_timeShifted", true);
            return receivedAt;
        }
        return occurredAt;
    }

    /** 쿠키의 guest_id가 DB에 없으면(로컬 DB 초기화 등) FK 위반으로 배치가 죽지 않게 무주체 처리 */
    private String resolveGuestId(String guestId) {
        if (guestId == null || !guestRepository.existsById(guestId)) {
            return null;
        }
        return guestId;
    }

    /** ③ ipHash 주입 + schemaVersion 동봉 + 필수 키 누락 표시 (전용 컬럼을 늘리지 않는다, 02 D38) */
    private Map<String, Object> properties(EventBatchRequest.EventItem item, String clientIp) {
        Map<String, Object> properties = item.properties() == null
                ? new HashMap<>() : new HashMap<>(item.properties());
        if (SESSION_START.equals(item.eventType())) {
            properties.put("ipHash", TokenHasher.sha256Hex(clientIp)); // 서버 주입 (02 §4)
        }
        if (item.schemaVersion() != null) {
            properties.put("schemaVersion", item.schemaVersion());
        }
        Set<String> required = REQUIRED_PROPERTIES.getOrDefault(item.eventType(), Set.of());
        if (!properties.keySet().containsAll(required)) {
            // 버리지 않는 건 배치를 실패시키지 않는다는 원칙 때문이고, 표시를 남기는 건
            // 집계가 조용히 틀어지는 걸 막기 위해서다 — "이 기간 N% 불완전"이라고 말할 수 있게
            log.warn("properties 필수 키 누락 — _incomplete 표시 후 저장 (eventType={})", item.eventType());
            properties.put("_incomplete", true);
        }
        return properties;
    }

    private String serialize(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            log.warn("properties 직렬화 실패 — null로 적재", e);
            return null;
        }
    }
}
