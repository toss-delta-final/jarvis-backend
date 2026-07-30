package com.jarvis.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.chat.dto.RecommendedCardResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.RecommendationCallbackRequest;
import com.jarvis.internal.dto.RecommendationCallbackRequest.ListEntry;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.recommendation.RecommendationList;
import com.jarvis.recommendation.RecommendationListItem;
import com.jarvis.recommendation.RecommendationListStore;
import com.jarvis.recommendation.RecommendationListType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * I-21 콜백 저장 + CH-5 조회 (노션 I-21·CH-5 — 2026-07-30 다중 목록 확정).
 * FastAPI가 listId를 생성해 확정 목록(+카드용 reason)을 넘기면 DB(정본)와 Redis(CH-5 조회 전용)에
 * 함께 저장하고, FE가 products.ready{listIds} 수신 후 listId마다 카드+이유를 pull한다.
 * 순서 = 콜백 저장 순서.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationListService {

    private static final String LIST_KEY_PREFIX = "chat:list:";
    private static final int MAX_PRODUCT_IDS = 9;  // 목록당 상한 (노션 I-21 — 2026-07-30 확정)
    private static final int MAX_LISTS = 10;       // lists 배열 상한 (노션 I-21 §6)
    private static final int MAX_LABEL_LENGTH = 50;
    private static final int MAX_REQUEST_ID_LENGTH = 36;
    // listId는 FastAPI 생성 문자열(예: "list-4471") — Redis 키가 되므로 안전 문자만 허용(키 인젝션 차단)
    private static final Pattern LIST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final StringRedisTemplate redisTemplate;
    private final ProductService productService;
    private final ChatProperties chatProperties;
    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;
    private final RecommendationListStore recommendationListStore;

    /**
     * I-21 — sessionId는 세션 계약상 UUID, listId는 안전 문자열이면 형식 무관.
     * 세션에 기록된 신원을 목록에 함께 박아 CH-5 조회 시 소유자를 검증한다(노션 CH-5).
     * 세션이 이미 사라졌으면 owner를 남기지 못하며, 그 목록은 CH-5가 읽지 못한다(fail-closed).
     * 그래도 저장·200은 유지 — 여기서 실패시키면 FastAPI가 products.ready를 못 쏘게 되고,
     * 세션이 없다는 건 정당한 독자도 이미 없다는 뜻이라 목록을 못 읽는 게 손해가 아니다.
     */
    public void store(RecommendationCallbackRequest request) {
        String sessionId = request.sessionId();
        requireUuid(sessionId);
        List<ListEntry> entries = request.resolvedLists();
        if (entries.isEmpty() || entries.size() > MAX_LISTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // 목록 전부를 저장 전에 검증한다 — 앞쪽만 저장되면 products.ready가 사본 없는 목록을 가리킨다
        entries.forEach(RecommendationListService::requireValidEntry);
        if (entries.stream().map(ListEntry::listId).distinct().count() != entries.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        RecommendationListType listType = resolveListType(request.listType());
        String requestId = resolveRequestId(request.recommendationRequestId());

        ChatIdentity identity = chatSessionService.findIdentity(sessionId).orElse(null);
        if (identity == null) {
            log.warn("추천 목록을 익명으로 저장한다 — 세션이 이미 만료됐다. CH-5에서 조회되지 않는다"
                    + " (recommendationRequestId={})", requestId);
        }
        persist(request, entries, listType, requestId, identity);

        String owner = identity == null ? null : ownerKey(identity);
        entries.forEach(entry -> cache(entry, owner));
    }

    /**
     * DB가 정본이라 먼저 쓴다. Redis 쓰기는 트랜잭션 밖에서 한다 —
     * 열린 DB 트랜잭션 안에서 Redis를 기다리지 않는다(ChatSessionService와 같은 원칙).
     */
    private void persist(RecommendationCallbackRequest request, List<ListEntry> entries,
                         RecommendationListType listType, String requestId, ChatIdentity identity) {
        LocalDateTime now = LocalDateTime.now();
        List<RecommendationList> lists = new ArrayList<>();
        List<RecommendationListItem> items = new ArrayList<>();
        // totalBudget은 콜백 단위(BUY_ALL일 때만 실린다)라 목록마다 같은 값이 들어간다 — 보낸 값을 그대로 둔다
        for (ListEntry entry : entries) {
            lists.add(RecommendationList.ofChat(entry.listId(), requestId, listType, request.sessionId(),
                    identity, entry.label(), request.totalBudget(), entry.productIds().size(), now));
            for (int position = 0; position < entry.productIds().size(); position++) {
                items.add(RecommendationListItem.of(entry.listId(), position,
                        entry.productIds().get(position)));
            }
        }
        try {
            recommendationListStore.saveAll(lists, items);
        } catch (DataIntegrityViolationException e) {
            // 같은 콜백이 동시에 두 번 도착한 경우 — 다른 쪽이 이미 넣었으므로 멱등하게 통과시킨다.
            // 여기서 500을 내면 FastAPI가 products.ready를 발행하지 못해 FE가 카드를 못 받는다.
            log.warn("추천 목록 중복 저장 경합 — 멱등 처리 (recommendationRequestId={})", requestId, e);
        }
    }

    /** Redis는 CH-5 조회 전용 캐시. TTL은 생성 시점 고정이라 세션이 연장돼도 늘어나지 않는다(노션 I-21). */
    private void cache(ListEntry entry, String owner) {
        Map<Long, String> reasonById = entry.reasons() == null ? Map.of() : entry.reasons().stream()
                .filter(r -> r.productId() != null && r.reason() != null)
                .collect(Collectors.toMap(RecommendationCallbackRequest.Reason::productId,
                        RecommendationCallbackRequest.Reason::reason, (a, b) -> b));
        redisTemplate.opsForValue().set(LIST_KEY_PREFIX + entry.listId(),
                toJson(new StoredList(entry.productIds(), reasonById, owner)),
                Duration.ofMinutes(chatProperties.sessionTtlMinutes()));
    }

    private static void requireValidEntry(ListEntry entry) {
        if (entry.listId() == null || !LIST_ID_PATTERN.matcher(entry.listId()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<Long> productIds = entry.productIds();
        // contains(null)은 List.of가 NPE를 던지므로 쓰지 않는다
        if (productIds == null || productIds.isEmpty() || productIds.size() > MAX_PRODUCT_IDS
                || productIds.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // label은 컬럼이 VARCHAR(50)이라 넘기면 DB에서 터진다 — 500 대신 400으로 돌려준다
        if (entry.label() != null && entry.label().length() > MAX_LABEL_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * listType은 "항상 싣는다"가 계약이지만 누락을 400으로 만들지 않는다 —
     * 노션 I-21 실패 응답 표에 없는 거절 사유를 새로 만들면 FastAPI가 카드를 못 띄운다.
     * 대체재/보완재 중 흔한 쪽인 PICK_ONE으로 채우고 경고만 남긴다. 알 수 없는 값은 400이다.
     */
    private static RecommendationListType resolveListType(String listType) {
        if (listType == null || listType.isBlank()) {
            log.warn("I-21 콜백에 listType이 없다 — PICK_ONE으로 저장한다");
            return RecommendationListType.PICK_ONE;
        }
        try {
            return RecommendationListType.valueOf(listType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** 같은 이유로 누락은 400이 아니다 — 컬럼이 NOT NULL이라 서버가 채운다(그 추천은 실행 단위 집계에서 홀로 남는다). */
    private static String resolveRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            log.warn("I-21 콜백에 recommendationRequestId가 없다 — 서버가 발급한다");
            return UUID.randomUUID().toString();
        }
        if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return requestId;
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
        // 드롭 = 콜백에 실렸으나 지금은 못 파는 것(품절·HIDDEN) + 그 사이 사라진 상품.
        // I-1이 후보 단계에서 이미 품절을 걸러내므로, 여기 잡히는 건 추천 이후 재고가 소진된 경우다.
        return new RecommendationListResponse(listId, items,
                stored.productIds().size() - items.size());
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
