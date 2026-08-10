package com.jarvis.seller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.global.event.BehaviorEventMessage;
import com.jarvis.product.ProductBrandIndex;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActiveVisitorConsumerTest {

    @Mock ProductBrandIndex productBrandIndex;
    @Mock ActiveVisitorStore activeVisitorStore;

    @InjectMocks ActiveVisitorConsumer consumer;

    private static BehaviorEventMessage event(String sessionKey, Long productId) {
        LocalDateTime now = LocalDateTime.now();
        return new BehaviorEventMessage(1L, null, sessionKey, "evt-" + sessionKey, "product_view",
                productId, null, now, null, null, null, null, now);
    }

    @Test
    @DisplayName("브라우저 세션만 방문자로 센다 — 챗봇 sentinel은 ID 공간이 달라 제외 (노션 E-1·I-2 한계 ①)")
    void skipsChatSentinelSessions() {
        lenient().when(productBrandIndex.brandOf(101L)).thenReturn(7L);

        consumer.consume(List.of(event("sess-browser", 101L), event("chat:abc-123", 101L)));

        verify(activeVisitorStore).record(eq(7L), eq("sess-browser"), any());
        verify(activeVisitorStore, never()).record(anyLong(), eq("chat:abc-123"), any());
    }

    @Test
    @DisplayName("브랜드를 해석하지 못한 이벤트는 건너뛴다 — 리프레시 전 신상품 (08 D4)")
    void skipsUnmappedProducts() {
        when(productBrandIndex.brandOf(999L)).thenReturn(null);

        consumer.consume(List.of(event("sess-1", 999L)));

        verify(activeVisitorStore, never()).record(anyLong(), anyString(), any());
    }

}
