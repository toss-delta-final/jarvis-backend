package com.jarvis.seller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEvent;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.AccountEventLogRepository;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductChangeType;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.AccountEventAggregateResponse;
import com.jarvis.seller.dto.BrandAccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerCustomerFeaturesResponse;
import com.jarvis.seller.dto.SellerEventsResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 분석 콜백 I-7/I-8/I-13/I-14/I-15/I-16/I-38 (04 §10, 노션 명세 기준) — 전부 집계·조회 전용.
 * LLM에 raw 개인 데이터를 주지 않는다(I-8 IP 마스킹·집계 전용 — 05 §I-6 원칙 공유).
 * from/to는 전 엔드포인트 필수 — 컨트롤러의 AnalysisPeriod가 INVALID_PERIOD로 사전 검증.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_LIMIT = 500;
    private static final int CHURN_LIST_CAP = 50;
    private static final int IP_BUCKET_LIMIT = 100;
    /** ANALYSIS_CONFIG.abuse.login_fail_burst — 기간 내 IP당 LOGIN_FAIL 이 수 이상이면 suspicious */
    private static final long LOGIN_FAIL_BURST_THRESHOLD = 20;
    /** ANALYSIS_CONFIG.abuse — cancelRatio > 0.5 또는 maxOrdersPerHour > 10이면 suspicious (노션 I-14) */
    private static final double SUSPICIOUS_CANCEL_RATIO = 0.5;
    private static final long SUSPICIOUS_MAX_ORDERS_PER_HOUR = 10;
    private static final int CANCEL_REASON_TOP_LIMIT = 10;
    private static final int RETURN_REASON_TOP_LIMIT = 5;
    private static final int SESSIONS_WINDOW_DAYS = 30;
    /** I-38 최소 모집단 — 미만이면 행을 주지 않는다(과소 표본 세그멘테이션·소집단 재식별 차단) */
    private static final int MIN_COHORT_SIZE = 30;
    /** I-38 행 상한 — 초과분은 활동량 순으로 잘리고 truncated로 알린다. 페이지네이션 없음 */
    private static final int CUSTOMER_ROW_LIMIT = 1000;
    /**
     * I-38 피처 이벤트 — {@code product_id} 컬럼으로 브랜드 귀속이 되는 2종만이다.
     * {@code checkout_start}는 JSON 경로라 따로 세고, {@code purchase_complete}는 상품 귀속이
     * 구조적으로 불가능해 아예 빠졌다(노션 I-38 2026-08-10 — {@code orderCount}로 단일화).
     */
    private static final List<String> CUSTOMER_FEATURE_EVENT_TYPES =
            List.of("product_view", "add_to_cart");
    private static final String CHECKOUT_START = "checkout_start";
    private static final String PURCHASE_COMPLETE = "purchase_complete";
    /**
     * I-13 상품 연계 5종 (노션 I-13 — 2026-08-06 remove_from_cart 편입) — counts 키는 camelCase.
     * 삭제 이벤트도 서버 적재지만 product_id가 채워져 조회·담기와 귀속 경로가 같다.
     */
    private static final List<String> I13_EVENT_TYPES =
            List.of("product_view", "add_to_cart", "remove_from_cart",
                    CHECKOUT_START, PURCHASE_COMPLETE);
    /** 체류시간 이상치 상한 — 세션 30분 무활동 재발급과 정합. BE 고정이라 호출자가 못 바꾼다 */
    private static final int DWELL_CAP_SECONDS = 1800;
    /** page_leave 없이 이벤트 차분으로 냈다는 표기 — 판정 기준이 바뀌면 이 값도 바뀐다 */
    private static final String DWELL_SOURCE_NEXT_EVENT = "next_event";

    private final BehaviorEventRepository behaviorEventRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final AccountEventLogRepository accountEventLogRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final CustomerLabeler customerLabeler;
    private final ObjectMapper objectMapper;

    /** I-7 — 4단 퍼널. 3단은 checkout_start properties.productIds의 자사 상품 포함 여부(Java 판정) */
    public SellerFunnelResponse funnel(Long brandId, AnalysisPeriod period) {
        requireBrand(brandId);
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();

        Map<String, Long> eventCounts = behaviorEventRepository
                .countSellerFunnelEvents(brandId, fromDt, toDt).stream()
                .collect(Collectors.toMap(BehaviorEventRepository.TypeCountRow::getEventType,
                        BehaviorEventRepository.TypeCountRow::getCnt));
        long productViews = eventCounts.getOrDefault("product_view", 0L);
        long cartAdds = eventCounts.getOrDefault("add_to_cart", 0L);
        // 노션 계약: v1(productIds 이전) 적재 구간이면 3단 count null·computable false여야 하나,
        // behavior_events엔 적재 세대 구분이 없고 EventService는 처음부터 properties를 그대로
        // 적재해 왔다 — 전 구간 계산 가능으로 보고 항상 computable true.
        long checkoutStarts = countBrandCheckoutStarts(brandId, null, fromDt, toDt);
        long purchases = orderItemRepository.countSellerPurchaseOrders(brandId, null, fromDt, toDt);

        List<SellerFunnelResponse.Stage> stages = List.of(
                new SellerFunnelResponse.Stage("product_view", productViews, "events", null),
                new SellerFunnelResponse.Stage("add_to_cart", cartAdds, "events", null),
                new SellerFunnelResponse.Stage(CHECKOUT_START, checkoutStarts, "events", true),
                new SellerFunnelResponse.Stage("purchase_complete", purchases, "orders", null));
        SellerFunnelResponse.ConversionRates rates = new SellerFunnelResponse.ConversionRates(
                fraction(cartAdds, productViews), fraction(checkoutStarts, cartAdds),
                fraction(purchases, checkoutStarts), fraction(purchases, productViews));
        return new SellerFunnelResponse(brandId, period.from(), period.to(), stages, rates);
    }

    /** I-8 — 전역 계정 이벤트 집계. groupBy=ip는 무차별 대입 신호 집계, IP는 마스킹해 반환 */
    public AccountEventAggregateResponse accountEvents(String groupBy, String eventType,
                                                       AnalysisPeriod period) {
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();
        String effectiveGroupBy = groupBy == null || groupBy.isBlank() ? "eventType" : groupBy;

        List<?> rows = switch (effectiveGroupBy) {
            case "ip" -> accountEventLogRepository
                    .aggregateByIp(eventType, fromDt, toDt, IP_BUCKET_LIMIT).stream()
                    .map(SellerAnalyticsService::toIpRow).toList();
            case "eventType" -> toBuckets(accountEventLogRepository
                    .countByEventType(eventType, fromDt, toDt));
            case "hour" -> toBuckets(accountEventLogRepository
                    .countByHour(eventType, fromDt, toDt));
            default -> throw new BusinessException(ErrorCode.INVALID_GROUP_BY);
        };
        return new AccountEventAggregateResponse(effectiveGroupBy, eventType, period.from(),
                period.to(), rows);
    }

    /**
     * I-8 자사 코호트 계정 이벤트 집계 (노션 I-8 — 2026-08-06 전역 → 브랜드 스코프 전환).
     *
     * <p>전환 배경: admin 파트가 없어 판매자 abuse 워커가 전역 API를 소비하게 됐고, 그 결과 판매자가
     * 자사와 무관한 플랫폼 전체 신호를 보고 있었다. 코호트는 <b>I-16 churn과 같은 조인을 재사용</b>한다 —
     * 다른 조인을 쓰면 같은 대화에서 두 워커가 모순된 수치를 낸다.
     *
     * <p>코호트가 비면 200 + 빈 rows다(노션 「이상 징후가 없으면 정상 결과」). 미존재 브랜드만 404.
     */
    public BrandAccountEventAggregateResponse brandAccountEvents(Long brandId, String groupBy,
                                                                 String eventType,
                                                                 AnalysisPeriod period) {
        requireBrand(brandId);
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();
        String effectiveGroupBy = groupBy == null || groupBy.isBlank() ? "eventType" : groupBy;
        if (!List.of("ip", "eventType", "hour").contains(effectiveGroupBy)) {
            throw new BusinessException(ErrorCode.INVALID_GROUP_BY);
        }

        List<Long> cohort = behaviorEventRepository.findChurnCohortMemberIds(brandId, fromDt, toDt);
        if (cohort.isEmpty()) {
            return BrandAccountEventAggregateResponse.of(brandId, effectiveGroupBy, eventType,
                    period.from(), period.to(), List.of());
        }

        List<?> rows = switch (effectiveGroupBy) {
            case "ip" -> accountEventLogRepository
                    .aggregateByIpForCohort(cohort, suspiciousMemberIds(brandId, fromDt, toDt),
                            eventType, fromDt, toDt, IP_BUCKET_LIMIT)
                    .stream().map(SellerAnalyticsService::toCohortIpRow).toList();
            case "eventType" -> toBrandBuckets(accountEventLogRepository
                    .countByEventTypeForCohort(cohort, eventType, fromDt, toDt));
            default -> toBrandBuckets(accountEventLogRepository
                    .countByHourForCohort(cohort, eventType, fromDt, toDt));
        };
        return BrandAccountEventAggregateResponse.of(brandId, effectiveGroupBy, eventType,
                period.from(), period.to(), rows);
    }

    /**
     * I-14와 <b>같은 어뷰징 기준</b>으로 자사 주문 이력상 의심 회원을 뽑는다 — 기준이 갈리면
     * 같은 대화에서 I-8의 suspiciousMemberCount와 I-14의 isSuspicious가 서로 다른 답을 한다.
     *
     * @return 비어 있으면 센티널 — MariaDB에서 {@code IN ()}은 문법 오류라 빈 목록을 넘길 수 없다
     */
    private List<Long> suspiciousMemberIds(Long brandId, LocalDateTime from, LocalDateTime to) {
        List<String> noStatusFilter = List.of("__NONE__");
        Map<Long, Long> maxPerHour = orderStatusLogRepository
                .maxSellerOrdersPerHourByMember(brandId, false, noStatusFilter, null, from, to)
                .stream().collect(Collectors.toMap(OrderStatusLogRepository.MemberHourRow::getMemberId,
                        OrderStatusLogRepository.MemberHourRow::getMaxPerHour));
        List<Long> suspicious = orderStatusLogRepository
                .aggregateSellerOrderEventsByMember(brandId, false, noStatusFilter, null, from, to,
                        MAX_LIMIT)
                .stream()
                .filter(row -> {
                    long orders = row.getOrderCount();
                    double cancelRatio = orders == 0 ? 0.0 : (double) row.getCancelCount() / orders;
                    return cancelRatio > SUSPICIOUS_CANCEL_RATIO
                            || maxPerHour.getOrDefault(row.getMemberId(), 0L)
                                    > SUSPICIOUS_MAX_ORDERS_PER_HOUR;
                })
                .map(OrderStatusLogRepository.MemberAggRow::getMemberId)
                .toList();
        return suspicious.isEmpty() ? List.of(-1L) : suspicious;
    }

    private static List<BrandAccountEventAggregateResponse.Bucket> toBrandBuckets(
            List<AccountEventLogRepository.BucketCountRow> rows) {
        return rows.stream()
                .map(row -> new BrandAccountEventAggregateResponse.Bucket(row.getBucket(), row.getCnt()))
                .toList();
    }

    private static BrandAccountEventAggregateResponse.IpRow toCohortIpRow(
            AccountEventLogRepository.CohortIpAggRow row) {
        return new BrandAccountEventAggregateResponse.IpRow(maskIp(row.getIp()),
                row.getDistinctMembers(), row.getSuspiciousMembers(), row.getEventCount(),
                toOffset(row.getFirstSeen()), toOffset(row.getLastSeen()));
    }

    /** I-13 — 자사 행동 이벤트 집계. groupBy=product(기본)|eventType|date (노션 I-13) */
    public SellerEventsResponse events(Long brandId, String eventType, Long productId,
                                       String groupBy, AnalysisPeriod period) {
        requireBrand(brandId);
        String effectiveGroupBy = groupBy == null || groupBy.isBlank() ? "product" : groupBy;
        if (!List.of("product", "eventType", "date").contains(effectiveGroupBy)) {
            throw new BusinessException(ErrorCode.INVALID_GROUP_BY);
        }
        List<String> types = parseEventTypes(eventType);
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();

        // product_id 컬럼으로 집계할 수 있는 건 product_view·add_to_cart뿐이다. checkout_start는
        // properties.productIds JSON 귀속, purchase_complete는 주문(order_item×product×brand) 귀속 —
        // 둘 다 product_id가 비어 있어 컬럼 조인 스코프에서 탈락한다(노션 I-13 2026-07-31 개정).
        List<String> columnTypes = types.stream()
                .filter(t -> !CHECKOUT_START.equals(t) && !PURCHASE_COMPLETE.equals(t)).toList();
        List<BrandCheckout> checkouts = types.contains(CHECKOUT_START)
                ? loadBrandCheckouts(brandId, productId, fromDt, toDt)
                : List.of();

        return switch (effectiveGroupBy) {
            case "eventType" -> eventsByType(brandId, productId, types, columnTypes, checkouts,
                    period, fromDt, toDt);
            case "date" -> eventsByDate(brandId, productId, types, columnTypes, checkouts,
                    period, fromDt, toDt);
            default -> eventsByProduct(brandId, productId, types, columnTypes, checkouts,
                    period, fromDt, toDt);
        };
    }

    /** I-14 — 자사 주문 전이 로그. stats=true는 {byStatus, cancelReasonsTop}, groupBy=memberId는 어뷰징 집계 */
    public SellerOrderEventsResponse orderEvents(Long brandId, String toStatus, String actorType,
                                                 AnalysisPeriod period, boolean stats,
                                                 String groupBy, int limit) {
        requireBrand(brandId);
        // 지원 그룹핑은 memberId 하나뿐 — 어휘 밖 값은 400 (노션 I-14. 2026-08-11 정합 수정:
        // 종전엔 조용히 기본 rows 모드로 넘어가 명세의 400 INVALID_GROUP_BY와 어긋났다)
        if (groupBy != null && !groupBy.isBlank() && !"memberId".equals(groupBy)) {
            throw new BusinessException(ErrorCode.INVALID_GROUP_BY);
        }
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<String> toStatuses = toStatus == null || toStatus.isBlank()
                ? List.of("__NONE__") // 빈 IN 방지 센티널 (searchCandidates와 같은 관성)
                : Arrays.stream(toStatus.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean applyToStatus = toStatus != null && !toStatus.isBlank();

        if (stats) {
            Map<String, Long> byStatus = orderStatusLogRepository
                    .countSellerOrderEventsByStatus(brandId, applyToStatus, toStatuses, actorType,
                            fromDt, toDt)
                    .stream().collect(Collectors.toMap(OrderStatusLogRepository.StatusCountRow::getBucket,
                            OrderStatusLogRepository.StatusCountRow::getCnt, (a, b) -> a,
                            LinkedHashMap::new));
            List<SellerOrderEventsResponse.ReasonCount> reasons = orderStatusLogRepository
                    .findTopCancelReasons(brandId, fromDt, toDt, CANCEL_REASON_TOP_LIMIT).stream()
                    .map(row -> new SellerOrderEventsResponse.ReasonCount(row.getReason(), row.getCnt()))
                    .toList();
            return new SellerOrderEventsResponse(brandId, period.from(), period.to(),
                    null, null, byStatus, reasons);
        }
        if ("memberId".equals(groupBy)) {
            Map<Long, Long> maxPerHour = orderStatusLogRepository
                    .maxSellerOrdersPerHourByMember(brandId, applyToStatus, toStatuses, actorType,
                            fromDt, toDt)
                    .stream().collect(Collectors.toMap(OrderStatusLogRepository.MemberHourRow::getMemberId,
                            OrderStatusLogRepository.MemberHourRow::getMaxPerHour));
            List<SellerOrderEventsResponse.MemberRow> rows = orderStatusLogRepository
                    .aggregateSellerOrderEventsByMember(brandId, applyToStatus, toStatuses, actorType,
                            fromDt, toDt, effectiveLimit)
                    .stream().map(row -> toMemberRow(brandId, row, maxPerHour)).toList();
            return new SellerOrderEventsResponse(brandId, period.from(), period.to(),
                    rows, rows.size(), null, null);
        }
        List<SellerOrderEventsResponse.Row> rows = orderStatusLogRepository
                .findSellerOrderEvents(brandId, applyToStatus, toStatuses, actorType, fromDt, toDt,
                        effectiveLimit)
                .stream().map(row -> toRow(brandId, row)).toList();
        long total = orderStatusLogRepository
                .countSellerOrderEvents(brandId, applyToStatus, toStatuses, actorType, fromDt, toDt);
        return new SellerOrderEventsResponse(brandId, period.from(), period.to(),
                rows, (int) total, null, null);
    }

    /** I-15 — 자사 상품 변경 이력. changeType·productId 필터, rows+total */
    public SellerProductChangesResponse productChanges(Long brandId, String changeType, Long productId,
                                                       AnalysisPeriod period, int limit) {
        requireBrand(brandId);
        if (changeType != null) {
            try {
                ProductChangeType.valueOf(changeType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<SellerProductChangesResponse.Row> rows = productChangeLogRepository
                .findSellerProductChanges(brandId, changeType, productId, fromDt, toDt, effectiveLimit)
                .stream().map(SellerAnalyticsService::toChangeRow).toList();
        long total = productChangeLogRepository
                .countSellerProductChanges(brandId, changeType, productId, fromDt, toDt);
        return new SellerProductChangesResponse(brandId, period.from(), period.to(), rows, total);
    }

    /**
     * I-16 — 이탈 코호트 (노션 I-16). 코호트 = from~to에 자사 상품과 상호작용한 회원,
     * 이탈 = 최근 inactiveDays일 behavior_events 무활동. lastLoginAt 단일 출처 = LOGIN_SUCCESS (02 D32).
     */
    public SellerChurnResponse churn(Long brandId, AnalysisPeriod period, int inactiveDays) {
        requireBrand(brandId);
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();
        List<Long> cohort = behaviorEventRepository.findChurnCohortMemberIds(brandId, fromDt, toDt);
        if (cohort.isEmpty()) {
            // churnRate는 0.0이 아니라 null이다 — 분모가 없다는 뜻이지 "이탈이 없었다"가 아니다.
            // 0으로 내려보내면 LLM이 "이탈 0%"로 보고한다 (노션 I-16).
            return new SellerChurnResponse(brandId, period.from(), period.to(), inactiveDays, 0, null,
                    emptySignals(), List.of());
        }
        Map<Long, LocalDateTime> lastActivities = behaviorEventRepository.findLastActivities(cohort)
                .stream().collect(Collectors.toMap(
                        BehaviorEventRepository.LastActivityRow::getMemberId,
                        BehaviorEventRepository.LastActivityRow::getLastActivity));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(inactiveDays);
        List<Long> churnedIds = cohort.stream()
                .filter(id -> lastActivities.getOrDefault(id, LocalDateTime.MIN).isBefore(cutoff))
                .sorted(Comparator.comparing(id -> lastActivities.getOrDefault(id, LocalDateTime.MIN)))
                .toList();
        Double churnRate = round3((double) churnedIds.size() / cohort.size());

        SellerChurnResponse.PreChurnSignals signals = churnedIds.isEmpty()
                ? emptySignals()
                : new SellerChurnResponse.PreChurnSignals(
                        orderStatusLogRepository.countChurnedMemberCancels(brandId, churnedIds),
                        orderStatusLogRepository
                                .findChurnedMemberReturnReasons(brandId, churnedIds,
                                        RETURN_REASON_TOP_LIMIT)
                                .stream()
                                .map(row -> new SellerChurnResponse.ReasonCount(row.getReason(),
                                        row.getCnt()))
                                .toList(),
                        // search 이벤트 properties에 결과 수가 적재되지 않아(E-1 FE 스키마) 0건 검색
                        // 세션을 판정할 수 없다 — 데이터 한계로 0 고정 (노션 I-16 합의)
                        0,
                        behaviorEventRepository.countPriceIncreaseExposedMembers(brandId, churnedIds));

        List<Long> listed = churnedIds.stream().limit(CHURN_LIST_CAP).toList();
        List<SellerChurnResponse.Member> members = listed.isEmpty()
                ? List.of()
                : buildChurnMembers(brandId, listed, lastActivities);
        return new SellerChurnResponse(brandId, period.from(), period.to(), inactiveDays,
                cohort.size(), churnRate, signals, members);
    }

    /**
     * I-38 — 고객 행동 피처 집계 (노션 I-38, 2026-08-10 확정). AI팀 세그멘테이션(k-means) 입력이라
     * 행 단위로 나가지만, 회원 노출은 I-14·I-16과 같은 HMAC 라벨뿐이고 금액은 구간, 시각은 일 단위다.
     *
     * <p>코호트는 I-16 {@code churn}과 <b>같은 조인</b>을 재사용한다 — 두 워커가 같은 브랜드에서
     * 모순된 모집단을 보면 세그먼트와 이탈률을 연결할 수 없다.
     *
     * <p>표본이 {@link #MIN_COHORT_SIZE}명 미만이면 행을 주지 않는다({@code insufficientCohort}).
     * 과소 표본 세그멘테이션 방지이자 소집단 재식별 차단이며, "고객이 없다"는 뜻이 아니다.
     */
    public SellerCustomerFeaturesResponse customerFeatures(Long brandId, AnalysisPeriod period) {
        requireBrand(brandId);
        LocalDateTime fromDt = period.from().atStartOfDay();
        LocalDateTime toDt = period.to().plusDays(1).atStartOfDay();

        List<Long> cohort = behaviorEventRepository.findChurnCohortMemberIds(brandId, fromDt, toDt);
        if (cohort.size() < MIN_COHORT_SIZE) {
            return new SellerCustomerFeaturesResponse(period.from(), period.to(), cohort.size(),
                    CUSTOMER_ROW_LIMIT, false, true,
                    SellerCustomerFeaturesResponse.AMOUNT_BUCKETS, List.of());
        }

        Map<Long, Map<String, Long>> byMember = new HashMap<>();
        behaviorEventRepository
                .countCustomerEventsByType(brandId, CUSTOMER_FEATURE_EVENT_TYPES, fromDt, toDt)
                .forEach(row -> byMember
                        .computeIfAbsent(row.getMemberId(), k -> new HashMap<>())
                        .put(row.getEventType(), row.getCnt()));
        Map<Long, Long> checkoutStarts = countCheckoutStartsByCustomer(brandId, fromDt, toDt);

        // 활동량 내림차순으로 자른다 — 동률은 memberId로 갈라 같은 질문에 같은 명단이 나오게 한다
        List<Long> listed = cohort.stream()
                .sorted(Comparator
                        .comparingLong((Long id) -> activitySum(byMember.get(id), checkoutStarts, id))
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(CUSTOMER_ROW_LIMIT)
                .toList();

        Map<Long, Long> sessions = behaviorEventRepository
                .countCustomerSessions(listed, fromDt, toDt).stream()
                .collect(Collectors.toMap(BehaviorEventRepository.MemberCntRow::getMemberId,
                        BehaviorEventRepository.MemberCntRow::getCnt));
        Map<Long, BehaviorEventRepository.ActivitySpanRow> spans = behaviorEventRepository
                .findCustomerActivitySpans(brandId, listed, toDt).stream()
                .collect(Collectors.toMap(
                        BehaviorEventRepository.ActivitySpanRow::getMemberId, row -> row));
        Map<Long, OrderItemRepository.CustomerOrderRow> orders = orderItemRepository
                .sumSellerOrdersByCustomer(brandId, listed, fromDt, toDt).stream()
                .collect(Collectors.toMap(
                        OrderItemRepository.CustomerOrderRow::getMemberId, row -> row));
        Map<Long, Long> cancels = orderStatusLogRepository
                .countCancelsByCustomer(brandId, listed, fromDt, toDt).stream()
                .collect(Collectors.toMap(OrderStatusLogRepository.MemberCancelRow::getMemberId,
                        OrderStatusLogRepository.MemberCancelRow::getCnt));

        List<SellerCustomerFeaturesResponse.Row> rows = listed.stream()
                .map(id -> toCustomerRow(brandId, id, period.to(), byMember.get(id), checkoutStarts,
                        sessions, spans, orders, cancels))
                .toList();
        return new SellerCustomerFeaturesResponse(period.from(), period.to(), cohort.size(),
                CUSTOMER_ROW_LIMIT, cohort.size() > CUSTOMER_ROW_LIMIT, false,
                SellerCustomerFeaturesResponse.AMOUNT_BUCKETS, rows);
    }

    private SellerCustomerFeaturesResponse.Row toCustomerRow(
            Long brandId, Long memberId, LocalDate to, Map<String, Long> counts,
            Map<Long, Long> checkoutStarts, Map<Long, Long> sessions,
            Map<Long, BehaviorEventRepository.ActivitySpanRow> spans,
            Map<Long, OrderItemRepository.CustomerOrderRow> orders, Map<Long, Long> cancels) {
        Map<String, Long> safeCounts = counts == null ? Map.of() : counts;
        OrderItemRepository.CustomerOrderRow order = orders.get(memberId);
        BehaviorEventRepository.ActivitySpanRow span = spans.get(memberId);
        return new SellerCustomerFeaturesResponse.Row(
                customerLabeler.label(brandId, memberId),
                sessions.getOrDefault(memberId, 0L),
                safeCounts.getOrDefault("product_view", 0L),
                safeCounts.getOrDefault("add_to_cart", 0L),
                checkoutStarts.getOrDefault(memberId, 0L),
                order == null ? 0L : order.getOrderCount(),
                cancels.getOrDefault(memberId, 0L),
                amountBucket(order == null ? 0L : order.getAmount()),
                daysAgo(span == null ? null : span.getLastActivity(), to),
                daysAgo(span == null ? null : span.getFirstSeen(), to));
    }

    /** 정렬 기준 — 이벤트 3종 합. 주문·금액은 넣지 않는다(단위가 달라 합산에 의미가 없다) */
    private static long activitySum(Map<String, Long> counts, Map<Long, Long> checkoutStarts,
                                    Long memberId) {
        long events = counts == null ? 0L
                : counts.values().stream().mapToLong(Long::longValue).sum();
        return events + checkoutStarts.getOrDefault(memberId, 0L);
    }

    private Map<Long, Long> countCheckoutStartsByCustomer(Long brandId, LocalDateTime from,
                                                          LocalDateTime to) {
        Set<Long> targetIds = productRepository.findAllByBrandId(brandId).stream()
                .map(Product::getId)
                .collect(Collectors.toSet());
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        String targetIdsJson = targetIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        Map<Long, Long> counts = new HashMap<>();
        behaviorEventRepository.findCustomerCheckouts(targetIdsJson, from, to).stream()
                .filter(row -> !matchedProductIds(row.getProperties(), targetIds).isEmpty())
                .forEach(row -> counts.merge(row.getMemberId(), 1L, Long::sum));
        return counts;
    }

    /** 원값 대신 구간 — 경계는 응답 amountBuckets가 정본이라 여기 순서와 어긋나면 안 된다 */
    private static String amountBucket(long amount) {
        if (amount <= 0) {
            return "ZERO";
        }
        if (amount < 10_000) {
            return "LT_10K";
        }
        if (amount < 50_000) {
            return "10K_50K";
        }
        if (amount < 100_000) {
            return "50K_100K";
        }
        if (amount < 300_000) {
            return "100K_300K";
        }
        return "GTE_300K";
    }

    /** to 기준 일 단위 절사 — 정밀 시각은 어떤 필드로도 내려가지 않는다 */
    private static long daysAgo(LocalDateTime at, LocalDate to) {
        if (at == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.DAYS.between(at.toLocalDate(), to));
    }

    private List<SellerChurnResponse.Member> buildChurnMembers(
            Long brandId, List<Long> listed, Map<Long, LocalDateTime> lastActivities) {
        Map<Long, Long> sessions = behaviorEventRepository
                .countRecentSessions(listed, LocalDateTime.now().minusDays(SESSIONS_WINDOW_DAYS))
                .stream().collect(Collectors.toMap(BehaviorEventRepository.MemberCntRow::getMemberId,
                        BehaviorEventRepository.MemberCntRow::getCnt));
        // preChurnEvent — 클레임 있으면 "RETURNED(상품불량)" 형식(최신 1건), 없으면 마지막 행동 이벤트 타입
        Map<Long, String> claims = new HashMap<>();
        orderStatusLogRepository.findChurnedMemberClaims(brandId, listed)
                .forEach(row -> claims.putIfAbsent(row.getMemberId(), row.getReason() == null
                        ? row.getToStatus()
                        : row.getToStatus() + "(" + row.getReason() + ")"));
        Map<Long, String> lastEvents = new HashMap<>();
        behaviorEventRepository.findLastEventTypes(listed)
                .forEach(row -> lastEvents.putIfAbsent(row.getMemberId(), row.getEventType()));
        return listed.stream()
                .map(id -> new SellerChurnResponse.Member(customerLabeler.label(brandId, id),
                        toOffset(lastActivities.get(id)),
                        sessions.getOrDefault(id, 0L),
                        claims.getOrDefault(id, lastEvents.get(id))))
                .toList();
    }

    // --- I-13 groupBy별 조립 ---

    private SellerEventsResponse eventsByProduct(Long brandId, Long productId, List<String> types,
                                                 List<String> columnTypes, List<BrandCheckout> checkouts,
                                                 AnalysisPeriod period,
                                                 LocalDateTime fromDt, LocalDateTime toDt) {
        Map<Long, Map<String, Long>> countsByProduct = new HashMap<>();
        if (!columnTypes.isEmpty()) {
            behaviorEventRepository
                    .countSellerEventsByProductType(brandId, columnTypes, productId, fromDt, toDt)
                    .forEach(row -> countsByProduct
                            .computeIfAbsent(row.getProductId(), k -> new HashMap<>())
                            .put(row.getEventType(), row.getCnt()));
        }
        checkouts.forEach(c -> c.matchedProductIds().forEach(pid -> countsByProduct
                .computeIfAbsent(pid, k -> new HashMap<>())
                .merge(CHECKOUT_START, 1L, Long::sum)));
        // 구매만 있고 조회·담기 이벤트가 없는 상품도 여기서 rows에 처음 등장한다
        if (types.contains(PURCHASE_COMPLETE)) {
            orderItemRepository
                    .countSellerPurchaseOrdersByProduct(brandId, productId, fromDt, toDt)
                    .forEach(row -> countsByProduct
                            .computeIfAbsent(row.getProductId(), k -> new HashMap<>())
                            .put(PURCHASE_COMPLETE, row.getCnt()));
        }
        // uniqueVisitors는 product_id 컬럼 기반 이벤트만 distinct — JSON 귀속(checkout_start) 제외 근사
        Map<Long, Long> visitors = columnTypes.isEmpty() ? Map.of()
                : behaviorEventRepository
                        .countSellerVisitorsByProduct(brandId, columnTypes, productId, fromDt, toDt)
                        .stream()
                        .collect(Collectors.toMap(
                                BehaviorEventRepository.ProductVisitorRow::getProductId,
                                BehaviorEventRepository.ProductVisitorRow::getVisitors));
        Map<Long, String> names = productRepository.findAllByBrandId(brandId).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));
        // 판매 수량은 매출 계열이라 I-6·S-1과 같은 산식을 쓴다 — 화면 간 숫자가 갈리면 안 된다
        Map<Long, Long> salesQuantities = types.contains(PURCHASE_COMPLETE)
                ? orderItemRepository.sumSellerSalesByProduct(brandId, fromDt, toDt).stream()
                        .collect(Collectors.toMap(
                                OrderItemRepository.ProductQuantityRow::getProductId,
                                OrderItemRepository.ProductQuantityRow::getQuantity))
                : Map.of();
        Map<Long, DwellStats> dwellByProduct = dwellByProduct(brandId, productId, types, fromDt, toDt);
        List<SellerEventsResponse.ProductRow> rows = countsByProduct.entrySet().stream()
                .map(e -> toProductRow(e.getKey(), names.get(e.getKey()), e.getValue(), visitors,
                        salesQuantities, dwellByProduct, types))
                .sorted(Comparator
                        .comparingLong((SellerEventsResponse.ProductRow r) -> r.counts().values()
                                .stream().mapToLong(Long::longValue).sum())
                        .reversed()
                        .thenComparing(SellerEventsResponse.ProductRow::productId))
                .toList();
        return SellerEventsResponse.ofProduct(brandId, period.from(), period.to(), rows);
    }

    private SellerEventsResponse eventsByType(Long brandId, Long productId, List<String> types,
                                              List<String> columnTypes, List<BrandCheckout> checkouts,
                                              AnalysisPeriod period,
                                              LocalDateTime fromDt, LocalDateTime toDt) {
        Map<String, Long> raw = columnTypes.isEmpty() ? Map.of()
                : behaviorEventRepository
                        .countSellerEventsByType(brandId, columnTypes, productId, fromDt, toDt)
                        .stream()
                        .collect(Collectors.toMap(BehaviorEventRepository.TypeCountRow::getEventType,
                                BehaviorEventRepository.TypeCountRow::getCnt));
        // I-7 purchase 단과 같은 쿼리 — 두 API 수치가 어긋나면 워커가 교차 조회에서 혼동한다
        long purchases = types.contains(PURCHASE_COMPLETE)
                ? orderItemRepository.countSellerPurchaseOrders(brandId, productId, fromDt, toDt)
                : 0L;
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : types) {
            counts.put(camel(type), switch (type) {
                case CHECKOUT_START -> (long) checkouts.size(); // 주문서 1회=1 (02 §4)
                case PURCHASE_COMPLETE -> purchases;
                default -> raw.getOrDefault(type, 0L);
            });
        }
        return SellerEventsResponse.ofEventType(brandId, period.from(), period.to(), counts);
    }

    private SellerEventsResponse eventsByDate(Long brandId, Long productId, List<String> types,
                                              List<String> columnTypes, List<BrandCheckout> checkouts,
                                              AnalysisPeriod period,
                                              LocalDateTime fromDt, LocalDateTime toDt) {
        Map<String, Map<String, Long>> byDay = new HashMap<>();
        if (!columnTypes.isEmpty()) {
            behaviorEventRepository
                    .countSellerEventsByDateType(brandId, columnTypes, productId, fromDt, toDt)
                    .forEach(row -> byDay.computeIfAbsent(row.getDay(), k -> new HashMap<>())
                            .put(row.getEventType(), row.getCnt()));
        }
        checkouts.forEach(c -> byDay
                .computeIfAbsent(c.createdAt().toLocalDate().toString(), k -> new HashMap<>())
                .merge(CHECKOUT_START, 1L, Long::sum));
        // 구매의 일자 기준은 orders.paid_at — 다른 3종(behavior_events.created_at)과 다르다
        if (types.contains(PURCHASE_COMPLETE)) {
            orderItemRepository.countSellerPurchaseOrdersByDate(brandId, productId, fromDt, toDt)
                    .forEach(row -> byDay.computeIfAbsent(row.getDay(), k -> new HashMap<>())
                            .put(PURCHASE_COMPLETE, row.getCnt()));
        }
        // 빈 일자 0 채움 — I-6 시계열과 같은 관성
        List<Map<String, Object>> series = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            Map<String, Long> dayCounts = byDay.getOrDefault(d.toString(), Map.of());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d.toString());
            for (String type : types) {
                point.put(camel(type), dayCounts.getOrDefault(type, 0L));
            }
            series.add(point);
        }
        return SellerEventsResponse.ofDate(brandId, period.from(), period.to(), series);
    }

    private SellerEventsResponse.ProductRow toProductRow(Long productId, String productName,
                                                         Map<String, Long> rawCounts,
                                                         Map<Long, Long> visitors,
                                                         Map<Long, Long> salesQuantities,
                                                         Map<Long, DwellStats> dwellByProduct,
                                                         List<String> types) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : types) {
            counts.put(camel(type), rawCounts.getOrDefault(type, 0L));
        }
        Double viewToCartRate = types.contains("product_view") && types.contains("add_to_cart")
                ? fraction(rawCounts.getOrDefault("add_to_cart", 0L),
                        rawCounts.getOrDefault("product_view", 0L))
                : null;
        // 0은 "안 팔림", null은 "미조회" — eventType 필터에 purchase_complete가 없으면 후자다
        Long salesQuantity = types.contains(PURCHASE_COMPLETE)
                ? salesQuantities.getOrDefault(productId, 0L)
                : null;
        DwellStats dwell = dwellByProduct.get(productId);
        return new SellerEventsResponse.ProductRow(productId, productName, counts, salesQuantity,
                dwell == null ? null : dwell.median(),
                dwell == null ? null : dwell.average(),
                dwell == null ? null : dwell.sampleCount(),
                dwell == null ? null : DWELL_SOURCE_NEXT_EVENT,
                viewToCartRate, visitors.getOrDefault(productId, 0L));
    }

    /** 표본이 0이면 행 자체를 만들지 않는다 — 4필드가 통째로 null이어야 하기 때문(노션 I-13) */
    private record DwellStats(Double median, Double average, Long sampleCount) {

        static DwellStats of(List<Long> samples) {
            List<Long> sorted = samples.stream().sorted().toList();
            int size = sorted.size();
            double median = size % 2 == 1
                    ? sorted.get(size / 2)
                    : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
            double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
            return new DwellStats(round1(median), round1(average), (long) size);
        }

        private static double round1(double value) {
            return Math.round(value * 10) / 10.0;
        }
    }

    /**
     * 체류시간 — {@code product_view}가 조회 대상이 아니면 계산 자체를 하지 않는다(4필드 null).
     * 표본은 이상치를 거른 뒤 상품별로 접는다.
     */
    private Map<Long, DwellStats> dwellByProduct(Long brandId, Long productId, List<String> types,
                                                 LocalDateTime fromDt, LocalDateTime toDt) {
        if (!types.contains("product_view")) {
            return Map.of();
        }
        Map<Long, List<Long>> samples = behaviorEventRepository
                .findDwellSamples(brandId, productId, fromDt, toDt, DWELL_CAP_SECONDS).stream()
                .collect(Collectors.groupingBy(BehaviorEventRepository.DwellSampleRow::getProductId,
                        Collectors.mapping(BehaviorEventRepository.DwellSampleRow::getDwellSeconds,
                                Collectors.toList())));
        return samples.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> DwellStats.of(e.getValue())));
    }

    /** eventType 파라미터 파싱 — 콤마 복수, 5종 외 값은 INVALID_GROUP_BY (노션 I-13) */
    private static List<String> parseEventTypes(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return I13_EVENT_TYPES;
        }
        Set<String> requested = Arrays.stream(eventType.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (requested.isEmpty() || !I13_EVENT_TYPES.containsAll(requested)) {
            throw new BusinessException(ErrorCode.INVALID_GROUP_BY);
        }
        return I13_EVENT_TYPES.stream().filter(requested::contains).toList();
    }

    /**
     * counts 키는 event_type의 camelCase다(노션 I-13). 구현은 <b>일반 변환</b>이다 — 구 switch는
     * {@code default -> "purchaseComplete"}라, 2026-08-06에 {@code remove_from_cart}를 편입하자
     * 그 카운트가 조용히 purchaseComplete에 합산됐다. 타입이 늘 때마다 case를 잊으면 같은 사고가
     * 반복되므로 매핑 표를 없앴다.
     */
    private static String camel(String eventType) {
        StringBuilder camel = new StringBuilder(eventType.length());
        boolean upperNext = false;
        for (char c : eventType.toCharArray()) {
            if (c == '_') {
                upperNext = true;
                continue;
            }
            camel.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return camel.toString();
    }

    /** checkout_start 이벤트 중 자사(또는 지정 상품) 귀속분 — 매칭 상품 집합 포함 */
    private record BrandCheckout(LocalDateTime createdAt, Set<Long> matchedProductIds) {
    }

    private List<BrandCheckout> loadBrandCheckouts(Long brandId, Long productId,
                                                   LocalDateTime from, LocalDateTime to) {
        Set<Long> targetIds = productRepository.findAllByBrandId(brandId).stream()
                .map(Product::getId)
                .filter(id -> productId == null || id.equals(productId))
                .collect(Collectors.toSet());
        if (targetIds.isEmpty()) {
            return List.of();
        }
        // 브랜드 필터는 SQL(JSON_OVERLAPS)에서 끝내고, 살아남은 행만 파싱해 매칭 id를 뽑는다
        String targetIdsJson = targetIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        return behaviorEventRepository.findBrandCheckouts(targetIdsJson, from, to).stream()
                .map(e -> new BrandCheckout(e.getCreatedAt(),
                        matchedProductIds(e.getProperties(), targetIds)))
                .filter(c -> !c.matchedProductIds().isEmpty())
                .toList();
    }

    /** 3단 판정 — checkout_start의 properties.productIds ∩ 자사 상품 (02 §4: 주문서 1회=1건) */
    private long countBrandCheckoutStarts(Long brandId, Long productId,
                                          LocalDateTime from, LocalDateTime to) {
        return loadBrandCheckouts(brandId, productId, from, to).size();
    }

    private Set<Long> matchedProductIds(String properties, Set<Long> targetIds) {
        if (properties == null) {
            return Set.of();
        }
        try {
            JsonNode ids = objectMapper.readTree(properties).path("productIds");
            if (!ids.isArray()) {
                return Set.of();
            }
            Set<Long> matched = new HashSet<>();
            for (JsonNode id : ids) {
                if (id.canConvertToLong() && targetIds.contains(id.asLong())) {
                    matched.add(id.asLong());
                }
            }
            return matched;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return Set.of();
        }
    }

    private void requireBrand(Long brandId) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
    }

    /** IPv4는 마지막 두 옥텟(211.234.xx.xx — 노션 I-8), IPv6는 프리픽스 2그룹 외 마스킹 — raw IP 미반환 */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.contains(":")) {
            String[] groups = ip.split(":");
            return groups.length >= 2 ? groups[0] + ":" + groups[1] + "::xxxx" : "xxxx";
        }
        String[] octets = ip.split("\\.");
        return octets.length == 4 ? octets[0] + "." + octets[1] + ".xx.xx" : "xxxx";
    }

    private static List<AccountEventAggregateResponse.Bucket> toBuckets(
            List<AccountEventLogRepository.BucketCountRow> rows) {
        return rows.stream()
                .map(row -> new AccountEventAggregateResponse.Bucket(row.getBucket(), row.getCnt()))
                .toList();
    }

    private static AccountEventAggregateResponse.IpRow toIpRow(
            AccountEventLogRepository.IpAggRow row) {
        double nullMemberRatio = row.getTotalCnt() == 0 ? 0.0
                : round3((double) row.getNullMemberCnt() / row.getTotalCnt());
        return new AccountEventAggregateResponse.IpRow(maskIp(row.getIp()), row.getFailCount(),
                row.getDistinctMembers(), nullMemberRatio,
                row.getFailCount() >= LOGIN_FAIL_BURST_THRESHOLD,
                toOffset(row.getFirstSeen()), toOffset(row.getLastSeen()));
    }

    /** 라벨이 브랜드별로 달라야 해서(대조 추적 차단) brandId가 필요하다 — 그래서 static이 아니다 */
    private SellerOrderEventsResponse.Row toRow(Long brandId,
                                                OrderStatusLogRepository.OrderEventRow row) {
        return new SellerOrderEventsResponse.Row(row.getOrderId(), row.getOrderItemId(),
                row.getFromStatus(), row.getToStatus(), row.getActorType(), row.getReason(),
                customerLabeler.label(brandId, row.getBuyerMemberId()), toOffset(row.getCreatedAt()));
    }

    private SellerOrderEventsResponse.MemberRow toMemberRow(
            Long brandId, OrderStatusLogRepository.MemberAggRow row,
            Map<Long, Long> maxPerHourByMember) {
        long orderCount = row.getOrderCount();
        long cancelCount = row.getCancelCount();
        double cancelRatio = orderCount == 0 ? 0.0 : round3((double) cancelCount / orderCount);
        long maxPerHour = maxPerHourByMember.getOrDefault(row.getMemberId(), 0L);
        boolean suspicious = cancelRatio > SUSPICIOUS_CANCEL_RATIO
                || maxPerHour > SUSPICIOUS_MAX_ORDERS_PER_HOUR;
        return new SellerOrderEventsResponse.MemberRow(
                customerLabeler.label(brandId, row.getMemberId()), orderCount, cancelCount,
                cancelRatio, maxPerHour, suspicious);
    }

    private static SellerProductChangesResponse.Row toChangeRow(
            ProductChangeLogRepository.ChangeRow row) {
        return new SellerProductChangesResponse.Row(row.getProductId(), row.getProductName(),
                row.getChangeType(), row.getOldValue(), row.getNewValue(),
                toOffset(row.getCreatedAt()));
    }

    private static SellerChurnResponse.PreChurnSignals emptySignals() {
        return new SellerChurnResponse.PreChurnSignals(0, List.of(), 0, 0);
    }

    private static OffsetDateTime toOffset(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZONE).toOffsetDateTime();
    }

    /** 소수 전환율 — 분모 0이거나 재료 없으면 null (노션 I-7/I-13) */
    private static Double fraction(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return round3((double) numerator / denominator);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
