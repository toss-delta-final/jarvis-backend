package com.jarvis.seller;

import com.jarvis.cart.CartEventRecorder;
import com.jarvis.global.config.KafkaConfig;
import com.jarvis.global.event.BehaviorEventMessage;
import com.jarvis.product.ProductBrandIndex;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 컨슈머 그룹 B — visitor-tracker (08 §0). 같은 토픽을 컨슈머 A와 <b>독립적으로</b> 읽어
 * S-1 실시간 방문자 집계를 유지한다. 그룹이 다르므로 A가 밀리거나 죽어도 이쪽은 영향이 없다 —
 * 이 팬아웃이 파이프라인을 스트림으로 만든 이유다.
 *
 * <p>브랜드 귀속은 S-1 정의와 같다 — {@code product_id}가 있는 이벤트만 해당 상품의 브랜드로 센다.
 * 상품과 무관한 이벤트({@code session_start}·{@code page_view} 등)는 귀속할 브랜드가 없어 제외된다.
 * 챗봇 sentinel 세션도 제외한다(아래) — 폴백 쿼리와 같은 기준이어야 숫자가 흔들리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveVisitorConsumer {

    private final ProductBrandIndex productBrandIndex;
    private final ActiveVisitorStore activeVisitorStore;

    @KafkaListener(topics = KafkaConfig.TOPIC, groupId = KafkaConfig.VISITOR_TRACKER_GROUP)
    public void consume(List<BehaviorEventMessage> messages) {
        for (BehaviorEventMessage message : messages) {
            Long brandId = productBrandIndex.brandOf(message.productId());
            // 챗봇 sentinel은 브라우저 세션과 ID 공간이 달라 방문자로 세면 같은 사람이 두 번 잡힌다
            // (노션 E-1·I-2 한계 ①). 폴백 쿼리도 같은 기준으로 거른다 — 두 경로의 숫자가 같아야 한다
            if (brandId == null || !CartEventRecorder.isBrowserSession(message.sessionKey())) {
                continue;
            }
            activeVisitorStore.record(brandId, message.sessionKey(), message.createdAt());
        }
    }

}
