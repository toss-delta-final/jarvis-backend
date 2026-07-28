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
    private static final ChatIdentity OWNER = ChatIdentity.member(7L);
    private static final String OWNER_KEY = "member:7";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock ProductService productService;
    @Mock ChatProperties chatProperties;
    @Mock ChatSessionService chatSessionService;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

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
        service.store(SESSION_ID, LIST_ID, List.of(3L, 1L, 2L),
                List.of(new RecommendationCallbackRequest.Reason(1L, "방수 등급이 높아요")));

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("chat:list:" + LIST_ID), value.capture(),
                eq(Duration.ofMinutes(10)));
        RecommendationListService.StoredList stored =
                objectMapper.readValue(value.getValue(), RecommendationListService.StoredList.class);
        assertThat(stored.productIds()).containsExactly(3L, 1L, 2L);
        assertThat(stored.reasons()).containsEntry(1L, "방수 등급이 높아요");
        assertThat(stored.owner()).isEqualTo(OWNER_KEY);
    }

    @Test
    @DisplayName("I-21 — 세션이 이미 사라졌으면 owner 없이 저장(200 유지) — 그 목록은 CH-5가 못 읽는다")
    void storeWithoutSessionLeavesNoOwner() throws Exception {
        when(chatSessionService.findIdentity(SESSION_ID)).thenReturn(java.util.Optional.empty());

        service.store(SESSION_ID, LIST_ID, List.of(1L), null);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("chat:list:" + LIST_ID), value.capture(),
                eq(Duration.ofMinutes(10)));
        assertThat(objectMapper.readValue(value.getValue(),
                RecommendationListService.StoredList.class).owner()).isNull();
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
        givenStored(new RecommendationListService.StoredList(
                List.of(3L, 1L, 2L), java.util.Map.of(3L, "가성비가 좋아요"), OWNER_KEY));
        when(productService.getCardsByIds(List.of(3L, 1L, 2L))).thenReturn(List.of(
                card(3L, true), card(1L, false), card(2L, true)));

        RecommendationListResponse response = service.getList(LIST_ID, OWNER);

        assertThat(response.items()).extracting(RecommendedCardResponse::productId)
                .containsExactly(3L, 2L);
        assertThat(response.items().get(0).reason()).isEqualTo("가성비가 좋아요");
        assertThat(response.items().get(1).reason()).isNull();
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

    // 직렬화를 when() 인자 안에서 하면 @Spy objectMapper 호출이 중첩 스터빙으로 잡힌다 — 먼저 계산해 둔다
    private void givenStored(RecommendationListService.StoredList stored) throws Exception {
        String json = objectMapper.writeValueAsString(stored);
        when(valueOperations.get("chat:list:" + LIST_ID)).thenReturn(json);
    }

    private static ProductCardResponse card(Long id, boolean purchasable) {
        return new ProductCardResponse(id, "상품" + id, "브랜드", 1000, 2000, "img", 0.0, 0, purchasable);
    }
}
