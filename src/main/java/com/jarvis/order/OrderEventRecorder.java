package com.jarvis.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventPublisher;
import com.jarvis.global.event.EventAttribution;
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
 * 결제 완료 행동 이벤트 서버 적재 (노션 E-1·O-1·O-2 — 2026-08-11 개정).
 *
 * <p><b>왜 FE에서 서버로 옮겼나</b> — FE는 "주문 성사의 정본은 서버"라는 판단으로 발사를 뺐고
 * 서버 적재도 없어 이 이벤트가 어디서도 쌓이지 않았다(S-1 coverage 분자 상시 0). 결제가 실제로
 * 성사되는 지점(PAID 전이)에서 서버가 남기면 O-1 최초 결제·O-2 재결제가 한 번에 커버되고
 * 유실·중복이 원천적으로 없다 — 2026-08-06 장바구니 2종 이관과 같은 논리다.
 *
 * <p>커밋 이후에 적재한다 — 적재가 실패해도 결제는 성공해야 하고(경고 로그만), 롤백된 주문이
 * 이벤트로 남아서도 안 된다. {@link com.jarvis.cart.CartEventRecorder}와 동일 패턴.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventRecorder {

    public static final String EVENT_TYPE = "purchase_complete";

    private final BehaviorEventPublisher behaviorEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 적재 재료 — 트랜잭션 안에서 미리 뽑아 둔다.
     *
     * @param representativeProductId 대표 상품 1개(금액 최대 아이템 — S-2 대표상품 규칙과 동일 기준).
     *                                판매자 집계에는 쓰지 않는다(정본은 order_item 집계 — 노션 E-1)
     */
    public record PurchaseEvent(Long memberId, String sessionKey, Long orderId, int amount,
                                Long representativeProductId) {
    }

    /**
     * 커밋 성공 후에만 적재한다. {@code sessionKey}가 없으면 이벤트만 건너뛴다 —
     * 분석 때문에 결제를 실패시키지 않는다(노션 O-1 「누락 시 정상 처리」, 컬럼이 NOT NULL이라 채울 수도 없다).
     */
    public void record(PurchaseEvent event) {
        if (event.sessionKey() == null || event.sessionKey().isBlank()) {
            log.warn("purchase_complete 적재 스킵 — sessionKey 없음 (X-Session-Key 헤더 누락, orderId={})",
                    event.orderId());
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

    private void append(PurchaseEvent event) {
        try {
            LocalDateTime now = LocalDateTime.now();
            // 주문은 회원 전용(02 D30)이라 guestId는 항상 null. 추천 귀속은 order_item 스냅샷이 정본이라
            // 이벤트에는 싣지 않는다(E-1 — purchase_complete는 recommendation 문맥 없는 주문 단위 이벤트)
            behaviorEventPublisher.publish(List.of(BehaviorEvent.record(
                    event.memberId(), null, event.sessionKey(),
                    UUID.randomUUID().toString(),
                    EVENT_TYPE, event.representativeProductId(), properties(event), now,
                    EventAttribution.NONE, now)));
        } catch (Exception e) {
            // 분석 행 하나 때문에 이미 성공한 결제를 되돌릴 수는 없다
            log.warn("purchase_complete 적재 실패 — 1건 유실 (orderId={})", event.orderId(), e);
        }
    }

    /** 노션 E-1 「properties 계약」 — 필수 orderId·amount */
    private String properties(PurchaseEvent event) throws JsonProcessingException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orderId", event.orderId());
        properties.put("amount", event.amount());
        return objectMapper.writeValueAsString(properties);
    }
}
