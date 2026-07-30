package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.chat.dto.RecommendedCardResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.dto.RecommendationCallbackRequest;
import com.jarvis.internal.dto.RecommendationCallbackRequest.ListEntry;
import com.jarvis.internal.dto.RecommendationCallbackRequest.Reason;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.recommendation.RecommendationList;
import com.jarvis.recommendation.RecommendationListItem;
import com.jarvis.recommendation.RecommendationListStore;
import com.jarvis.recommendation.RecommendationListType;
import com.jarvis.recommendation.RecommendationSource;
import com.jarvis.recommendation.RecommendationSurface;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** I-21 콜백 저장 + CH-5 조회 (노션 I-21·CH-5 — 2026-07-30 다중 목록 확정) */
@ExtendWith(MockitoExtension.class)
class RecommendationListServiceTest {

    private static final String SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String REQUEST_ID = "a63be350-ec96-4f44-b3f9-c962b6673a68";
    private static final String LIST_ID = "list-4471";
    private static final String LIST_ID_2 = "list-4472";
    private static final ChatIdentity OWNER = ChatIdentity.member(7L);
    private static final String OWNER_KEY = "member:7";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock ProductService productService;
    @Mock ChatProperties chatProperties;
    @Mock ChatSessionService chatSessionService;
    @Mock RecommendationListStore recommendationListStore;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @Captor ArgumentCaptor<List<RecommendationList>> listCaptor;
    @Captor ArgumentCaptor<List<RecommendationListItem>> itemCaptor;

    @InjectMocks RecommendationListService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(chatProperties.sessionTtlMinutes()).thenReturn(10L);
        lenient().when(chatSessionService.findIdentity(SESSION_ID))
                .thenReturn(java.util.Optional.of(OWNER));
    }

    @Test
    @DisplayName("I-21 — FastAPI 생성 listId 수용, 순서·reason 유지 저장 (Redis TTL)")
    void store() throws Exception {
        service.store(request("PICK_ONE", null,
                entry(LIST_ID, null, List.of(3L, 1L, 2L), new Reason(1L, "방수 등급이 높아요"))));

        RecommendationListService.StoredList stored = capturedCache(LIST_ID);
        assertThat(stored.productIds()).containsExactly(3L, 1L, 2L);
        assertThat(stored.reasons()).containsEntry(1L, "방수 등급이 높아요");
        assertThat(stored.owner()).isEqualTo(OWNER_KEY);
    }

    // 니즈별 추천(감자 9·시래기 9·뼈 9)은 콜백 1건에 목록 여러 개로 온다 — 전부 저장돼야 CH-5가 각각 조회된다
    @Test
    @DisplayName("I-21 — 목록 여러 개를 한 콜백으로 받아 전부 저장 (DB 정본 + Redis 키 각각)")
    void storeMultipleLists() throws Exception {
        service.store(request("PICK_ONE", null,
                entry(LIST_ID, "감자", List.of(1L, 2L)),
                entry(LIST_ID_2, "시래기", List.of(3L))));

        verify(recommendationListStore).saveAll(listCaptor.capture(), itemCaptor.capture());
        assertThat(listCaptor.getValue()).extracting(RecommendationList::getListId)
                .containsExactly(LIST_ID, LIST_ID_2);
        assertThat(listCaptor.getValue()).extracting(RecommendationList::getItemCount)
                .containsExactly(2, 1);
        assertThat(itemCaptor.getValue())
                .extracting(RecommendationListItem::getListId, RecommendationListItem::getPosition,
                        RecommendationListItem::getProductId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LIST_ID, 0, 1L),
                        org.assertj.core.groups.Tuple.tuple(LIST_ID, 1, 2L),
                        org.assertj.core.groups.Tuple.tuple(LIST_ID_2, 0, 3L));
        assertThat(capturedCache(LIST_ID).productIds()).containsExactly(1L, 2L);
        assertThat(capturedCache(LIST_ID_2).productIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("I-21 — 채팅 콜백은 surface=CHAT, source=AI_RECOMMENDED 고정 (인기 fallback은 홈 전용)")
    void storeStampsChatSurfaceAndAiSource() {
        service.store(request("BUY_ALL", 50000, entry(LIST_ID, "알뜰", List.of(1L))));

        verify(recommendationListStore).saveAll(listCaptor.capture(), itemCaptor.capture());
        RecommendationList saved = listCaptor.getValue().get(0);
        assertThat(saved.getSurface()).isEqualTo(RecommendationSurface.CHAT);
        assertThat(saved.getSource()).isEqualTo(RecommendationSource.AI_RECOMMENDED);
        assertThat(saved.getListType()).isEqualTo(RecommendationListType.BUY_ALL);
        assertThat(saved.getLabel()).isEqualTo("알뜰");
        assertThat(saved.getTotalBudget()).isEqualTo(50000);
        assertThat(saved.getRecommendationRequestId()).isEqualTo(REQUEST_ID);
        assertThat(saved.getMemberId()).isEqualTo(7L);
        assertThat(saved.getGuestId()).isNull();
    }

    // FastAPI가 lists[]로 전환하기 전까지 구 평평 구조도 그대로 동작해야 한다(양쪽 배포 시점 분리)
    @Test
    @DisplayName("I-21 — 과도기 평평 구조(listId·productIds)도 목록 1건으로 수용")
    void storeAcceptsLegacyFlatShape() throws Exception {
        service.store(new RecommendationCallbackRequest(SESSION_ID, null, null, null, null,
                LIST_ID, List.of(5L, 6L), List.of(new Reason(5L, "가성비"))));

        verify(recommendationListStore).saveAll(listCaptor.capture(), itemCaptor.capture());
        assertThat(listCaptor.getValue()).hasSize(1);
        // listType이 없으면 대체재/보완재 중 흔한 쪽으로 채운다 — 400으로 만들지 않는다
        assertThat(listCaptor.getValue().get(0).getListType()).isEqualTo(RecommendationListType.PICK_ONE);
        // requestId도 컬럼이 NOT NULL이라 서버가 발급한다
        assertThat(listCaptor.getValue().get(0).getRecommendationRequestId()).isNotBlank();
        assertThat(capturedCache(LIST_ID).productIds()).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("I-21 — 세션이 이미 사라졌으면 owner 없이 저장(200 유지) — 그 목록은 CH-5가 못 읽는다")
    void storeWithoutSessionLeavesNoOwner() throws Exception {
        when(chatSessionService.findIdentity(SESSION_ID)).thenReturn(java.util.Optional.empty());

        service.store(request("PICK_ONE", null, entry(LIST_ID, null, List.of(1L))));

        assertThat(capturedCache(LIST_ID).owner()).isNull();
        verify(recommendationListStore).saveAll(listCaptor.capture(), itemCaptor.capture());
        RecommendationList saved = listCaptor.getValue().get(0);
        assertThat(saved.getMemberId()).isNull();
        assertThat(saved.getGuestId()).isNull();
    }

    @Test
    @DisplayName("I-21 — listId에 Redis 키 위험 문자는 400 (키 인젝션 차단)")
    void storeRejectsUnsafeListId() {
        assertThatThrownBy(() -> service.store(
                request("PICK_ONE", null, entry("../evil", null, List.of(1L)))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-21 — 목록당 상품 9개 초과는 400 (노션 I-21 — 목록당 상한)")
    void storeRejectsTooManyProductsPerList() {
        List<Long> ten = LongStream.rangeClosed(1, 10).boxed().toList();

        assertThatThrownBy(() -> service.store(request("PICK_ONE", null, entry(LIST_ID, null, ten))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-21 — 목록당 9개는 통과 (상한이 전체가 아니라 목록당이라는 계약)")
    void storeAcceptsNineProductsPerList() {
        List<Long> nine = LongStream.rangeClosed(1, 9).boxed().toList();

        service.store(request("PICK_ONE", null, entry(LIST_ID, null, nine),
                entry(LIST_ID_2, null, nine)));

        verify(recommendationListStore).saveAll(listCaptor.capture(), itemCaptor.capture());
        assertThat(itemCaptor.getValue()).hasSize(18);
    }

    @Test
    @DisplayName("I-21 — lists가 10개 초과면 400 (버그 한 번에 Redis 키·DB 행이 수백 개 생긴다)")
    void storeRejectsTooManyLists() {
        ListEntry[] eleven = LongStream.rangeClosed(1, 11)
                .mapToObj(i -> entry("list-" + i, null, List.of(i)))
                .toArray(ListEntry[]::new);

        assertThatThrownBy(() -> service.store(request("PICK_ONE", null, eleven)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-21 — lists가 비면 400 (빈 목록을 저장하면 FE가 빈 카드 패널을 받는다)")
    void storeRejectsEmptyLists() {
        assertThatThrownBy(() -> service.store(request("PICK_ONE", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("I-21 — 알 수 없는 listType은 400")
    void storeRejectsUnknownListType() {
        assertThatThrownBy(() -> service.store(
                request("CHEAPEST", null, entry(LIST_ID, null, List.of(1L)))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // 같은 listId가 두 번 오면 멱등 처리에 걸려 뒤 목록이 조용히 사라진다 — 삼키지 말고 거절한다
    @Test
    @DisplayName("I-21 — 한 콜백 안에 같은 listId가 겹치면 400")
    void storeRejectsDuplicateListIdInSameCallback() {
        assertThatThrownBy(() -> service.store(request("PICK_ONE", null,
                entry(LIST_ID, null, List.of(1L)), entry(LIST_ID, null, List.of(2L)))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // 뒤 목록이 잘못됐는데 앞 목록만 저장되면 products.ready가 사본 없는 목록을 가리킨다
    @Test
    @DisplayName("I-21 — 목록 하나가 잘못되면 앞 목록도 저장하지 않는다")
    void storeValidatesAllListsBeforeSavingAny() {
        assertThatThrownBy(() -> service.store(request("PICK_ONE", null,
                entry(LIST_ID, null, List.of(1L)), entry("../evil", null, List.of(2L)))))
                .isInstanceOf(BusinessException.class);

        verify(recommendationListStore, never()).saveAll(any(), any());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // 재전송이 동시에 도착해 UNIQUE에 걸려도 200이어야 한다 — 500이면 products.ready가 발행되지 않는다
    @Test
    @DisplayName("I-21 — DB 중복 저장 경합은 멱등 통과, Redis 캐시는 계속 쓴다")
    void storeTreatsDuplicateInsertAsIdempotent() throws Exception {
        doThrow(new DataIntegrityViolationException("uk_reco_list"))
                .when(recommendationListStore).saveAll(any(), any());

        service.store(request("PICK_ONE", null, entry(LIST_ID, null, List.of(1L))));

        assertThat(capturedCache(LIST_ID).productIds()).containsExactly(1L);
    }

    // 타임아웃 후 재전송 — DB는 건너뛰어도 200이고, Redis는 setIfAbsent라 첫 저장이 실패했던
    // 키만 채워진다(있으면 no-op → 내용·TTL 불변). DB와 Redis가 항상 최초 수신본으로 일치한다
    @Test
    @DisplayName("I-21 — 이미 저장된 listId 재전송은 멱등 통과, 캐시는 없을 때만 채운다")
    void storeResendKeepsFirstCopy() throws Exception {
        when(recommendationListStore.saveAll(any(), any())).thenReturn(List.of(LIST_ID));

        service.store(request("PICK_ONE", null, entry(LIST_ID, null, List.of(9L))));

        assertThat(capturedCache(LIST_ID).productIds()).containsExactly(9L);
    }

    // 같은 상품이 두 번 실리면 (list_id, position) PK로 position만 다른 2행 + 카드 중복 렌더가 된다
    @Test
    @DisplayName("I-21 — 한 목록 안에 같은 productId가 겹치면 400")
    void storeRejectsDuplicateProductIdInList() {
        assertThatThrownBy(() -> service.store(
                request("PICK_ONE", null, entry(LIST_ID, null, List.of(1L, 2L, 1L)))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("CH-5 — 저장 순서 보존 + reason echo + HIDDEN·품절 드롭 (05 §1-2-1)")
    void getListDropsUnpurchasableAndEchoesReason() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(3L, 1L, 2L), java.util.Map.of(3L, "가성비가 좋아요"), OWNER_KEY));
        when(productService.getCardsByIds(List.of(3L, 1L, 2L))).thenReturn(List.of(
                card(3L, true), card(1L, false), card(2L, true)));

        RecommendationListResponse response = service.getList(LIST_ID, OWNER);

        assertThat(response.items()).extracting(RecommendedCardResponse::productId)
                .containsExactly(3L, 2L);
        assertThat(response.items().get(0).reason()).isEqualTo("가성비가 좋아요");
        assertThat(response.items().get(1).reason()).isNull();
        assertThat(response.itemsDropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("CH-5 — 전부 품절이어도 404가 아니라 200 + 빈 items + itemsDropped")
    void getListReportsAllDropped() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L, 2L), java.util.Map.of(), OWNER_KEY));
        when(productService.getCardsByIds(List.of(1L, 2L)))
                .thenReturn(List.of(card(1L, false), card(2L, false)));

        RecommendationListResponse response = service.getList(LIST_ID, OWNER);

        assertThat(response.items()).isEmpty();
        assertThat(response.itemsDropped()).isEqualTo(2);
    }

    // 카드 조회 자체가 못 찾은 상품(삭제 등)도 드롭 수에 포함돼야 한다 — purchasable 필터만 세면 어긋난다
    @Test
    @DisplayName("CH-5 — 카드가 조회되지 않은 상품도 itemsDropped에 포함")
    void getListCountsMissingCardsAsDropped() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L, 2L), java.util.Map.of(), OWNER_KEY));
        when(productService.getCardsByIds(List.of(1L, 2L))).thenReturn(List.of(card(1L, true)));

        RecommendationListResponse response = service.getList(LIST_ID, OWNER);

        assertThat(response.items()).extracting(RecommendedCardResponse::productId)
                .containsExactly(1L);
        assertThat(response.itemsDropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("CH-5 — 만료·미존재 listId는 404")
    void getListNotFound() {
        when(valueOperations.get("chat:list:gone")).thenReturn(null);

        assertThatThrownBy(() -> service.getList("gone", OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("CH-5 — 남의 listId는 403이 아니라 404 (존재 은닉 — 노션 CH-5)")
    void getListHidesOthersList() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L), java.util.Map.of(), OWNER_KEY));

        assertThatThrownBy(() -> service.getList(LIST_ID, ChatIdentity.member(99L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // 회원 7과 게스트 "7"이 같은 소유자로 취급되면 안 된다 — subType까지 비교하는지 고정
    @Test
    @DisplayName("CH-5 — sub가 같아도 sub_type이 다르면 404")
    void getListDistinguishesSubType() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L), java.util.Map.of(), OWNER_KEY));

        assertThatThrownBy(() -> service.getList(LIST_ID, ChatIdentity.guest("7")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("CH-5 — 신원 없는 요청(AT·guest_id 둘 다 없음)은 404")
    void getListRejectsAnonymous() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L), java.util.Map.of(), OWNER_KEY));

        assertThatThrownBy(() -> service.getList(LIST_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("CH-5 — owner가 기록되지 않은 목록은 아무도 못 읽는다 (fail-closed)")
    void getListRejectsOwnerlessList() throws Exception {
        givenStored(new RecommendationListService.StoredList(
                List.of(1L), java.util.Map.of(), null));

        assertThatThrownBy(() -> service.getList(LIST_ID, OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static RecommendationCallbackRequest request(String listType, Integer totalBudget,
                                                         ListEntry... entries) {
        return new RecommendationCallbackRequest(SESSION_ID, REQUEST_ID, listType, totalBudget,
                List.of(entries), null, null, null);
    }

    private static ListEntry entry(String listId, String label, List<Long> productIds,
                                   Reason... reasons) {
        return new ListEntry(listId, label, productIds,
                reasons.length == 0 ? null : List.of(reasons));
    }

    // 캐시는 setIfAbsent가 계약 — 재전송이 내용을 덮거나 TTL을 다시 시작시키면 안 된다(노션 I-21)
    private RecommendationListService.StoredList capturedCache(String listId) throws Exception {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq("chat:list:" + listId), value.capture(),
                eq(Duration.ofMinutes(10)));
        return objectMapper.readValue(value.getValue(), RecommendationListService.StoredList.class);
    }

    // 직렬화를 when() 인자 안에서 하면 @Spy objectMapper 호출이 중첩 스터빙으로 잡힌다 — 먼저 계산해 둔다
    private void givenStored(RecommendationListService.StoredList stored) throws Exception {
        String json = objectMapper.writeValueAsString(stored);
        when(valueOperations.get("chat:list:" + LIST_ID)).thenReturn(json);
    }

    private static ProductCardResponse card(Long id, boolean purchasable) {
        return new ProductCardResponse(id, "상품" + id, "브랜드", 1000, 2000, "img", 0.0, 0, purchasable);
    }
}
