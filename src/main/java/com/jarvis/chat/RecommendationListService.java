package com.jarvis.chat;

import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * I-21 콜백 저장 + CH-5 조회 (05 §1-2-1) — FastAPI가 확정한 Top5를 Redis TTL로 들고,
 * FE가 products.ready{listId} 수신 후 카드 완결 필드를 pull한다. 순서 = 콜백 저장 순서.
 * 스키마는 OPEN(LLM 협의 중) — 05 제안 형식({sessionId, listId, productIds[]}) 기준 임시 구현.
 */
@Service
@RequiredArgsConstructor
public class RecommendationListService {

    private static final String LIST_KEY_PREFIX = "chat:list:";
    private static final int MAX_PRODUCT_IDS = 20; // P-7 ids 상한과 동일 (04 §2)

    private final StringRedisTemplate redisTemplate;
    private final ProductService productService;
    private final ChatProperties chatProperties;

    /** I-21 — listId는 Redis 키가 되므로 UUID 형식 강제(키 인젝션 차단) */
    public void store(String sessionId, String listId, List<Long> productIds) {
        requireUuid(sessionId);
        requireUuid(listId);
        if (productIds == null || productIds.isEmpty() || productIds.size() > MAX_PRODUCT_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String value = String.join(",", productIds.stream().map(String::valueOf).toList());
        redisTemplate.opsForValue().set(LIST_KEY_PREFIX + listId, value,
                Duration.ofMinutes(chatProperties.sessionTtlMinutes()));
    }

    /** CH-5 — 저장 순서 보존, HIDDEN·품절 드롭(P-7과 동일 — FastAPI가 넉넉히 골라 대비, 05 §1-2-1) */
    public RecommendationListResponse getList(String listId) {
        String value = redisTemplate.opsForValue().get(LIST_KEY_PREFIX + listId);
        if (value == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<Long> ids = Arrays.stream(value.split(",")).map(Long::valueOf).toList();
        List<ProductCardResponse> cards = productService.getCardsByIds(ids).stream()
                .filter(ProductCardResponse::purchasable)
                .toList();
        return new RecommendationListResponse(listId, cards);
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
