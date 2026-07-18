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
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerEventsResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 판매자 분석 콜백 I-7/I-8/I-13/I-14/I-15/I-16 (04 §10, 노션 명세 기준) — 전부 집계·조회 전용.
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
    private static final String CHECKOUT_START = "checkout_start";
    /** I-13 상품 연계 4종 (노션 I-13) — counts 키는 camelCase */
    private static final List<String> I13_EVENT_TYPES =
            List.of("product_view", "add_to_cart", CHECKOUT_START, "purchase_complete");

    private final BehaviorEventRepository behaviorEventRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final AccountEventLogRepository accountEventLogRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
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
        long purchases = orderItemRepository.countSellerPurchaseOrders(brandId, fromDt, toDt);

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

        // checkout_start는 product_id 컬럼이 아닌 properties.productIds 귀속(I-7 3단과 동일 방식)
        List<String> columnTypes = types.stream().filter(t -> !CHECKOUT_START.equals(t)).toList();
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
                    .stream().map(row -> toMemberRow(row, maxPerHour)).toList();
            return new SellerOrderEventsResponse(brandId, period.from(), period.to(),
                    rows, rows.size(), null, null);
        }
        List<SellerOrderEventsResponse.Row> rows = orderStatusLogRepository
                .findSellerOrderEvents(brandId, applyToStatus, toStatuses, actorType, fromDt, toDt,
                        effectiveLimit)
                .stream().map(SellerAnalyticsService::toRow).toList();
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
            return new SellerChurnResponse(brandId, period.from(), period.to(), inactiveDays, 0, 0.0,
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
        double churnRate = round3((double) churnedIds.size() / cohort.size());

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

    private List<SellerChurnResponse.Member> buildChurnMembers(
            Long brandId, List<Long> listed, Map<Long, LocalDateTime> lastActivities) {
        Map<Long, LocalDateTime> lastLogins = accountEventLogRepository.findLastLogins(listed)
                .stream().collect(Collectors.toMap(AccountEventLogRepository.LastLoginRow::getMemberId,
                        AccountEventLogRepository.LastLoginRow::getLastLogin));
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
                .map(id -> new SellerChurnResponse.Member(id,
                        toOffset(lastActivities.get(id)), toOffset(lastLogins.get(id)),
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
        List<SellerEventsResponse.ProductRow> rows = countsByProduct.entrySet().stream()
                .map(e -> toProductRow(e.getKey(), names.get(e.getKey()), e.getValue(), visitors, types))
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
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : types) {
            counts.put(camel(type), CHECKOUT_START.equals(type)
                    ? checkouts.size() // 주문서 1회=1 (02 §4)
                    : raw.getOrDefault(type, 0L));
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
                                                         List<String> types) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String type : types) {
            counts.put(camel(type), rawCounts.getOrDefault(type, 0L));
        }
        Double viewToCartRate = types.contains("product_view") && types.contains("add_to_cart")
                ? fraction(rawCounts.getOrDefault("add_to_cart", 0L),
                        rawCounts.getOrDefault("product_view", 0L))
                : null;
        return new SellerEventsResponse.ProductRow(productId, productName, counts, viewToCartRate,
                visitors.getOrDefault(productId, 0L));
    }

    /** eventType 파라미터 파싱 — 콤마 복수, 4종 외 값은 INVALID_GROUP_BY (노션 I-13) */
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

    private static String camel(String eventType) {
        return switch (eventType) {
            case "product_view" -> "productView";
            case "add_to_cart" -> "addToCart";
            case CHECKOUT_START -> "checkoutStart";
            default -> "purchaseComplete";
        };
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
        return behaviorEventRepository
                .findAllByEventTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        CHECKOUT_START, from, to)
                .stream()
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

    private static SellerOrderEventsResponse.Row toRow(OrderStatusLogRepository.OrderEventRow row) {
        return new SellerOrderEventsResponse.Row(row.getOrderId(), row.getFromStatus(),
                row.getToStatus(), row.getActorType(), row.getReason(), row.getBuyerMemberId(),
                toOffset(row.getCreatedAt()));
    }

    private static SellerOrderEventsResponse.MemberRow toMemberRow(
            OrderStatusLogRepository.MemberAggRow row, Map<Long, Long> maxPerHourByMember) {
        long orderCount = row.getOrderCount();
        long cancelCount = row.getCancelCount();
        double cancelRatio = orderCount == 0 ? 0.0 : round3((double) cancelCount / orderCount);
        long maxPerHour = maxPerHourByMember.getOrDefault(row.getMemberId(), 0L);
        boolean suspicious = cancelRatio > SUSPICIOUS_CANCEL_RATIO
                || maxPerHour > SUSPICIOUS_MAX_ORDERS_PER_HOUR;
        return new SellerOrderEventsResponse.MemberRow(row.getMemberId(), orderCount, cancelCount,
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
