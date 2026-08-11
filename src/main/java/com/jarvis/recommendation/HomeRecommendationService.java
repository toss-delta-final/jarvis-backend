package com.jarvis.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.cart.CartItem;
import com.jarvis.cart.CartItemRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import com.jarvis.product.dto.RecommendedProductsResponse;
import com.jarvis.recommendation.dto.HomeRecommendationRequest;
import com.jarvis.recommendation.dto.HomeRecommendationResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * P-5 홈 개인화 추천 (노션 P-5·I-22) — 내부적으로 I-22를 한 번 호출한다. 채팅(CH-2/I-21)과 달리
 * <b>왕복 1회</b>로 끝난다: Spring이 호출한 주체라 응답에 목록이 담겨 오고 I-21 콜백을 타지 않는다.
 *
 * <p><b>항상 200이다.</b> 프로필 없음·후보 부족·타임아웃·에러 전부 P-4 인기상품으로 대체한다 —
 * FE가 "추천 실패" 화면을 따로 만들 필요가 없게 하는 게 이 API의 계약이다.
 *
 * <p><b>대체분에도 상관키를 발급한다.</b> 목록을 Spring이 만들었으므로 Spring이 발급하며, 없으면
 * FE가 인기상품 카드에 대해 쏘는 노출·클릭 이벤트가 부모 없는 고아가 되어 E-1 검증에서 버려진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeRecommendationService {

    /** 노출 목표 개수 — 홈 영역 카드 수 (노션 P-4와 같은 규모) */
    private static final int LIMIT = 12;
    /** 개인화 결과 캐시 TTL (노션 P-5 2026-07-30 확정). listId 귀속 유효기간 24시간과는 별개다 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String CACHE_KEY_PREFIX = "p5:home:";

    private final HomeRecommendationClient homeRecommendationClient;
    private final RecommendationListStore recommendationListStore;
    private final RecommendationEventRecorder recommendationEventRecorder;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RecommendedProductsResponse recommend(Long memberId) {
        RecommendedProductsResponse cached = readCache(memberId);
        if (cached != null) {
            return cached;
        }
        HomeRecommendationClient.Result result =
                homeRecommendationClient.recommend(buildRequest(memberId));
        if (result.response() != null && result.response().isUsable()) {
            RecommendedProductsResponse personalized = personalized(memberId, result.response());
            // 카드가 전부 드롭되면(HIDDEN·품절) 빈 화면이 되므로 그때도 인기상품으로 간다
            if (!personalized.items().isEmpty()) {
                writeCache(memberId, personalized);
                return personalized;
            }
            // 이벤트에 남는 사유는 "후보 부족"과 같지만 원인이 반대쪽이다 — AI는 개인화에 성공했고
            // 우리가 판매 불가·미등록으로 전부 버린 것이다(재고·상태, 또는 AI 인덱스와의 id 드리프트).
            // 사유 어휘는 노션 P-5가 4종으로 못박아 두어 늘리지 않고, 구분은 이 로그가 진다
            log.warn("P-5 개인화 카드 전량 드롭 — 인기상품 대체 (memberId={}, aiItems={})",
                    memberId, result.response().items().size());
            return fallback(memberId, HomeRecommendationClient.INSUFFICIENT_CANDIDATES);
        }
        // 200인데 개인화가 아닌 응답(NO_PROFILE·후보 부족)은 예외가 아니라 클라이언트도 로그를
        // 남기지 않는다 — 여기서 안 남기면 대체 사유가 behavior_events에만 남아 서버 로그로는
        // "왜 개인화가 안 됐나"를 전혀 알 수 없다. 상관키가 빠진 PERSONALIZED도 이 가지로 온다
        if (result.response() != null) {
            log.info("P-5 개인화 불가 — 인기상품 대체 (memberId={}, outcome={}, aiItems={})",
                    memberId, result.response().outcome(),
                    result.response().items() == null ? 0 : result.response().items().size());
        }
        return fallback(memberId, fallbackReason(result));
    }

    /** outcome 3분기 중 대체 2종은 사유가 정해져 있고, 응답 자체가 없으면 클라이언트가 사유를 준다 */
    private static String fallbackReason(HomeRecommendationClient.Result result) {
        if (result.response() == null) {
            return result.fallbackReason();
        }
        return HomeRecommendationClient.INSUFFICIENT_CANDIDATES.equals(result.response().outcome())
                ? HomeRecommendationClient.INSUFFICIENT_CANDIDATES
                : HomeRecommendationClient.PROFILE_MISSING;
    }

    private HomeRecommendationRequest buildRequest(Long memberId) {
        List<Long> recentlyViewed = capped(
                productRepository.findRecentViewedIdsByOccurredAt(memberId,
                        HomeRecommendationRequest.MAX_SIGNALS));
        List<Long> cart = capped(cartItemRepository.findAllByMemberId(memberId).stream()
                .map(CartItem::getProductId).distinct().toList());
        List<Long> purchased = capped(orderItemRepository.findRecentPurchasedProductIds(memberId,
                HomeRecommendationRequest.MAX_SIGNALS));
        return new HomeRecommendationRequest(memberId, LIMIT,
                new HomeRecommendationRequest.Signals(recentlyViewed, cart, purchased));
    }

    /** 상한 초과는 FastAPI가 400을 내므로 보내기 전에 자른다 — 앞쪽(최신)을 남긴다 (노션 I-22 C-19) */
    private static List<Long> capped(List<Long> ids) {
        return ids.size() <= HomeRecommendationRequest.MAX_SIGNALS
                ? ids
                : ids.subList(0, HomeRecommendationRequest.MAX_SIGNALS);
    }

    private RecommendedProductsResponse personalized(Long memberId,
                                                     HomeRecommendationResponse response) {
        List<Long> productIds = response.items().stream()
                .map(HomeRecommendationResponse.Item::productId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        Map<Long, String> reasons = response.items().stream()
                .filter(item -> item.productId() != null && item.reason() != null)
                .collect(Collectors.toMap(HomeRecommendationResponse.Item::productId,
                        HomeRecommendationResponse.Item::reason, (a, b) -> a));
        // 판매 불가 상품은 카드 조립 단계에서 뺀다 — 재고·상태 반영은 Spring 몫이다(노션 I-22)
        List<ProductCardResponse> cards = capToLimit(
                sellable(productService.getCardsByIds(productIds)));

        save(RecommendationList.ofHome(response.listId(), response.recommendationRequestId(),
                memberId, RecommendationSource.AI_RECOMMENDED, cards.size(), LocalDateTime.now()),
                cards, null);
        // 응답 어휘는 DB(source=AI_RECOMMENDED)와 갈린다 — FE는 "개인화됐나"만 묻는다
        return new RecommendedProductsResponse(RecommendedProductsResponse.PERSONALIZED,
                response.recommendationRequestId(), response.listId(),
                cards.stream().map(card -> RecommendedProductsResponse.Item.of(card,
                        reasons.get(card.productId()))).toList());
    }

    /**
     * P-4 인기상품으로 대체한다. <b>캐시하지 않는다</b> — 노션 P-5가 대체분의 상관키는 요청마다 새로
     * 발급해 귀속이 뭉개지지 않게 하라고 정했다. 대신 AI가 죽어 있으면 홈 진입마다 예산(3s)만큼
     * 기다리게 되는데, 그 상한이 곧 "메인 렌더 블로킹 방지"의 설계값이다.
     */
    private RecommendedProductsResponse fallback(Long memberId, String fallbackReason) {
        List<ProductCardResponse> cards = sellable(productService.getPopularCards(LIMIT));
        String requestId = UUID.randomUUID().toString();
        String listId = UUID.randomUUID().toString().replace("-", "");

        save(RecommendationList.ofHome(listId, requestId, memberId,
                RecommendationSource.POPULAR_FALLBACK, cards.size(), LocalDateTime.now()),
                cards, fallbackReason);
        // 원인 넷(프로필 없음·후보 부족·AI 에러·타임아웃)을 하나로 접는다 — FE 동작이 전부 같다.
        // 원인을 주장하는 이름을 쓰면 나머지 셋에서 거짓말이 되고, FE가 잘못된 분기를 만든다
        return new RecommendedProductsResponse(RecommendedProductsResponse.NOT_PERSONALIZED,
                requestId, listId,
                // 대체분엔 이유가 없다 — 키는 유지하고 null이다(CH-5와 동일 규칙)
                cards.stream().map(card -> RecommendedProductsResponse.Item.of(card, null)).toList());
    }

    /**
     * 노션 I-22 — {@code limit}은 <b>최종 노출 목표 개수</b>다. FastAPI는 우리가 품절을 뺄 것을
     * 예상해 이보다 넉넉히(overfetch 2배) 주고, <b>판매 불가를 뺀 뒤 자르는 건 우리 몫</b>이다.
     * 안 자르면 12개 자리에 24개까지 깔린다. 배열 순서가 곧 순위라 <b>앞에서</b> 남긴다.
     */
    private static List<ProductCardResponse> capToLimit(List<ProductCardResponse> cards) {
        return cards.size() <= LIMIT ? cards : cards.subList(0, LIMIT);
    }

    /** HIDDEN·품절은 홈 카드에서 뺀다 — P-4와 같은 기준이다 */
    private static List<ProductCardResponse> sellable(List<ProductCardResponse> cards) {
        return cards.stream()
                .filter(card -> "AVAILABLE".equals(card.purchaseState()))
                .toList();
    }

    /**
     * 목록 사본 + generated 이벤트. <b>실패해도 추천은 내려간다</b> — 분석 저장 때문에 홈이 죽으면
     * 안 된다.
     *
     * <p><b>[2026-08-10] 실패의 파급이 커졌다</b> — v1에선 사본이 없어도 "그 목록의 노출·클릭
     * 이벤트만 귀속되지 않는" 정도였지만(E-1 ③.5), v2는 이 사본이 <b>귀속의 유일한 근거</b>다.
     * 저장에 실패하면 그 회원에게 나간 추천이 없던 일이 되고, 이후 7일간의 구매가 전부 직접
     * 매출로 떨어진다. 그래도 추천 자체를 막지 않는 판단은 유지한다 — 집계가 화면을 죽이는 것이
     * 더 나쁘다. 대신 이 WARN이 잦아지면 AI 매출 과소집계를 의심할 자리다.
     */
    private void save(RecommendationList list, List<ProductCardResponse> cards,
                      String fallbackReason) {
        try {
            List<RecommendationListItem> items = IntStream.range(0, cards.size())
                    .mapToObj(position -> RecommendationListItem.of(list.getListId(), position,
                            cards.get(position).productId(), list.getCreatedAt()))
                    .toList();
            recommendationListStore.saveAll(List.of(list), items);
            recommendationEventRecorder.recordHomeGenerated(list, fallbackReason);
        } catch (Exception e) {
            log.warn("P-5 목록 저장 실패 — 추천은 그대로 내려간다 (listId={})", list.getListId(), e);
        }
    }

    /**
     * C-27 — 취향이 바뀌면 이 회원의 개인화 홈 캐시를 버린다(노션 M-12·M-13·M-15·M-16).
     * 캐시에 담기는 건 <b>개인화 성공 결과뿐</b>이라(인기상품 대체는 캐시하지 않는다) 남아 있다는 건
     * 곧 <b>바뀌기 전 취향으로 만든 추천이 남아 있다</b>는 뜻이다. 비우지 않으면 사용자가 취향을
     * 지웠는데도 최대 10분간 그 추천이 홈에 뜬다 — "지웠다"고 해놓고 계속 쓰는 셈이다.
     *
     * <p>AI는 자기 데이터만 지울 수 있고 이 키는 Spring 소유라, 무효화는 우리 몫이다.
     * 실패해도 예외를 올리지 않는다 — TTL 10분이 백스톱이고, 캐시 때문에 취향 변경 자체가
     * 실패하면 안 된다.
     */
    public void evictCache(Long memberId) {
        try {
            redisTemplate.delete(CACHE_KEY_PREFIX + memberId);
        } catch (Exception e) {
            log.warn("P-5 캐시 무효화 실패 — 최대 {}분간 이전 추천이 남는다 (memberId={})",
                    CACHE_TTL.toMinutes(), memberId, e);
        }
    }

    private RecommendedProductsResponse readCache(Long memberId) {
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + memberId);
            return cached == null ? null
                    : objectMapper.readValue(cached, RecommendedProductsResponse.class);
        } catch (Exception e) {
            // 캐시는 최적화지 정본이 아니다 — 깨졌으면 그냥 다시 계산한다
            log.warn("P-5 캐시 읽기 실패 — 재계산 (memberId={})", memberId, e);
            return null;
        }
    }

    private void writeCache(Long memberId, RecommendedProductsResponse response) {
        try {
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + memberId,
                    objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (Exception e) {
            log.warn("P-5 캐시 쓰기 실패 — 다음 요청에서 재계산 (memberId={})", memberId, e);
        }
    }
}
