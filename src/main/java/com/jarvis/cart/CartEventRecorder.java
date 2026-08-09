package com.jarvis.cart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventPublisher;
import com.jarvis.global.event.EventAttribution;
import com.jarvis.recommendation.RecommendationAttributionResolver;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 장바구니 행동 이벤트 서버 적재 (노션 E-1·C-2·C-4·I-2·I-24 — 2026-08-06 개정).
 *
 * <p><b>왜 FE에서 서버로 옮겼나</b> — 챗봇이 대신 담는 경로(SSE {@code CART_ADDED})는 페이로드가
 * {@code {cartItemId, message}}뿐이라 FE가 {@code quantity}·{@code price}를 계약대로 채울 수 없어
 * {@code _incomplete}로 쌓여 왔다. 데이터가 실제로 변하는 지점에서 서버가 남기면 담기 세 경로
 * (상품상세·챗봇·SSE)가 한 번에 커버된다.
 *
 * <p><b>왜 컨트롤러가 아니라 여기인가</b> — {@link CartService} 한 지점에 심으면 공개 경로(C-2·C-4)와
 * 챗봇 경로(I-2·I-24)가 같이 커버된다. 컨트롤러에 뒀으면 네 곳에 같은 코드를 넣어야 했다.
 *
 * <p><b>커밋 이후에 적재한다</b> — 적재가 실패해도 담기·삭제는 성공해야 하고(경고 로그만),
 * 롤백된 담기가 이벤트로 남아서도 안 된다. 발행은 {@link BehaviorEventPublisher}가 자체 트랜잭션으로
 * 하고, 그마저 실패하면 아래 {@code catch}가 삼킨다 — 분석 한 줄로 담기를 되돌리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartEventRecorder {

    public static final String ADD_EVENT_TYPE = "add_to_cart";
    public static final String REMOVE_EVENT_TYPE = "remove_from_cart";

    /**
     * 챗봇 경로의 {@code session_key} sentinel 접두사 (노션 I-2 「C안 확정」).
     *
     * <p>FastAPI는 FE localStorage의 세션 키를 알 수 없어 채팅 sessionId를 대신 싣는다.
     * <b>이 값은 브라우저 세션과 ID 공간이 다르다</b> — 세션 단위로 세거나 묶는 집계
     * (방문 수 · I-13 체류시간 차분 · abuse 속도 신호)는 <b>이 접두사를 반드시 제외</b>해야 한다.
     * 제외하지 않으면 한 사람의 한 방문이 둘로 세어지고 체류시간 구간이 끊긴다(노션 I-2 한계 3가지).
     * 새로 세션 집계를 만들 때는 {@link #isBrowserSession(String)}을 쓴다.
     */
    public static final String CHAT_SESSION_KEY_PREFIX = "chat:";

    private final BehaviorEventPublisher behaviorEventPublisher;
    private final RecommendationAttributionResolver attributionResolver;
    private final ObjectMapper objectMapper;

    /**
     * 적재 재료 — <b>트랜잭션 안에서 미리 뽑아 둔다.</b> 커밋 뒤 다른 스레드에서 엔티티를 다시 읽으면
     * 영속성 컨텍스트가 없어 터진다.
     *
     * @param listId 클라이언트가 주장한 목록 id. 서버가 E-1 ③.5로 검증·도출하므로 그대로 믿지 않는다
     */
    public record CartEvent(String eventType, Long memberId, String guestId, String sessionKey,
                            Long productId, Long optionId, int quantity, int price, String listId) {
    }

    /**
     * 세션 단위 집계에서 브라우저 세션만 골라내는 판정. 흩어놓으면 한 곳만 빠뜨려도
     * 숫자가 조용히 틀리므로 여기 한 곳에 둔다.
     */
    public static boolean isBrowserSession(String sessionKey) {
        return sessionKey != null && !sessionKey.startsWith(CHAT_SESSION_KEY_PREFIX);
    }

    /**
     * 커밋 성공 후에만 적재한다. 트랜잭션 밖에서 불리면(테스트 등) 즉시 적재한다.
     *
     * <p>{@code sessionKey}가 없으면 <b>이벤트만 건너뛴다</b> — 분석 때문에 사용자의 담기·삭제를
     * 실패시키지 않는다(노션 C-2·C-4 「누락 시 정상 처리」). 컬럼이 {@code NOT NULL}이라 채울 수도 없다.
     */
    public void record(CartEvent event) {
        if (event.sessionKey() == null || event.sessionKey().isBlank()) {
            log.warn("{} 적재 스킵 — sessionKey 없음 (X-Session-Key 헤더 또는 chatSessionId 누락, productId={})",
                    event.eventType(), event.productId());
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            append(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                append(event);
            }
        });
    }

    private void append(CartEvent event) {
        try {
            LocalDateTime now = LocalDateTime.now();
            behaviorEventPublisher.publish(List.of(BehaviorEvent.record(
                    event.memberId(), event.guestId(), event.sessionKey(),
                    // 결정적 UUID를 쓰면 수량 합산으로 같은 라인을 두 번 담을 때 두 번째가 UNIQUE에
                    // 조용히 흡수돼 유실된다 — 상태 변경마다 새 무작위 id다 (노션 E-1 2026-08-06)
                    UUID.randomUUID().toString(),
                    event.eventType(), event.productId(), properties(event), now,
                    attribution(event), now)));
        } catch (Exception e) {
            // 분석 행 하나 때문에 이미 성공한 담기·삭제를 되돌릴 수는 없다
            log.warn("{} 적재 실패 — 1건 유실 (productId={})", event.eventType(), event.productId(), e);
        }
    }

    /**
     * 이벤트 귀속은 <b>소유자를 검증하지 않는다</b>(E-1 ③.5) — {@code cart_item} 저장용 3규칙과 분리한다.
     * 게스트로 추천을 받고 로그인한 뒤 담는 경계에서 전환이 소실되면 CTR 분모만 남기 때문이다.
     */
    private EventAttribution attribution(CartEvent event) {
        if (event.listId() == null || event.listId().isBlank()) {
            return EventAttribution.NONE;
        }
        return attributionResolver.resolve(
                attributionResolver.snapshot(List.of(event.listId())),
                event.listId(), event.productId());
    }

    /** 노션 E-1 「properties 계약」 — 필수 quantity·price, 선택 optionId */
    private String properties(CartEvent event) throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("quantity", event.quantity());
        properties.put("price", event.price());
        if (event.optionId() != null) {
            properties.put("optionId", event.optionId());
        }
        return objectMapper.writeValueAsString(properties);
    }
}
