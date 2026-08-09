package com.jarvis.seller;

import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.seller.dto.SellerSummaryResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 추천 경유 매출 집계 (「AI 추천 성과」 2026-08-06 제안 · 2026-08-07 구현) — S-1의 나머지
 * 블록과 트랜잭션을 분리한다.
 *
 * <p>REQUIRES_NEW인 이유: 이 집계가 실패해도 대시보드 나머지는 내려보내야 하는데, 호출부와 같은
 * 트랜잭션 안에서 실패하면 호출부가 예외를 잡아도 소용이 없다. 예외가 트랜잭션 경계를 지나는
 * 순간 rollback-only로 마킹되고, 마킹은 되돌릴 수 없어 커밋 시점에 UnexpectedRollbackException이
 * 난다. 실제로 2026-08-08 S-1 전건 500이 이 경로였다.
 *
 * <p><b>여기서 예외를 잡지 않는다</b> — 잡으면 이 트랜잭션이 스스로 rollback-only로 마킹된 채
 * 커밋에 들어가 같은 문제가 그대로 재현된다. catch는 반드시 트랜잭션 경계 바깥, 즉
 * {@link SellerSalesService} 쪽에 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerAttributionService {

    /** 귀속 창 — 추천 목록이 나간 시각부터 잰다(v2). 집계 시점 기준이라 바꿔서 재계산할 수 있다 */
    private static final int ATTRIBUTION_WINDOW_DAYS = 7;
    /**
     * 귀속 창·판정 근거를 바꾸면 올린다 — 같은 기간 숫자가 달라지므로 비교 기준을 남긴다.
     * v2(2026-08-10): 판정 근거를 행동 이벤트 → 추천 명단으로, 창 기준점을 이벤트 시각 →
     * 추천 시각으로 바꿨다. 같은 기간이라도 v1 숫자와 직접 비교하면 안 된다.
     */
    private static final String ATTRIBUTION_POLICY_VERSION = "v2";

    private final OrderItemRepository orderItemRepository;
    private final BehaviorEventRepository behaviorEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SellerSummaryResponse.AiAttribution aggregate(Long brandId, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        OrderItemRepository.AttributionRow attribution = orderItemRepository
                .aggregateAiAttribution(brandId, fromDt, toDt, ATTRIBUTION_WINDOW_DAYS);
        long confirmed = attribution.getConfirmedSales();
        long estimated = attribution.getEstimatedSales();
        long total = orderItemRepository.sumSellerSales(brandId, fromDt, toDt).getSales();
        long ai = confirmed + estimated;
        // 금액의 권위는 order_item이다 — AI 매출만 산출하고 직접 매출은 총액에서 차감해 만든다.
        // 그래야 합이 총액과 달라질 수 없다. 음수가 나오면 두 산식이 갈렸다는 뜻이라 드러낸다.
        long direct = total - ai;
        if (direct < 0) {
            log.warn("AI 귀속 합계가 총매출을 넘었다 — 산식 불일치 의심 (brandId={}, ai={}, total={})",
                    brandId, ai, total);
            direct = 0;
        }

        BehaviorEventRepository.RecommendationFunnelRow funnel = behaviorEventRepository
                .aggregateRecommendationFunnel(brandId, fromDt, toDt);
        OrderItemRepository.CoverageRow coverage = orderItemRepository
                .aggregateCollectionCoverage(brandId, fromDt, toDt);

        return new SellerSummaryResponse.AiAttribution(
                ATTRIBUTION_POLICY_VERSION, ATTRIBUTION_WINDOW_DAYS,
                total, ai, confirmed, estimated, direct,
                // 분모가 0이면 null — 0%가 아니라 "계산할 모수가 없다"는 뜻이다
                total == 0 ? null : Math.round(ai * 1000.0 / total) / 10.0,
                attribution.getAiOrderCount(),
                new SellerSummaryResponse.AiAttribution.Funnel(
                        zeroIfNull(funnel.getImpression()), zeroIfNull(funnel.getClick()),
                        zeroIfNull(funnel.getAddToCart()),
                        orderItemRepository.countSellerPurchaseOrders(brandId, null, fromDt, toDt)),
                coverage.getPaidOrders() == 0 ? null
                        : Math.round(coverage.getObservedOrders() * 100.0
                                / coverage.getPaidOrders()) / 100.0);
    }

    /** SUM은 대상 행이 없으면 0이 아니라 NULL이다 */
    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}
