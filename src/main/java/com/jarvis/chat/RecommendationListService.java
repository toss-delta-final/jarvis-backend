package com.jarvis.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * I-21 콜백 저장 + CH-5 조회 (05 §1-2-1 — 2026-07-18 LLM 합의로 스키마 확정).
 * FastAPI가 listId를 생성해 확정 Top5(+카드용 reason)를 넘기면 Redis TTL로 들고,
 * FE가 products.ready{listId} 수신 후 카드+이유를 pull한다. 순서 = 콜백 저장 순서.
 */
@Service
@RequiredArgsConstructor
public class RecommendationListService {

    private static final String LIST_KEY_PREFIX = "chat:list:";
    private static final int MAX_PRODUCT_IDS = 20; // P-7 ids 상한과 동일 (04 §2)
    // listId는 FastAPI 생성 문자열(예: "list-4471") — Redis 키가 되므로 안전 문자만 허용(키 인젝션 차단)
    private static final Pattern LIST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final StringRedisTemplate redisTemplate;
    private final ProductService productService;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;

    /** I-21 — sessionId는 세션 계약상 UUID, listId는 안전 문자열이면 형식 무관 */
    public void store(String sessionId, String listId, List<Long> productIds,
                      List<RecommendationCallbackRequest.Reason> reasons) {
        requireUuid(sessionId);
        if (listId == null || !LIST_ID_PATTERN.matcher(listId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (productIds == null || productIds.isEmpty() || productIds.size() > MAX_PRODUCT_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Map<Long, String> reasonById = reasons == null ? Map.of() : reasons.stream()
                .filter(r -> r.productId() != null && r.reason() != null)
                .collect(Collectors.toMap(RecommendationCallbackRequest.Reason::productId,
                        RecommendationCallbackRequest.Reason::reason, (a, b) -> b));
        redisTemplate.opsForValue().set(LIST_KEY_PREFIX + listId,
                toJson(new StoredList(productIds, reasonById)),
                Duration.ofMinutes(chatProperties.sessionTtlMinutes()));
    }

    /** CH-5 — 저장 순서 보존 + 카드에 reason echo, HIDDEN·품절 드롭(P-7과 동일 — 05 §1-2-1) */
    public RecommendationListResponse getList(String listId) {
        String value = redisTemplate.opsForValue().get(LIST_KEY_PREFIX + listId);
        if (value == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        StoredList stored = fromJson(value);
        Map<Long, ProductCardResponse> cards = productService.getCardsByIds(stored.productIds())
                .stream().collect(Collectors.toMap(ProductCardResponse::productId, Function.identity()));
        List<RecommendedCardResponse> items = stored.productIds().stream()
                .map(cards::get)
                .filter(card -> card != null && card.purchasable())
                .map(card -> RecommendedCardResponse.of(card, stored.reasons().get(card.productId())))
                .toList();
        return new RecommendationListResponse(listId, items);
    }

    private String toJson(StoredList stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private StoredList fromJson(String value) {
        try {
            return objectMapper.readValue(value, StoredList.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Redis 저장 형식 — ids 순서가 렌더 순서, reasons는 productId 키잉 */
    record StoredList(List<Long> productIds, Map<Long, String> reasons) {
    }
}
