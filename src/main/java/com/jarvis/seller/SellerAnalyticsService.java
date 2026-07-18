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
import com.jarvis.order.OrderStatusLog;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductChangeLog;
import com.jarvis.product.ProductChangeLogRepository;
import com.jarvis.product.ProductChangeType;
import com.jarvis.product.ProductRepository;
import com.jarvis.seller.dto.AccountEventAggregateResponse;
import com.jarvis.seller.dto.SellerChurnResponse;
import com.jarvis.seller.dto.SellerFunnelResponse;
import com.jarvis.seller.dto.SellerOrderEventsResponse;
import com.jarvis.seller.dto.SellerProductChangesResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 분석 콜백 I-7/I-8/I-14/I-15/I-16 (04 §10) — 전부 집계·조회 전용.
 * LLM에 raw 개인 데이터를 주지 않는다(I-8 IP 마스킹·집계 전용 — 05 §I-6 원칙 공유).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_PERIOD_DAYS = 30;
    private static final int MAX_LIMIT = 500;
    private static final int CHURN_LIST_CAP = 50;
    private static final int IP_BUCKET_LIMIT = 100;

    private final BehaviorEventRepository behaviorEventRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final ProductChangeLogRepository productChangeLogRepository;
    private final AccountEventLogRepository accountEventLogRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final ObjectMapper objectMapper;

    /** I-7 — 4단 퍼널. 3단은 checkout_start properties.productIds의 자사 상품 포함 여부(Java 판정) */
    public SellerFunnelResponse funnel(Long brandId, LocalDate from, LocalDate to) {
        requireBrand(brandId);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        LocalDateTime fromDt = effectiveFrom.atStartOfDay();
        LocalDateTime toDt = effectiveTo.plusDays(1).atStartOfDay();

        Map<String, Long> eventCounts = behaviorEventRepository
                .countSellerFunnelEvents(brandId, fromDt, toDt).stream()
                .collect(Collectors.toMap(BehaviorEventRepository.TypeCountRow::getEventType,
                        BehaviorEventRepository.TypeCountRow::getCnt));
        long checkoutStarts = countBrandCheckoutStarts(brandId, fromDt, toDt);
        long purchases = orderItemRepository.countSellerPurchaseOrders(brandId, fromDt, toDt);

        List<SellerFunnelResponse.Stage> stages = new ArrayList<>();
        long[] counts = {eventCounts.getOrDefault("product_view", 0L),
                eventCounts.getOrDefault("add_to_cart", 0L), checkoutStarts, purchases};
        String[] names = {"product_view", "add_to_cart", "checkout_start", "purchase"};
        for (int i = 0; i < counts.length; i++) {
            Double rate = i > 0 && counts[i - 1] > 0
                    ? Math.round(counts[i] * 1000.0 / counts[i - 1]) / 10.0
                    : null;
            stages.add(new SellerFunnelResponse.Stage(names[i], counts[i], rate));
        }
        return new SellerFunnelResponse(brandId, effectiveFrom, effectiveTo, stages);
    }

    /** I-8 — 전역 계정 이벤트 집계. groupBy ip|eventType|hour, IP는 마스킹해 반환 */
    public AccountEventAggregateResponse accountEvents(String groupBy, String eventType,
                                                       LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        LocalDateTime fromDt = effectiveFrom.atStartOfDay();
        LocalDateTime toDt = effectiveTo.plusDays(1).atStartOfDay();
        String effectiveGroupBy = groupBy == null ? "eventType" : groupBy;

        List<AccountEventLogRepository.BucketCountRow> rows = switch (effectiveGroupBy) {
            case "ip" -> accountEventLogRepository.countByIp(eventType, fromDt, toDt, IP_BUCKET_LIMIT);
            case "eventType" -> accountEventLogRepository.countByEventType(eventType, fromDt, toDt);
            case "hour" -> accountEventLogRepository.countByHour(eventType, fromDt, toDt);
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        };
        List<AccountEventAggregateResponse.Bucket> buckets = rows.stream()
                .map(row -> new AccountEventAggregateResponse.Bucket(
                        "ip".equals(effectiveGroupBy) ? maskIp(row.getBucket()) : row.getBucket(),
                        row.getCnt()))
                .toList();
        return new AccountEventAggregateResponse(effectiveGroupBy, eventType, effectiveFrom,
                effectiveTo, buckets);
    }

    /** I-14 — 자사 주문 전이 로그. toStatus 복수(콤마), stats·groupBy=memberId 옵션 */
    public SellerOrderEventsResponse orderEvents(Long brandId, String toStatus, String actorType,
                                                 LocalDate from, LocalDate to, boolean stats,
                                                 String groupBy, int limit) {
        requireBrand(brandId);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        LocalDateTime fromDt = effectiveFrom.atStartOfDay();
        LocalDateTime toDt = effectiveTo.plusDays(1).atStartOfDay();
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<String> toStatuses = toStatus == null || toStatus.isBlank()
                ? List.of("__NONE__") // 빈 IN 방지 센티널 (searchCandidates와 같은 관성)
                : Arrays.stream(toStatus.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean applyToStatus = toStatus != null && !toStatus.isBlank();

        List<SellerOrderEventsResponse.Item> items = orderStatusLogRepository
                .findSellerOrderEvents(brandId, applyToStatus, toStatuses, actorType, fromDt, toDt,
                        effectiveLimit)
                .stream().map(SellerAnalyticsService::toItem).toList();
        Map<String, Long> statusCounts = !stats ? null : orderStatusLogRepository
                .countSellerOrderEventsByStatus(brandId, applyToStatus, toStatuses, actorType, fromDt, toDt)
                .stream().collect(Collectors.toMap(OrderStatusLogRepository.StatusCountRow::getBucket,
                        OrderStatusLogRepository.StatusCountRow::getCnt, (a, b) -> a, LinkedHashMap::new));
        List<SellerOrderEventsResponse.MemberCount> memberCounts = !"memberId".equals(groupBy) ? null
                : orderStatusLogRepository
                        .countSellerOrderEventsByMember(brandId, applyToStatus, toStatuses, actorType,
                                fromDt, toDt, effectiveLimit)
                        .stream()
                        .map(row -> new SellerOrderEventsResponse.MemberCount(row.getMemberId(), row.getCnt()))
                        .toList();
        return new SellerOrderEventsResponse(brandId, effectiveFrom, effectiveTo, items,
                statusCounts, memberCounts);
    }

    /** I-15 — 자사 상품 변경 이력. changeType·productId·기간 필터 */
    public SellerProductChangesResponse productChanges(Long brandId, String changeType, Long productId,
                                                       LocalDate from, LocalDate to, int limit) {
        requireBrand(brandId);
        if (changeType != null) {
            try {
                ProductChangeType.valueOf(changeType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_PERIOD_DAYS - 1);
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<SellerProductChangesResponse.Item> items = productChangeLogRepository
                .findSellerProductChanges(brandId, changeType, productId, effectiveFrom.atStartOfDay(),
                        effectiveTo.plusDays(1).atStartOfDay(), effectiveLimit)
                .stream().map(SellerAnalyticsService::toItem).toList();
        return new SellerProductChangesResponse(brandId, effectiveFrom, effectiveTo, items);
    }

    /** I-16 — 이탈 코호트. 마지막 로그인의 단일 출처 = account_event_logs.LOGIN_SUCCESS (02 D32) */
    public SellerChurnResponse churn(Long brandId, int inactiveDays) {
        requireBrand(brandId);
        List<OrderItemRepository.BuyerRow> buyers = orderItemRepository.findSellerBuyers(brandId);
        if (buyers.isEmpty()) {
            return new SellerChurnResponse(brandId, inactiveDays, 0, 0, 0.0, List.of());
        }
        List<Long> memberIds = buyers.stream().map(OrderItemRepository.BuyerRow::getMemberId).toList();
        Map<Long, LocalDateTime> lastLogins = accountEventLogRepository.findLastLogins(memberIds)
                .stream().collect(Collectors.toMap(AccountEventLogRepository.LastLoginRow::getMemberId,
                        AccountEventLogRepository.LastLoginRow::getLastLogin));

        LocalDateTime threshold = LocalDateTime.now().minusDays(inactiveDays);
        List<OrderItemRepository.BuyerRow> cohort = buyers.stream()
                .filter(b -> lastLogins.containsKey(b.getMemberId()))
                .toList();
        List<OrderItemRepository.BuyerRow> churnedRows = cohort.stream()
                .filter(b -> lastLogins.get(b.getMemberId()).isBefore(threshold))
                .sorted((a, b) -> lastLogins.get(a.getMemberId()).compareTo(lastLogins.get(b.getMemberId())))
                .limit(CHURN_LIST_CAP)
                .toList();
        long churnedCount = cohort.stream()
                .filter(b -> lastLogins.get(b.getMemberId()).isBefore(threshold)).count();

        Map<Long, Map<String, Long>> signals = new HashMap<>();
        if (!churnedRows.isEmpty()) {
            behaviorEventRepository.countPreChurnSignals(
                            churnedRows.stream().map(OrderItemRepository.BuyerRow::getMemberId).toList())
                    .forEach(row -> signals
                            .computeIfAbsent(row.getMemberId(), k -> new HashMap<>())
                            .put(row.getEventType(), row.getCnt()));
        }
        List<SellerChurnResponse.ChurnedMember> churned = churnedRows.stream()
                .map(b -> toChurnedMember(b, lastLogins.get(b.getMemberId()),
                        signals.getOrDefault(b.getMemberId(), Map.of())))
                .toList();
        double churnRate = cohort.isEmpty() ? 0.0
                : Math.round(churnedCount * 1000.0 / cohort.size()) / 10.0;
        return new SellerChurnResponse(brandId, inactiveDays, cohort.size(), (int) churnedCount,
                churnRate, churned);
    }

    /** 3단 판정 — checkout_start의 properties.productIds ∩ 자사 상품 (02 §4: 주문서 1회=1건) */
    private long countBrandCheckoutStarts(Long brandId, LocalDateTime from, LocalDateTime to) {
        Set<Long> brandProductIds = productRepository.findAllByBrandId(brandId).stream()
                .map(Product::getId).collect(Collectors.toSet());
        if (brandProductIds.isEmpty()) {
            return 0;
        }
        List<BehaviorEvent> events = behaviorEventRepository
                .findAllByEventTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        "checkout_start", from, to);
        return events.stream().filter(e -> containsAny(e.getProperties(), brandProductIds)).count();
    }

    private boolean containsAny(String properties, Set<Long> brandProductIds) {
        if (properties == null) {
            return false;
        }
        try {
            JsonNode ids = objectMapper.readTree(properties).path("productIds");
            if (!ids.isArray()) {
                return false;
            }
            for (JsonNode id : ids) {
                if (id.canConvertToLong() && brandProductIds.contains(id.asLong())) {
                    return true;
                }
            }
            return false;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return false;
        }
    }

    private void requireBrand(Long brandId) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
    }

    /** IPv4는 마지막 옥텟, IPv6는 프리픽스 2그룹 외 마스킹 — raw IP 미반환 (04 §10 I-8) */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.contains(":")) {
            String[] groups = ip.split(":");
            return groups.length >= 2 ? groups[0] + ":" + groups[1] + "::xxxx" : "xxxx";
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot) + ".xxx" : "xxxx";
    }

    private static SellerOrderEventsResponse.Item toItem(OrderStatusLog log) {
        return new SellerOrderEventsResponse.Item(log.getOrderId(), log.getFromStatus(),
                log.getToStatus(), log.getActorType().name(), log.getReason(),
                log.getCreatedAt().atZone(ZONE).toOffsetDateTime());
    }

    private static SellerProductChangesResponse.Item toItem(ProductChangeLog log) {
        return new SellerProductChangesResponse.Item(log.getProductId(), log.getChangeType().name(),
                log.getOldValue(), log.getNewValue(), log.getCreatedAt().atZone(ZONE).toOffsetDateTime());
    }

    private static SellerChurnResponse.ChurnedMember toChurnedMember(
            OrderItemRepository.BuyerRow buyer, LocalDateTime lastLogin, Map<String, Long> signals) {
        return new SellerChurnResponse.ChurnedMember(buyer.getMemberId(),
                lastLogin.atZone(ZONE).toOffsetDateTime(), buyer.getOrderCount(), buyer.getTotalSpent(),
                new SellerChurnResponse.Signals(signals.getOrDefault("product_view", 0L),
                        signals.getOrDefault("add_to_cart", 0L), signals.getOrDefault("search", 0L)));
    }
}
