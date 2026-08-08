package com.jarvis.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.cart.CartItemRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.product.dto.RecommendedProductsResponse;
import com.jarvis.recommendation.dto.HomeRecommendationRequest;
import com.jarvis.recommendation.dto.HomeRecommendationResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** P-5 홈 개인화 추천 (노션 P-5·I-22) */
@ExtendWith(MockitoExtension.class)
class HomeRecommendationServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String AI_REQUEST_ID = "a63be350-ec96-4f44-b3f9-c962b6673a68";
    private static final String AI_LIST_ID = "7c1e9f2a4b8d43f5a0c6d1e97b3f8a24";

    @Mock HomeRecommendationClient homeRecommendationClient;
    @Mock RecommendationListStore recommendationListStore;
    @Mock RecommendationEventRecorder recommendationEventRecorder;
    @Mock ProductService productService;
    @Mock ProductRepository productRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    @Captor ArgumentCaptor<List<RecommendationList>> listCaptor;
    @Captor ArgumentCaptor<List<RecommendationListItem>> itemsCaptor;

    @InjectMocks HomeRecommendationService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(any())).thenReturn(null);   // 캐시 미스가 기본
        lenient().when(productRepository.findRecentViewedIdsByOccurredAt(anyLong(), anyInt()))
                .thenReturn(List.of(101L, 205L));
        lenient().when(cartItemRepository.findAllByMemberId(anyLong())).thenReturn(List.of());
        lenient().when(orderItemRepository.findRecentPurchasedProductIds(anyLong(), anyInt()))
                .thenReturn(List.of());
        // ObjectMapper는 목이 아니라 실물 — 캐시 직렬화가 실제로 되는지 봐야 한다
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper",
                new ObjectMapper());
    }

    private static ProductCardResponse card(long id) {
        return new ProductCardResponse(id, "상품" + id, "브랜드", 12000, 15000,
                "https://img/" + id + ".jpg", 4.5, 100L, "AVAILABLE");
    }

    private void aiReturns(String outcome, List<HomeRecommendationResponse.Item> items) {
        when(homeRecommendationClient.recommend(any())).thenReturn(
                new HomeRecommendationClient.Result(
                        new HomeRecommendationResponse(outcome, AI_REQUEST_ID, AI_LIST_ID, items),
                        null));
    }

    @Test
    @DisplayName("P-5 — 개인화 성공이면 source=PERSONALIZED, 상관키는 FastAPI가 준 값")
    void personalizedUsesAiCorrelationKeys() {
        aiReturns(HomeRecommendationResponse.PERSONALIZED,
                List.of(new HomeRecommendationResponse.Item(101L, "최근 선호 가격대에 맞아요")));
        when(productService.getCardsByIds(List.of(101L))).thenReturn(List.of(card(101L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.source()).isEqualTo("PERSONALIZED");
        assertThat(response.recommendationRequestId()).isEqualTo(AI_REQUEST_ID);
        assertThat(response.listId()).isEqualTo(AI_LIST_ID);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).reason()).isEqualTo("최근 선호 가격대에 맞아요");
    }

    // 상관키가 없으면 FE가 인기상품 카드에 대해 쏘는 노출·클릭이 부모 없는 고아가 되어 버려진다
    @Test
    @DisplayName("P-5 — 개인화 불가면 source=NOT_PERSONALIZED, 상관키는 Spring이 새로 발급한다")
    void fallbackStillIssuesCorrelationKeys() {
        aiReturns("NO_PROFILE", List.of());
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L), card(2L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.source()).isEqualTo("NOT_PERSONALIZED");
        assertThat(response.recommendationRequestId()).isNotBlank();
        assertThat(response.listId()).isNotBlank().doesNotContain("-");
        assertThat(response.items()).hasSize(2);
        // 대체분엔 이유가 없다 — 키는 유지하고 null (CH-5와 동일 규칙)
        assertThat(response.items().get(0).reason()).isNull();
        // NO_PROFILE(AI 어휘) → PROFILE_MISSING(우리 사유 어휘) 매핑 — 노션 I-22 outcome 처리표
        verify(recommendationEventRecorder).recordHomeGenerated(any(),
                eq(HomeRecommendationClient.PROFILE_MISSING));
    }

    @Test
    @DisplayName("P-5 — 타임아웃도 인기상품 대체로 200 (FastAPI 실패는 실패 응답이 아니다)")
    void timeoutFallsBack() {
        when(homeRecommendationClient.recommend(any())).thenReturn(
                new HomeRecommendationClient.Result(null, HomeRecommendationClient.AI_TIMEOUT));
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.source()).isEqualTo("NOT_PERSONALIZED");
        verify(recommendationEventRecorder).recordHomeGenerated(any(),
                eq(HomeRecommendationClient.AI_TIMEOUT));
    }

    // 신규 회원이라 대체된 건지 AI가 죽어서 대체된 건지는 서버만 알면 된다 (노션 P-5)
    @Test
    @DisplayName("P-5 — fallbackReason은 와이어에 없고 이벤트에만 실린다")
    void fallbackReasonIsNotOnTheWire() {
        aiReturns("INSUFFICIENT_CANDIDATES", List.of());
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.toString()).doesNotContain("INSUFFICIENT_CANDIDATES");
        verify(recommendationEventRecorder).recordHomeGenerated(any(),
                eq(HomeRecommendationClient.INSUFFICIENT_CANDIDATES));
    }

    @Test
    @DisplayName("P-5 — 목록은 surface=HOME·listType=PICK_ONE으로 저장된다")
    void savesHomeList() {
        aiReturns(HomeRecommendationResponse.PERSONALIZED,
                List.of(new HomeRecommendationResponse.Item(101L, null)));
        when(productService.getCardsByIds(List.of(101L))).thenReturn(List.of(card(101L)));

        service.recommend(MEMBER_ID);

        verify(recommendationListStore).saveAll(listCaptor.capture(), anyList());
        RecommendationList saved = listCaptor.getValue().get(0);
        assertThat(saved.getSurface()).isEqualTo(RecommendationSurface.HOME);
        assertThat(saved.getListType()).isEqualTo(RecommendationListType.PICK_ONE);
        assertThat(saved.getSource()).isEqualTo(RecommendationSource.AI_RECOMMENDED);
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getSessionId()).isNull();   // 홈엔 채팅 세션이 없다
    }

    // 응답은 "개인화됐나"에, DB는 "어디서 왔나"에 답한다 — AI 추천 성과 집계가 DB 값에 의존하므로
    // 응답 어휘를 바꿀 때 DB까지 따라 바꾸면 그 집계가 대체분을 배제하지 못한다
    @Test
    @DisplayName("P-5 — 대체분은 응답 NOT_PERSONALIZED / DB POPULAR_FALLBACK으로 어휘가 갈린다")
    void wireAndDbVocabulariesDiffer() {
        aiReturns("NO_PROFILE", List.of());
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.source()).isEqualTo("NOT_PERSONALIZED");
        verify(recommendationListStore).saveAll(listCaptor.capture(), anyList());
        assertThat(listCaptor.getValue().get(0).getSource())
                .isEqualTo(RecommendationSource.POPULAR_FALLBACK);
    }

    @Test
    @DisplayName("P-5 — 개인화는 캐시하고 대체분은 캐시하지 않는다 (대체분 상관키는 매번 새로)")
    void cachesPersonalizedOnly() {
        aiReturns(HomeRecommendationResponse.PERSONALIZED,
                List.of(new HomeRecommendationResponse.Item(101L, null)));
        when(productService.getCardsByIds(List.of(101L))).thenReturn(List.of(card(101L)));
        service.recommend(MEMBER_ID);
        verify(valueOperations).set(any(), any(), any(java.time.Duration.class));

        aiReturns("NO_PROFILE", List.of());
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));
        service.recommend(MEMBER_ID);

        // 개인화 1회분만 — 대체분은 추가로 쓰지 않는다
        verify(valueOperations).set(any(), any(), any(java.time.Duration.class));
    }

    // 재고·판매상태 반영은 Spring 몫이다(노션 I-22) — 전부 빠지면 빈 화면이 되므로 대체한다
    @Test
    @DisplayName("P-5 — 추천 상품이 전부 판매 불가면 인기상품으로 대체한다")
    void fallsBackWhenAllRecommendedUnavailable() {
        aiReturns(HomeRecommendationResponse.PERSONALIZED,
                List.of(new HomeRecommendationResponse.Item(101L, null)));
        when(productService.getCardsByIds(List.of(101L))).thenReturn(List.of(
                new ProductCardResponse(101L, "숨김상품", "브랜드", 1000, 1000, null, 0, 0, "HIDDEN")));
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.source()).isEqualTo("NOT_PERSONALIZED");
        verify(valueOperations, never()).set(any(), any(), any(java.time.Duration.class));
        // AI는 개인화에 성공했지만 우리가 전부 버린 경우다 — 사유 어휘가 4종뿐이라 "후보 부족"과
        // 같은 값으로 적재된다. 이 겹침은 의도이고, 원인 구분은 서비스 로그가 진다
        verify(recommendationEventRecorder).recordHomeGenerated(any(),
                eq(HomeRecommendationClient.INSUFFICIENT_CANDIDATES));
    }

    // FastAPI는 우리가 품절을 뺄 것을 예상해 넉넉히(overfetch 2배) 준다 — limit만큼 자르는 건
    // 우리 몫이다(노션 I-22). 안 자르면 12개 자리에 24개가 깔린다
    @Test
    @DisplayName("P-5 — 넉넉히 받은 후보를 limit(12)개로 자른다, 순서는 받은 그대로")
    void capsPersonalizedToLimit() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 24).boxed().toList();
        aiReturns(HomeRecommendationResponse.PERSONALIZED,
                ids.stream().map(id -> new HomeRecommendationResponse.Item(id, null)).toList());
        when(productService.getCardsByIds(ids))
                .thenReturn(ids.stream().map(HomeRecommendationServiceTest::card).toList());

        RecommendedProductsResponse response = service.recommend(MEMBER_ID);

        assertThat(response.items()).hasSize(12);
        assertThat(response.items().get(0).productId()).isEqualTo(1L);
        assertThat(response.items().get(11).productId()).isEqualTo(12L);
        // 저장 사본도 자른 뒤 기준이어야 노출·클릭 이벤트의 position이 화면과 어긋나지 않는다
        verify(recommendationListStore).saveAll(listCaptor.capture(), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(12);
        assertThat(listCaptor.getValue().get(0).getItemCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("P-5 signals — recentlyViewed는 occurred_at 기준 최신순으로 뽑는다 (recency decay 입력)")
    void sendsRecentlyViewedInOccurredAtOrder() {
        aiReturns("NO_PROFILE", List.of());
        when(productService.getPopularCards(anyInt())).thenReturn(List.of(card(1L)));

        service.recommend(MEMBER_ID);

        ArgumentCaptor<HomeRecommendationRequest> captor =
                ArgumentCaptor.forClass(HomeRecommendationRequest.class);
        verify(homeRecommendationClient).recommend(captor.capture());
        assertThat(captor.getValue().signals().recentlyViewedProductIds())
                .containsExactly(101L, 205L);
        verify(productRepository).findRecentViewedIdsByOccurredAt(MEMBER_ID,
                HomeRecommendationRequest.MAX_SIGNALS);
    }
}
