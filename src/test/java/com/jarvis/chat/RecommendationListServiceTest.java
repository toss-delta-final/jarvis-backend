package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.chat.dto.RecommendedCardResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.RecommendationCallbackRequest;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** I-21 콜백 저장 + CH-5 조회 (05 §1-2-1 — 2026-07-18 스키마 확정) */
@ExtendWith(MockitoExtension.class)
class RecommendationListServiceTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String LIST_ID = "list-4471";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock ProductService productService;
    @Mock ChatProperties chatProperties;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks RecommendationListService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(chatProperties.sessionTtlMinutes()).thenReturn(10L);
    }

    @Test
    @DisplayName("I-21 — FastAPI 생성 listId 수용, 순서·reason 유지 저장 (Redis TTL)")
    void store() throws Exception {
        service.store(SESSION_ID, LIST_ID, List.of(3L, 1L, 2L),
                List.of(new RecommendationCallbackRequest.Reason(1L, "방수 등급이 높아요")));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("chat:list:" + LIST_ID), value.capture(),
                eq(Duration.ofMinutes(10)));
        RecommendationListService.StoredList stored =
                objectMapper.readValue(value.getValue(), RecommendationListService.StoredList.class);
        assertThat(stored.productIds()).containsExactly(3L, 1L, 2L);
        assertThat(stored.reasons()).containsEntry(1L, "방수 등급이 높아요");
    }

    @Test
    @DisplayName("I-21 — listId에 Redis 키 위험 문자는 400 (키 인젝션 차단)")
    void storeRejectsUnsafeListId() {
        assertThatThrownBy(() -> service.store(SESSION_ID, "../evil", List.of(1L), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("CH-5 — 저장 순서 보존 + reason echo + HIDDEN·품절 드롭 (05 §1-2-1)")
    void getListDropsUnpurchasableAndEchoesReason() throws Exception {
        String stored = objectMapper.writeValueAsString(new RecommendationListService.StoredList(
                List.of(3L, 1L, 2L), java.util.Map.of(3L, "가성비가 좋아요")));
        when(valueOperations.get("chat:list:" + LIST_ID)).thenReturn(stored);
        when(productService.getCardsByIds(List.of(3L, 1L, 2L))).thenReturn(List.of(
                card(3L, true), card(1L, false), card(2L, true)));

        RecommendationListResponse response = service.getList(LIST_ID);

        assertThat(response.items()).extracting(RecommendedCardResponse::productId)
                .containsExactly(3L, 2L);
        assertThat(response.items().get(0).reason()).isEqualTo("가성비가 좋아요");
        assertThat(response.items().get(1).reason()).isNull();
    }

    @Test
    @DisplayName("CH-5 — 만료·미존재 listId는 404")
    void getListNotFound() {
        when(valueOperations.get("chat:list:gone")).thenReturn(null);

        assertThatThrownBy(() -> service.getList("gone"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ProductCardResponse card(Long id, boolean purchasable) {
        return new ProductCardResponse(id, "상품" + id, "브랜드", 1000, 2000, "img", 0.0, 0, purchasable);
    }
}
