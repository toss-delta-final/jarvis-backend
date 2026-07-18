package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** I-21 콜백 저장 + CH-5 조회 (05 §1-2-1) */
@ExtendWith(MockitoExtension.class)
class RecommendationListServiceTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String LIST_ID = "22222222-2222-2222-2222-222222222222";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock ProductService productService;
    @Mock ChatProperties chatProperties;

    @InjectMocks RecommendationListService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(chatProperties.sessionTtlMinutes()).thenReturn(10L);
    }

    @Test
    @DisplayName("I-21 — 순서 유지 저장 (Redis TTL)")
    void store() {
        service.store(SESSION_ID, LIST_ID, List.of(3L, 1L, 2L));

        verify(valueOperations).set("chat:list:" + LIST_ID, "3,1,2", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("I-21 — listId가 UUID가 아니면 400 (Redis 키 인젝션 차단)")
    void storeRejectsNonUuid() {
        assertThatThrownBy(() -> service.store(SESSION_ID, "../evil", List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("CH-5 — 저장 순서 보존 + HIDDEN·품절 드롭 (05 §1-2-1)")
    void getListDropsUnpurchasable() {
        when(valueOperations.get("chat:list:" + LIST_ID)).thenReturn("3,1,2");
        when(productService.getCardsByIds(List.of(3L, 1L, 2L))).thenReturn(List.of(
                card(3L, true), card(1L, false), card(2L, true)));

        RecommendationListResponse response = service.getList(LIST_ID);

        assertThat(response.items()).extracting(ProductCardResponse::productId)
                .containsExactly(3L, 2L);
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
