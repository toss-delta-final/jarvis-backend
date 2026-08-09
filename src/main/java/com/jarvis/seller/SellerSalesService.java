package com.jarvis.seller;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.event.BehaviorEventRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.OrderItemRepository;
import com.jarvis.order.OrderStatusLogRepository;
import com.jarvis.product.Product;
import com.jarvis.product.ProductOption;
import com.jarvis.product.ProductOptionRepository;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductStock;
import com.jarvis.product.ProductStockRepository;
import com.jarvis.seller.dto.SellerSalesResponse;
import com.jarvis.seller.dto.SellerSummaryResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-1 요약 + I-6 매출 시계열 (04 §7·§10) — 집계 규칙 공유: PAID 주문의 order_item 중
 * PENDING/CANCELLED/RETURNED 제외. LLM에는 집계만 준다(raw 미노출 — 05 §I-6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerSalesService {

    private static final int MOVING_WINDOW = 7;
    private static final int MIN_WINDOW = 3;
    private static final double ANOMALY_THRESHOLD_PCT = 30.0;

    private final OrderItemRepository orderItemRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final BehaviorEventRepository behaviorEventRepository;
    private final ProductRepository productRepository;
    private final ProductStockRepository productStockRepository;
    private final ProductOptionRepository productOptionRepository;
    private final BrandRepository brandRepository;
    private final SellerAttributionService sellerAttributionService;
    private final ActiveVisitorStore activeVisitorStore;

    private static final int DEFAULT_LOW_STOCK = 10;
    private static final int DEFAULT_TREND_DAYS = 7;
    private static final int ACTIVE_VISITOR_MINUTES = 30;
    /** 화면 노출 6종 — PENDING·CANCEL_REQUESTED·RETURN_REQUESTED는 제외(노션 S-1) */
    private static final List<String> STATUS_KEYS =
            List.of("ORDERED", "SHIPPING", "DELIVERED", "CONFIRMED", "CANCELLED", "RETURNED");

    /** S-1 — 대시보드 진입 1회 호출로 전 블록(주문상태·오늘지표·매출추이·재고부족·상품퍼널)을 덮는다 (노션 S-1) */
    public SellerSummaryResponse summary(Brand brand, String fromParam, String toParam,
                                         Integer lowStockThreshold, Integer trendDays) {
        LocalDate today = LocalDate.now();
        LocalDate to = parseDate(toParam, today);
        LocalDate from = parseDate(fromParam, today);
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.SELLER_INVALID_PARAM);
        }
        int threshold = require(lowStockThreshold, DEFAULT_LOW_STOCK, 1, 999);
        int trend = require(trendDays, DEFAULT_TREND_DAYS, 1, 90);
        Long brandId = brand.getId();

        SellerSummaryResponse.OrderStatus orderStatus = orderStatus(brandId);
        SellerSummaryResponse.Today todayBlock = today(brandId, today);
        SellerSummaryResponse.SalesTrend salesTrend = salesTrend(brandId, to, trend);
        SellerSummaryResponse.LowStock lowStock = lowStock(brandId, threshold);

        return new SellerSummaryResponse(new SellerSummaryResponse.Period(from, to),
                orderStatus, todayBlock, salesTrend, lowStock,
                aiAttribution(brandId, from, to));
    }

    /** 주문상태 카드 — 현재 스냅샷. counts 6종 0채움, activeTotal은 CANCELLED·RETURNED 제외 합 */
    private SellerSummaryResponse.OrderStatus orderStatus(Long brandId) {
        Map<String, Long> raw = orderItemRepository.countSellerItemsByStatus(brandId).stream()
                .collect(Collectors.toMap(OrderItemRepository.StatusCountRow::getBucket,
                        OrderItemRepository.StatusCountRow::getCnt, (a, b) -> a));
        Map<String, Long> counts = new LinkedHashMap<>();
        long activeTotal = 0;
        for (String key : STATUS_KEYS) {
            long cnt = raw.getOrDefault(key, 0L);
            counts.put(key, cnt);
            if (!"CANCELLED".equals(key) && !"RETURNED".equals(key)) {
                activeTotal += cnt;
            }
        }
        Double avgSeconds = orderStatusLogRepository.avgSellerDeliverySeconds(brandId);
        Double avgDeliveryDays = avgSeconds == null ? null
                : Math.round(avgSeconds / 86_400.0 * 10) / 10.0;
        return new SellerSummaryResponse.OrderStatus(counts, activeTotal, avgDeliveryDays);
    }

    /** 오늘 지표 — 항상 오늘(자정~현재), *ChangeRate는 어제 하루 대비(어제 0이면 null) */
    private SellerSummaryResponse.Today today(Long brandId, LocalDate today) {
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime yesterdayStart = today.minusDays(1).atStartOfDay();

        OrderItemRepository.SalesTotalsRow t = orderItemRepository.sumSellerSales(brandId, todayStart, tomorrowStart);
        OrderItemRepository.SalesTotalsRow y = orderItemRepository.sumSellerSales(brandId, yesterdayStart, todayStart);
        long sales = t.getSales();
        long orders = t.getOrders();
        long aov = orders == 0 ? 0 : Math.round((double) sales / orders);
        long ySales = y.getSales();
        long yOrders = y.getOrders();
        long yAov = yOrders == 0 ? 0 : Math.round((double) ySales / yOrders);
        long activeVisitors = activeVisitors(brandId);
        return new SellerSummaryResponse.Today(sales, orders, aov, activeVisitors,
                changeRate(sales, ySales), changeRate(orders, yOrders), changeRate(aov, yAov));
    }

    /**
     * 실시간 방문자 — 최근 30분 자사 상품 관련 이벤트의 고유 세션 수 (노션 S-1).
     *
     * <p>원천은 스트림 컨슈머가 유지하는 라이브 집합이다(08 D4) — 대시보드 진입마다
     * {@code behavior_events} 30분 구간을 스캔하던 것을 없앴다. 스트림이 멈췄거나 Redis가 죽으면
     * <b>기존 DB 집계로 폴백</b>한다(08 D5). 숫자의 정의는 양쪽이 같아 화면은 달라지지 않는다.
     */
    private long activeVisitors(Long brandId) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(ACTIVE_VISITOR_MINUTES);
        return activeVisitorStore.count(brandId, since)
                .orElseGet(() -> behaviorEventRepository.countActiveVisitors(brandId, since));
    }

    /** 매출 추이 — to 기준 trend일, 매출 0인 날도 채워 반환 + total = points 합 */
    private SellerSummaryResponse.SalesTrend salesTrend(Long brandId, LocalDate to, int trend) {
        LocalDate trendFrom = to.minusDays(trend - 1L);
        Map<String, OrderItemRepository.PeriodSalesRow> byDay = orderItemRepository
                .sumSellerSalesByPeriod(brandId, "%Y-%m-%d",
                        trendFrom.atStartOfDay(), to.plusDays(1).atStartOfDay()).stream()
                .collect(Collectors.toMap(OrderItemRepository.PeriodSalesRow::getPeriod, Function.identity()));
        List<SellerSummaryResponse.SalesTrend.Point> points = new ArrayList<>();
        long total = 0;
        for (LocalDate d = trendFrom; !d.isAfter(to); d = d.plusDays(1)) {
            OrderItemRepository.PeriodSalesRow row = byDay.get(d.toString());
            long sales = row != null ? row.getSales() : 0L;
            points.add(new SellerSummaryResponse.SalesTrend.Point(d, sales));
            total += sales;
        }
        return new SellerSummaryResponse.SalesTrend(total, points);
    }

    /** 옵션 단위 (02 D33 개정) — 한 상품의 여러 옵션이 부족하면 여러 줄이다 */
    private SellerSummaryResponse.LowStock lowStock(Long brandId, int threshold) {
        List<ProductStock> lowStocks = productStockRepository.findLowStock(brandId, threshold);
        Map<Long, String> productNames = new LinkedHashMap<>();
        Map<Long, String> imageUrls = new LinkedHashMap<>();
        productRepository.findAllById(lowStocks.stream().map(ProductStock::getProductId).distinct().toList())
                .forEach(p -> {
                    productNames.put(p.getId(), p.getName());
                    imageUrls.put(p.getId(), p.getImageUrl());
                });
        Map<Long, String> optionNames = productOptionRepository.findAllById(
                        lowStocks.stream().map(ProductStock::getOptionId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ProductOption::getId, ProductOption::getName));
        List<SellerSummaryResponse.LowStock.Item> items = lowStocks.stream()
                .map(stock -> new SellerSummaryResponse.LowStock.Item(
                        stock.getProductId(), productNames.get(stock.getProductId()),
                        imageUrls.get(stock.getProductId()), stock.getOptionId(),
                        stock.getOptionId() == null ? null : optionNames.get(stock.getOptionId()),
                        stock.getQuantity()))
                .toList();
        return new SellerSummaryResponse.LowStock(threshold, items.size(), items);
    }

    /** 상품별 퍼널 — from..to 기간, 판매수 desc → 조회수 desc (구 스키마 유지) */
    // S-1 products[] 블록은 2026-08-06 제거됐다 — 소비처가 0건이었고 I-13 groupBy=product가
    // 같은 정보를 더 풍부하게(salesQuantity·체류시간까지) 준다.

    /**
     * AI 추천 경유 매출 (「AI 추천 성과」 2026-08-06 제안 · 2026-08-07 구현).
     *
     * <p><b>실패를 격리한다</b> — 귀속 집계 하나 때문에 대시보드 전체가 500이 되면 안 되므로,
     * 실패 시 이 블록만 {@code null}로 내려간다. 나머지 5개 서브쿼리는 종전대로 하나라도 실패하면
     * 500이다(그건 화면의 뼈대라 부분 응답이 더 위험하다).
     *
     * <p><b>이 catch는 집계가 별도 트랜잭션일 때만 의미가 있다</b> — 집계를
     * {@link SellerAttributionService}(REQUIRES_NEW)로 뺀 이유다. 같은 트랜잭션 안에서 실패하면
     * 예외가 경계를 지나며 rollback-only로 마킹돼, 여기서 잡아도 커밋 시점에
     * UnexpectedRollbackException으로 되돌아온다. 2026-08-08 S-1 전건 500이 그 경로였다.
     * 집계를 이 클래스 안으로 다시 인라인하면 그 버그가 그대로 복구된다.
     */
    private SellerSummaryResponse.AiAttribution aiAttribution(Long brandId, LocalDate from,
                                                              LocalDate to) {
        try {
            return sellerAttributionService.aggregate(brandId, from, to);
        } catch (Exception e) {
            log.warn("AI 추천 성과 집계 실패 — 이 블록만 비우고 대시보드는 내려보낸다 (brandId={})",
                    brandId, e);
            return null;
        }
    }

    /** (오늘 - 어제) / 어제 × 100, 소수 1자리. 어제가 0이면 null(FE "—") */
    private static Double changeRate(long todayValue, long yesterdayValue) {
        if (yesterdayValue == 0) {
            return null;
        }
        return Math.round((todayValue - yesterdayValue) * 1000.0 / yesterdayValue) / 10.0;
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.SELLER_INVALID_PARAM);
        }
    }

    private static int require(Integer value, int fallback, int min, int max) {
        int v = value != null ? value : fallback;
        if (v < min || v > max) {
            throw new BusinessException(ErrorCode.SELLER_INVALID_PARAM);
        }
        return v;
    }

    /** I-6 — granularity daily|weekly|monthly|summary, 기간은 필수(AnalysisPeriod로 사전 검증) */
    public SellerSalesResponse sales(Long brandId, String granularity, AnalysisPeriod period) {
        if (!brandRepository.existsById(brandId)) {
            throw new BusinessException(ErrorCode.BRAND_NOT_FOUND);
        }
        String effective = granularity == null ? "daily" : granularity;
        LocalDate from = period.from();
        LocalDate to = period.to();
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();

        if ("summary".equals(effective)) {
            OrderItemRepository.SalesTotalsRow totals =
                    orderItemRepository.sumSellerSales(brandId, fromDt, toDt);
            // 노션 I-6 확정 어휘 4종 고정(0 채움) — PAID/PAYMENT_FAILED 주문 단위, CANCELLED/RETURNED 아이템 단위
            Map<String, Long> buckets = orderItemRepository
                    .countSellerStatusBuckets(brandId, fromDt, toDt).stream()
                    .collect(Collectors.toMap(OrderItemRepository.StatusCountRow::getBucket,
                            OrderItemRepository.StatusCountRow::getCnt, (a, b) -> a));
            Map<String, Long> statusCounts = new LinkedHashMap<>();
            for (String key : List.of("PAID", "CANCELLED", "PAYMENT_FAILED", "RETURNED")) {
                statusCounts.put(key, buckets.getOrDefault(key, 0L));
            }
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            return SellerSalesResponse.ofSummary(brandId, from, to,
                    totals.getSales(), totals.getOrders(), totals.getSales() / days, statusCounts);
        }

        List<SellerSalesResponse.Point> series = buildSeries(brandId, effective, from, to, fromDt, toDt);
        return SellerSalesResponse.ofSeries(brandId, effective, from, to, series,
                new SellerSalesResponse.Config(MOVING_WINDOW, ANOMALY_THRESHOLD_PCT));
    }

    private List<SellerSalesResponse.Point> buildSeries(Long brandId, String granularity,
                                                        LocalDate from, LocalDate to,
                                                        LocalDateTime fromDt, LocalDateTime toDt) {
        String fmt = switch (granularity) {
            case "daily" -> "%Y-%m-%d";
            case "weekly" -> "%x-W%v";
            case "monthly" -> "%Y-%m";
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        };
        Map<String, OrderItemRepository.PeriodSalesRow> byPeriod = orderItemRepository
                .sumSellerSalesByPeriod(brandId, fmt, fromDt, toDt).stream()
                .collect(Collectors.toMap(OrderItemRepository.PeriodSalesRow::getPeriod,
                        Function.identity()));
        return withAnomaly(periodKeys(granularity, from, to), byPeriod);
    }

    /** 빈 구간 0 채움 — 이동평균이 결측 구간을 건너뛰며 왜곡되지 않게 */
    private static List<String> periodKeys(String granularity, LocalDate from, LocalDate to) {
        List<String> keys = new ArrayList<>();
        switch (granularity) {
            case "daily" -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    keys.add(d.toString());
                }
            }
            case "weekly" -> {
                WeekFields wf = WeekFields.ISO;
                for (LocalDate d = from.with(DayOfWeek.MONDAY); !d.isAfter(to); d = d.plusWeeks(1)) {
                    keys.add(String.format("%d-W%02d",
                            d.get(wf.weekBasedYear()), d.get(wf.weekOfWeekBasedYear())));
                }
            }
            case "monthly" -> {
                for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                    keys.add(m.toString());
                }
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return keys;
    }

    /** 이상 감지 (04 §10 I-6) — 직전 7개 구간 이동평균 대비 ±30%. 표본 3개 미만이면 판정 보류 */
    private static List<SellerSalesResponse.Point> withAnomaly(
            List<String> keys, Map<String, OrderItemRepository.PeriodSalesRow> byPeriod) {
        List<SellerSalesResponse.Point> points = new ArrayList<>();
        List<Long> history = new ArrayList<>();
        for (String key : keys) {
            OrderItemRepository.PeriodSalesRow row = byPeriod.get(key);
            long sales = row != null ? row.getSales() : 0L;
            long orders = row != null ? row.getOrders() : 0L;
            long quantity = row != null ? row.getQuantity() : 0L;
            Double deviationPct = null;
            boolean anomaly = false;
            if (history.size() >= MIN_WINDOW) {
                List<Long> window = history.subList(Math.max(0, history.size() - MOVING_WINDOW),
                        history.size());
                double avg = window.stream().mapToLong(Long::longValue).average().orElse(0);
                if (avg > 0) {
                    double deviation = (sales - avg) / avg * 100.0;
                    deviationPct = Math.round(deviation * 10) / 10.0;
                    // 0원 구간은 이상 아님 — 저볼륨에서 무판매일이 전부 -100% 판정되는 노이즈 방지
                    anomaly = sales > 0 && Math.abs(deviation) >= ANOMALY_THRESHOLD_PCT;
                } else if (sales > 0) {
                    anomaly = true; // 무매출 구간 직후 매출 발생 — 기준선 0이라 dev는 null
                }
            }
            points.add(new SellerSalesResponse.Point(key, sales, orders, quantity, deviationPct, anomaly));
            history.add(sales);
        }
        return points;
    }
}
