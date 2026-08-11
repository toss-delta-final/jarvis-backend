package com.jarvis.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.chat.dto.RecommendedCardResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.RecommendationCallbackRequest;
import com.jarvis.member.GuestService;
import com.jarvis.internal.dto.RecommendationCallbackRequest.ListEntry;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.recommendation.RecommendationEventRecorder;
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
    private final RecommendationEventRecorder recommendationEventRecorder;
    private final GuestService guestService;

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
        List<ListEntry> entries = request.lists();
        if (entries == null || entries.isEmpty() || entries.size() > MAX_LISTS) {
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
        List<RecommendationList> saved = persist(request, entries, listType, requestId, identity);

        String owner = identity == null ? null : ownerKey(identity);
        entries.forEach(entry -> cache(entry, owner, requestId, listType, request.totalBudget()));

        // 분석 적재는 맨 끝 — 여기서 실패해도 조회 경로(DB·Redis)는 이미 준비돼 있다.
        // 재전송으로 건너뛴 목록은 saved에 없으므로 recommendation_generated가 이중 계상되지 않는다.
        recommendationEventRecorder.recordGenerated(saved);
    }

    /**
     * DB가 정본이라 먼저 쓴다. Redis 쓰기는 트랜잭션 밖에서 한다 —
     * 열린 DB 트랜잭션 안에서 Redis를 기다리지 않는다(ChatSessionService와 같은 원칙).
     *
     * @return 이번 호출로 새로 저장된 목록 — recommendation_generated 적재 대상(재전송분 제외)
     */
    private List<RecommendationList> persist(RecommendationCallbackRequest request,
                                             List<ListEntry> entries,
                                             RecommendationListType listType, String requestId,
                                             ChatIdentity identity) {
        LocalDateTime now = LocalDateTime.now();
        List<RecommendationList> lists = new ArrayList<>();
        List<RecommendationListItem> items = new ArrayList<>();
        // totalBudget은 콜백 단위(BUY_ALL일 때만 실린다)라 목록마다 같은 값이 들어간다 — 보낸 값을 그대로 둔다
        for (ListEntry entry : entries) {
            lists.add(RecommendationList.ofChat(entry.listId(), requestId, listType, request.sessionId(),
                    identity, entry.label(), request.totalBudget(), entry.productIds().size(), now));
            for (int position = 0; position < entry.productIds().size(); position++) {
                items.add(RecommendationListItem.of(entry.listId(), position,
                        entry.productIds().get(position), now));
            }
        }
        try {
            List<String> skipped = recommendationListStore.saveAll(lists, items);
            if (skipped.isEmpty()) {
                return lists;
            }
            // 정상 재시도(타임아웃 후 재전송)면 무해. 같은 listId에 다른 내용이 오는 계약 위반은
            // 어차피 최초 수신본이 이기지만(DB·Redis 동일), 조용히 묻히지 않게 남긴다.
            log.warn("이미 저장된 listId 재전송 — 최초 수신본 유지 (recommendationRequestId={}, listIds={})",
                    requestId, skipped);
            return lists.stream()
                    .filter(list -> !skipped.contains(list.getListId()))
                    .toList();
        } catch (DataIntegrityViolationException e) {
            // 같은 콜백이 동시에 두 번 도착한 경우 — 다른 쪽이 이미 넣었으므로 멱등하게 통과시킨다.
            // 여기서 500을 내면 FastAPI가 products.ready를 발행하지 못해 FE가 카드를 못 받는다.
            // 저장 주체가 저쪽이므로 recommendation_generated도 저쪽이 남긴다 — 여기선 비운다.
            log.warn("추천 목록 중복 저장 경합 — 멱등 처리 (recommendationRequestId={})", requestId, e);
            return List.of();
        }
    }

    /**
     * Redis는 CH-5 조회 전용 캐시. setIfAbsent인 이유 두 가지(노션 I-21 멱등·TTL 규약) —
     * ① 재전송이 덮어쓰면 DB(최초 수신본 유지)와 내용이 갈라진다: E-1 귀속은 DB의 position을 쓰는데
     *    CH-5는 새 내용을 보여주게 된다. ② 덮어쓰기는 TTL을 다시 시작시켜 "생성 시점 고정"을 깬다.
     * DB만 성공하고 Redis 쓰기가 실패했던 재시도는 키가 없으므로 여전히 채워진다.
     */
    private void cache(ListEntry entry, String owner, String requestId,
                       RecommendationListType listType, Integer totalBudget) {
        Map<Long, String> reasonById = entry.reasons() == null ? Map.of() : entry.reasons().stream()
                .filter(r -> r.productId() != null && r.reason() != null)
                .collect(Collectors.toMap(RecommendationCallbackRequest.Reason::productId,
                        RecommendationCallbackRequest.Reason::reason, (a, b) -> b));
        // 세션 TTL이 아니라 목록 전용 TTL — 근거가 다르다(07 §2-1). 세션은 대화 지속성이,
        // 이쪽은 listId 노출 창이 근거라 세션을 늘려도 이 값이 따라 늘어나면 안 된다.
        redisTemplate.opsForValue().setIfAbsent(LIST_KEY_PREFIX + entry.listId(),
                toJson(new StoredList(entry.productIds(), reasonById, owner,
                        requestId, listType.name(), entry.label(), totalBudget)),
                Duration.ofMinutes(chatProperties.listTtlMinutes()));
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
        // 같은 상품이 두 번 실리면 (list_id, position) PK로 position만 다른 2행이 생기고
        // 카드·이유가 두 번 렌더된다 — 조용히 이상한 데이터를 만들지 말고 거절한다
        if (productIds.stream().distinct().count() != productIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // label은 컬럼이 VARCHAR(50)이라 넘기면 DB에서 터진다 — 500 대신 400으로 돌려준다
        if (entry.label() != null && entry.label().length() > MAX_LABEL_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * 누락은 DTO의 {@code @NotBlank}가 400으로 거른다(2026-08-07 — 노션 I-21 「필수 필드 누락」 행 개정).
     * 종전엔 PICK_ONE으로 채우고 경고만 남겼는데, 그러면 BUY_ALL 세트가 PICK_ONE으로 오기록되고
     * 집계할 때까지 아무도 모른다. 알 수 없는 값은 그때도 지금도 400이다.
     */
    private static RecommendationListType resolveListType(String listType) {
        // null 가드는 @NotBlank가 이미 막은 뒤의 심층 방어다 — valueOf(null)은 NPE라 500이 된다
        if (listType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return RecommendationListType.valueOf(listType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** 누락은 마찬가지로 {@code @NotBlank}가 막는다 — 서버가 발급하면 그 추천만 실행 단위 집계에서 홀로 남았다. */
    private static String resolveRequestId(String requestId) {
        if (requestId == null || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return requestId;
    }

    /**
     * CH-5 — 저장 순서 보존 + 카드에 reason echo, HIDDEN·품절 드롭 (노션 CH-5 — 2026-07-28 확정).
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
                .filter(card -> card != null && card.available())
                .map(card -> RecommendedCardResponse.of(card, stored.reasons().get(card.productId())))
                .toList();
        // 드롭 = 콜백에 실렸으나 지금은 못 파는 것(품절·HIDDEN) + 그 사이 사라진 상품.
        // I-1이 후보 단계에서 이미 품절을 걸러내므로, 여기 잡히는 건 추천 이후 재고가 소진된 경우다.
        int itemsDropped = stored.productIds().size() - items.size();
        // 구 포맷 캐시(확장 배포 직전 저장, TTL 10분 내 소멸)는 listType이 없다 — PICK_ONE으로 간주
        String listType = stored.listType() != null ? stored.listType()
                : RecommendationListType.PICK_ONE.name();
        boolean buyAll = RecommendationListType.BUY_ALL.name().equals(listType);
        // sum은 "남은 상품" 기준 재계산 — 화면 카드들의 합과 항상 일치해야 한다(노션 CH-5).
        // withinBudget은 드롭이 있거나 예산 미발화면 판정 불가 — 키 생략이 아니라 null 리터럴.
        Integer sum = buyAll ? items.stream().mapToInt(RecommendedCardResponse::price).sum() : null;
        Object withinBudget = null;
        if (buyAll) {
            withinBudget = itemsDropped == 0 && stored.totalBudget() != null
                    ? Boolean.valueOf(sum <= stored.totalBudget())
                    : RecommendationListResponse.VERDICT_UNAVAILABLE;
        }
        return new RecommendationListResponse(listId, stored.recommendationRequestId(), listType,
                stored.label(), buyAll ? stored.totalBudget() : null, sum, withinBudget,
                itemsDropped, items);
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
    /**
     * 소유자 검증 — 불일치는 403이 아니라 404로 통일해 listId의 존재를 노출하지 않는다(노션 CH-5).
     * 게스트 승계 예외(2026-07-31): 목록 주인이 게스트이고 그 구간의 귀속 계정이 요청 회원이면 통과한다.
     * 게스트로 추천받은 뒤 로그인하면 카드가 404가 되던 것을 막는다 — 추천 목록은 로그성 자산이라
     * 고쳐 쓰지 않고 귀속 기록으로 잇는다(GUEST-LIFECYCLE). 채팅 세션 승계(CH-7) 여부와는 무관하다.
     */
    private void requireOwner(StoredList stored, ChatIdentity requester) {
        if (stored.owner() == null || requester == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (stored.owner().equals(ownerKey(requester))) {
            return;
        }
        if (!inheritedByRequester(stored.owner(), requester)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private boolean inheritedByRequester(String owner, ChatIdentity requester) {
        if (!ChatIdentity.TYPE_MEMBER.equals(requester.subType())
                || !owner.startsWith(ChatIdentity.TYPE_GUEST + ":")) {
            return false;
        }
        String ownerGuestId = owner.substring(ChatIdentity.TYPE_GUEST.length() + 1);
        return guestService.isOwnedBy(ownerGuestId, Long.valueOf(requester.sub()));
    }

    private static String ownerKey(ChatIdentity identity) {
        return identity.subType() + ":" + identity.sub();
    }

    /**
     * Redis 저장 형식 — ids 순서가 렌더 순서, reasons는 productId 키잉, owner는 CH-5 소유자 검증용.
     * requestId·listType·label·totalBudget은 CH-5 응답 확장분(노션 CH-5 2026-07-28) — CH-5는
     * DB를 읽지 않으므로 캐시에 함께 담는다. 배포 직전에 저장된 구 포맷 키는 이 필드들이 null로
     * 역직렬화되며(TTL 10분 내 자연 소멸), getList가 listType null을 PICK_ONE으로 간주한다.
     */
    record StoredList(List<Long> productIds, Map<Long, String> reasons, String owner,
                      String recommendationRequestId, String listType, String label,
                      Integer totalBudget) {
    }
}
