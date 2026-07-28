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
    private static final int MAX_PRODUCT_IDS = 20; // 카드 다건 조회 상한 (04 §2)
    // listId는 FastAPI 생성 문자열(예: "list-4471") — Redis 키가 되므로 안전 문자만 허용(키 인젝션 차단)
    private static final Pattern LIST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final StringRedisTemplate redisTemplate;
    private final ProductService productService;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;

    /**
     * I-21 — sessionId는 세션 계약상 UUID, listId는 안전 문자열이면 형식 무관.
     * 세션에 기록된 신원을 목록에 함께 박아 CH-5 조회 시 소유자를 검증한다(노션 CH-5).
     * 세션이 이미 사라졌으면 owner를 남기지 못하며, 그 목록은 CH-5가 읽지 못한다(fail-closed).
     * 그래도 저장·200은 유지 — 여기서 실패시키면 FastAPI가 products.ready를 못 쏘게 되고(05 §1-2-1),
     * 세션이 없다는 건 정당한 독자도 이미 없다는 뜻이라 목록을 못 읽는 게 손해가 아니다.
     */
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
        String owner = chatSessionService.findIdentity(sessionId).map(RecommendationListService::ownerKey)
                .orElse(null);
        redisTemplate.opsForValue().set(LIST_KEY_PREFIX + listId,
                toJson(new StoredList(productIds, reasonById, owner)),
                Duration.ofMinutes(chatProperties.sessionTtlMinutes()));
    }

    /**
     * CH-5 — 저장 순서 보존 + 카드에 reason echo, HIDDEN·품절 드롭 (05 §1-2-1).
     * 소유자 불일치는 403이 아니라 404 — listId의 존재 여부를 노출하지 않는다(노션 CH-5).
     */
    public RecommendationListResponse getList(String listId, ChatIdentity requester) {
        String value = redisTemplate.opsForValue().get(LIST_KEY_PREFIX + listId);
        if (value == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        StoredList stored = fromJson(value);
        requireOwner(stored, requester);
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

    /**
     * 소유자 검증 — 목록에 owner가 없으면(세션이 이미 사라진 뒤 도착한 콜백) 아무도 못 읽는다.
     * 불일치·신원 없음도 모두 404로 수렴시켜 listId 존재 여부를 노출하지 않는다.
     */
    private static void requireOwner(StoredList stored, ChatIdentity requester) {
        if (stored.owner() == null || requester == null
                || !stored.owner().equals(ownerKey(requester))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static String ownerKey(ChatIdentity identity) {
        return identity.subType() + ":" + identity.sub();
    }

    /** Redis 저장 형식 — ids 순서가 렌더 순서, reasons는 productId 키잉, owner는 CH-5 소유자 검증용 */
    record StoredList(List<Long> productIds, Map<Long, String> reasons, String owner) {
    }
}
