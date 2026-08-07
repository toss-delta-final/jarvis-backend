package com.jarvis.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 집계 projection — 네이티브 alias와 게터명 일치 필수 */
    interface ProductQuantityRow {
        Long getProductId();
        Long getQuantity();
    }

    interface SalesTotalsRow {
        Long getSales();
        Long getOrders();
        Long getQuantity();
    }

    interface PeriodSalesRow {
        String getPeriod();
        Long getSales();
        Long getOrders();
        Long getQuantity();
    }

    interface StatusCountRow {
        String getBucket();
        Long getCnt();
    }

    interface ProductCountRow {
        Long getProductId();
        Long getCnt();
    }

    interface DayCountRow {
        String getDay();
        Long getCnt();
    }

    List<OrderItem> findAllByOrderId(Long orderId);

    List<OrderItem> findAllByOrderIdIn(List<Long> orderIds);

    /** 스케줄러 후보 스캔 — idx_order_item_status(status, status_changed_at) 사용 (02 §3) */
    List<OrderItem> findAllByStatusAndStatusChangedAtLessThanEqual(OrderItemStatus status, LocalDateTime threshold);

    /**
     * 조건부 전이 (01 §6) — 체크와 전이를 한 쿼리에 접어 경합 시 한쪽만 성공(0건 매치).
     * status_changed_at 갱신 필수 — 빠뜨리면 옛 타임스탬프 기준 연쇄 전이 (01 §6).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OrderItem i SET i.status = :to, i.statusChangedAt = :now
            WHERE i.id = :id AND i.status = :from
            """)
    int transitionStatus(@Param("id") Long id,
                         @Param("from") OrderItemStatus from,
                         @Param("to") OrderItemStatus to,
                         @Param("now") LocalDateTime now);

    /** 전량 취소 판정 (01 §2-1) — 0이면 소속 아이템 전부 CANCELLED */
    long countByOrderIdAndStatusNot(Long orderId, OrderItemStatus status);

    /**
     * I-30 발송 대상 조회 — 아이템이 그 브랜드 소유일 때만 반환한다(노션 I-30).
     * 소유가 아니면 빈 값이라 호출부가 404로 존재를 은닉한다 — 403이 아니다(I-11 규칙).
     * brandId는 티켓 claim에서 오지만 신뢰하지 않고 실행 시점에 다시 확인하는 지점이다.
     */
    @Query("""
            select oi from OrderItem oi
            where oi.id = :orderItemId
              and oi.productId in (select p.id from Product p where p.brandId = :brandId)
            """)
    Optional<OrderItem> findOwnedByBrand(@Param("orderItemId") Long orderItemId,
                                         @Param("brandId") Long brandId);

    /** S-3/I-9 표시 판매량 부착 — PAID 주문 합산 (02 D18, P-6 popular와 동일 규칙) */
    @Query(value = """
            SELECT oi.product_id AS productId, SUM(oi.quantity) AS quantity
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            WHERE oi.product_id IN (:productIds)
            GROUP BY oi.product_id
            """, nativeQuery = true)
    List<ProductQuantityRow> sumPaidQuantityByProduct(@Param("productIds") Collection<Long> productIds);

    /**
     * S-2·I-29 주문 단위 탭 카운트 (노션 S-2) — 자사 아이템이 포함된 PAID 주문을 대표상태로 분류.
     * 대표 탭 규칙(위에서부터): 활성 클레임(*_REQUESTED) 또는 전량 종결(CANCELLED/RETURNED) → CLAIM,
     * ORDERED 존재 → ORDERED, SHIPPING 존재 → SHIPPING, 그 외(DELIVERED/CONFIRMED) → DELIVERED.
     * item.status는 claim REQUESTED와 동기 전이(01 §5)라 클레임 판정에 claim 테이블 조인 불필요.
     * orderId·from·to는 I-29 전용 선택 필터 — S-2는 전부 null을 넘겨 종전과 같이 동작한다.
     * 탭 필터는 여기 없다: tabCounts는 탭 선택과 무관한 전량 기준이기 때문(S-2 규칙).
     */
    @Query(value = """
            SELECT t.tab AS bucket, COUNT(*) AS cnt FROM (
                SELECT
                    CASE
                        WHEN SUM(oi.status IN ('CANCEL_REQUESTED','RETURN_REQUESTED')) > 0 THEN 'CLAIM'
                        WHEN SUM(oi.status NOT IN ('CANCELLED','RETURNED')) = 0 THEN 'CLAIM'
                        WHEN SUM(oi.status = 'ORDERED') > 0 THEN 'ORDERED'
                        WHEN SUM(oi.status = 'SHIPPING') > 0 THEN 'SHIPPING'
                        ELSE 'DELIVERED'
                    END AS tab
                FROM order_item oi
                JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
                JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
                WHERE (:orderId IS NULL OR o.id = :orderId)
                  AND (CAST(:from AS datetime) IS NULL OR o.created_at >= :from)
                  AND (CAST(:to AS datetime) IS NULL OR o.created_at < :to)
                GROUP BY oi.order_id
            ) t
            GROUP BY t.tab
            """, nativeQuery = true)
    List<StatusCountRow> countSellerOrderTabs(@Param("brandId") Long brandId,
                                              @Param("orderId") Long orderId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    /**
     * S-2·I-29 페이지 orderId — 대표 탭 필터(null=전체) + 주문일시 최신순. 대표상태 파생은 countSellerOrderTabs와 동일.
     * orderId·from·to는 I-29 전용 선택 필터 — S-2는 전부 null을 넘겨 종전과 같이 동작한다.
     */
    @Query(value = """
            SELECT t.order_id FROM (
                SELECT oi.order_id AS order_id, o.created_at AS created_at,
                    CASE
                        WHEN SUM(oi.status IN ('CANCEL_REQUESTED','RETURN_REQUESTED')) > 0 THEN 'CLAIM'
                        WHEN SUM(oi.status NOT IN ('CANCELLED','RETURNED')) = 0 THEN 'CLAIM'
                        WHEN SUM(oi.status = 'ORDERED') > 0 THEN 'ORDERED'
                        WHEN SUM(oi.status = 'SHIPPING') > 0 THEN 'SHIPPING'
                        ELSE 'DELIVERED'
                    END AS tab
                FROM order_item oi
                JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
                JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
                WHERE (:orderId IS NULL OR o.id = :orderId)
                  AND (CAST(:from AS datetime) IS NULL OR o.created_at >= :from)
                  AND (CAST(:to AS datetime) IS NULL OR o.created_at < :to)
                GROUP BY oi.order_id, o.created_at
            ) t
            WHERE (:tab IS NULL OR t.tab = :tab)
            ORDER BY t.created_at DESC, t.order_id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Long> findSellerOrderIdsByTab(@Param("brandId") Long brandId, @Param("tab") String tab,
                                       @Param("orderId") Long orderId,
                                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("limit") int limit, @Param("offset") long offset);

    /** S-2 페이지 주문들의 자사 아이템 — 금액·건수·대표상품·대표상태 산출용 */
    @Query("""
            select oi from OrderItem oi
            where oi.orderId in :orderIds
              and oi.productId in (select p.id from Product p where p.brandId = :brandId)
            """)
    List<OrderItem> findSellerItemsByOrderIds(@Param("brandId") Long brandId,
                                              @Param("orderIds") Collection<Long> orderIds);

    /**
     * S-1/I-6 매출·판매수 집계 규칙 (04 §7) — PAID 주문의 order_item 중
     * PENDING/CANCELLED/RETURNED 제외(처리중 포함). 이하 판매자 집계 전부 동일.
     */
    @Query(value = """
            SELECT COALESCE(SUM(oi.price * oi.quantity), 0) AS sales,
                   COUNT(DISTINCT oi.order_id) AS orders,
                   COALESCE(SUM(oi.quantity), 0) AS quantity
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE oi.status NOT IN ('PENDING', 'CANCELLED', 'RETURNED')
              AND o.paid_at >= :from AND o.paid_at < :to
            """, nativeQuery = true)
    SalesTotalsRow sumSellerSales(@Param("brandId") Long brandId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /** S-1 상품별 판매수 (04 §7) */
    @Query(value = """
            SELECT oi.product_id AS productId, SUM(oi.quantity) AS quantity
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE oi.status NOT IN ('PENDING', 'CANCELLED', 'RETURNED')
              AND o.paid_at >= :from AND o.paid_at < :to
            GROUP BY oi.product_id
            """, nativeQuery = true)
    List<ProductQuantityRow> sumSellerSalesByProduct(@Param("brandId") Long brandId,
                                                     @Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to);

    /** I-6 시계열 — fmt는 DATE_FORMAT 패턴(daily %Y-%m-%d / weekly %x-W%v / monthly %Y-%m) */
    @Query(value = """
            SELECT DATE_FORMAT(o.paid_at, :fmt) AS period,
                   COALESCE(SUM(oi.price * oi.quantity), 0) AS sales,
                   COUNT(DISTINCT oi.order_id) AS orders,
                   COALESCE(SUM(oi.quantity), 0) AS quantity
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE oi.status NOT IN ('PENDING', 'CANCELLED', 'RETURNED')
              AND o.paid_at >= :from AND o.paid_at < :to
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<PeriodSalesRow> sumSellerSalesByPeriod(@Param("brandId") Long brandId,
                                                @Param("fmt") String fmt,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /**
     * I-6 statusCounts (04 §10, 노션 확정 어휘) — PAID/PAYMENT_FAILED는 자사 아이템이 포함된
     * 주문 단위, CANCELLED/RETURNED는 order_item.status 단위. PAYMENT_FAILED는 paid_at이
     * 없으므로 created_at 기준.
     */
    @Query(value = """
            SELECT 'PAID' AS bucket, COUNT(*) AS cnt
            FROM orders o
            WHERE o.status = 'PAID' AND o.paid_at >= :from AND o.paid_at < :to
              AND EXISTS (SELECT 1 FROM order_item oi
                          JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
                          WHERE oi.order_id = o.id)
            UNION ALL
            SELECT 'CANCELLED', COUNT(*)
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE oi.status = 'CANCELLED' AND o.paid_at >= :from AND o.paid_at < :to
            UNION ALL
            SELECT 'PAYMENT_FAILED', COUNT(*)
            FROM orders o
            WHERE o.status = 'PAYMENT_FAILED' AND o.created_at >= :from AND o.created_at < :to
              AND EXISTS (SELECT 1 FROM order_item oi
                          JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
                          WHERE oi.order_id = o.id)
            UNION ALL
            SELECT 'RETURNED', COUNT(*)
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE oi.status = 'RETURNED' AND o.paid_at >= :from AND o.paid_at < :to
            """, nativeQuery = true)
    List<StatusCountRow> countSellerStatusBuckets(@Param("brandId") Long brandId,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    /**
     * S-1 주문상태 카드 — 자사 아이템 상태 분포(현재 스냅샷). 반환은 order_item.status 원값(9종),
     * 화면 6종(ORDERED/SHIPPING/DELIVERED/CONFIRMED/CANCELLED/RETURNED) 매핑과 activeTotal은 서비스 계산.
     */
    @Query(value = """
            SELECT oi.status AS bucket, COUNT(*) AS cnt
            FROM order_item oi
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            GROUP BY oi.status
            """, nativeQuery = true)
    List<StatusCountRow> countSellerItemsByStatus(@Param("brandId") Long brandId);

    /**
     * I-7 4단 purchase 정본 — order_item×product×brand, 주문서 1회=1 (02 §4).
     * I-13 groupBy=eventType의 purchaseComplete도 같은 값을 쓴다(노션 I-13 2026-07-31 개정) —
     * productId는 I-13 상품 한정 필터용이고 I-7은 null을 넘긴다.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT oi.order_id)
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE (:productId IS NULL OR oi.product_id = :productId)
              AND o.paid_at >= :from AND o.paid_at < :to
            """, nativeQuery = true)
    long countSellerPurchaseOrders(@Param("brandId") Long brandId,
                                   @Param("productId") Long productId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /**
     * I-13 상품별 purchaseComplete — "그 상품이 포함된 구매완료 주문 수"(노션 I-13 2026-07-31 개정).
     * purchase_complete 이벤트는 주문당 1회 발사라 product_id가 비어 이벤트로는 상품 귀속이
     * 불가능하다 — 주문이 정본이다(02 §4). 수량이 아니라 주문 건수라 DISTINCT order_id.
     */
    @Query(value = """
            SELECT oi.product_id AS productId, COUNT(DISTINCT oi.order_id) AS cnt
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE (:productId IS NULL OR oi.product_id = :productId)
              AND o.paid_at >= :from AND o.paid_at < :to
            GROUP BY oi.product_id
            """, nativeQuery = true)
    List<ProductCountRow> countSellerPurchaseOrdersByProduct(@Param("brandId") Long brandId,
                                                             @Param("productId") Long productId,
                                                             @Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to);

    /** I-13 groupBy=date의 purchaseComplete — 일자 기준은 paid_at(다른 3종은 이벤트 created_at) */
    @Query(value = """
            SELECT DATE_FORMAT(o.paid_at, '%Y-%m-%d') AS day, COUNT(DISTINCT oi.order_id) AS cnt
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE (:productId IS NULL OR oi.product_id = :productId)
              AND o.paid_at >= :from AND o.paid_at < :to
            GROUP BY DATE_FORMAT(o.paid_at, '%Y-%m-%d')
            """, nativeQuery = true)
    List<DayCountRow> countSellerPurchaseOrdersByDate(@Param("brandId") Long brandId,
                                                      @Param("productId") Long productId,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to);

    /**
     * P-5 signals의 {@code recentPurchasedProductIds} (노션 I-22) — FastAPI는 이걸 가중치가 아니라
     * <b>결과에서 빼는 필터</b>로 쓴다(이미 산 상품을 또 추천하지 않기 위해). 그래서 최신순 정렬이
     * 의미가 크지 않지만, 상한을 자를 때 오래된 구매부터 버리도록 최신순으로 뽑는다.
     */
    @Query(value = """
            SELECT oi.product_id FROM order_item oi
            JOIN orders o ON o.id = oi.order_id
            WHERE o.member_id = :memberId AND o.status = 'PAID'
            GROUP BY oi.product_id
            ORDER BY MAX(o.paid_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRecentPurchasedProductIds(@Param("memberId") Long memberId,
                                             @Param("limit") int limit);

    interface AttributionRow {
        Long getConfirmedSales();
        Long getEstimatedSales();
        Long getAiOrderCount();
    }

    /**
     * S-1 AI 추천 성과 — 귀속 판정 (「AI 추천 성과」 제안 2026-08-06 · 구현 2026-08-07).
     *
     * <p><b>판정 단위는 order_item 한 줄</b>이다. 한 주문에 AI 추천 상품과 직접 발견 상품이 섞이므로
     * 주문 단위로는 나눌 수 없다. 금액·스코프·상태 3종 제외는 <b>기존 매출 산식을 그대로 재사용</b>한다 —
     * 다른 산식을 쓰면 대시보드 안에서 숫자가 갈린다.
     *
     * <p><b>확정 귀속</b>: 담기 이벤트에 추천 출처가 기록돼 있고 담기~결제 간격이 창 이내.
     * 담기 자체가 확정적 의사 표시라 <b>경합하지 않는다</b> — 담은 뒤 상세를 다시 봐도 판정이 안 바뀐다.
     *
     * <p><b>추정 귀속</b>: 담기 기록은 없으나 창 안에 추천 카드의 노출·클릭이 있는 경우(바로구매·홈 추천
     * 경유). 확정과 달리 <b>경합한다</b> — 추천 접촉과 비추천 접촉(상세 직접 진입) 중 결제에 더 가까운
     * 쪽이 이긴다(O-1 {@code last_valid_touch}). 동시각이면 추천이 이긴다({@code >=}).
     *
     * <p><b>게스트 구간을 이어붙인다</b> — {@code behavior_events}는 승계 시 백필하지 않으므로
     * {@code guest.converted_member_id} 조인이 <b>필수</b>다. 빼면 가입 직전에 한참 둘러본 회원의
     * 접촉이 통째로 빠져 AI 매출이 과소집계된다(제안서가 "조인을 제거해 단순화" 쪽으로 추측했던 지점).
     *
     * <p>{@code POPULAR_FALLBACK} 목록(P-5 대체분)은 {@code source} 조건으로 자동 배제된다 —
     * AI가 뽑은 게 아니라 인기상품으로 대신한 것이라 추천 성과가 아니다.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN confirmed = 1 THEN amount END), 0) AS confirmedSales,
                   COALESCE(SUM(CASE WHEN confirmed = 0 AND estimated = 1 THEN amount END), 0)
                       AS estimatedSales,
                   COUNT(DISTINCT CASE WHEN confirmed = 1 OR estimated = 1 THEN orderId END)
                       AS aiOrderCount
            FROM (
              SELECT oi.order_id AS orderId,
                     oi.price * oi.quantity AS amount,
                     EXISTS (SELECT 1 FROM behavior_events be
                             LEFT JOIN guest g ON g.id = be.guest_id
                             JOIN recommendation_list rl ON rl.list_id = be.list_id
                                                        AND rl.source = 'AI_RECOMMENDED'
                             WHERE be.event_type = 'add_to_cart'
                               AND be.product_id = oi.product_id
                               AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                               AND be.occurred_at <= o.paid_at
                               AND be.occurred_at >= o.paid_at - INTERVAL :windowDays DAY) AS confirmed,
                     (COALESCE((SELECT MAX(be.occurred_at) FROM behavior_events be
                                LEFT JOIN guest g ON g.id = be.guest_id
                                JOIN recommendation_list rl ON rl.list_id = be.list_id
                                                           AND rl.source = 'AI_RECOMMENDED'
                                WHERE be.event_type IN ('product_visible', 'product_click')
                                  AND be.product_id = oi.product_id
                                  AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                                  AND be.occurred_at <= o.paid_at
                                  AND be.occurred_at >= o.paid_at - INTERVAL :windowDays DAY),
                               '1000-01-01')
                      >= COALESCE((SELECT MAX(be.occurred_at) FROM behavior_events be
                                   LEFT JOIN guest g ON g.id = be.guest_id
                                   WHERE be.event_type = 'product_view'
                                     AND be.list_id IS NULL
                                     AND be.product_id = oi.product_id
                                     AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                                     AND be.occurred_at <= o.paid_at
                                     AND be.occurred_at >= o.paid_at - INTERVAL :windowDays DAY),
                                  '1000-01-01')
                      AND EXISTS (SELECT 1 FROM behavior_events be
                                  LEFT JOIN guest g ON g.id = be.guest_id
                                  JOIN recommendation_list rl ON rl.list_id = be.list_id
                                                             AND rl.source = 'AI_RECOMMENDED'
                                  WHERE be.event_type IN ('product_visible', 'product_click')
                                    AND be.product_id = oi.product_id
                                    AND COALESCE(be.member_id, g.converted_member_id) = o.member_id
                                    AND be.occurred_at <= o.paid_at
                                    AND be.occurred_at >= o.paid_at - INTERVAL :windowDays DAY)) AS estimated
              FROM order_item oi
              JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
              JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
              WHERE oi.status NOT IN ('PENDING', 'CANCELLED', 'RETURNED')
                AND o.paid_at >= :from AND o.paid_at < :to
            ) lines
            """, nativeQuery = true)
    AttributionRow aggregateAiAttribution(@Param("brandId") Long brandId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("windowDays") int windowDays);

    /**
     * S-1 수집 커버리지 — 기간 내 자사 상품 포함 PAID 주문 수 대비 {@code purchase_complete}
     * 이벤트로 관측된 주문 수. 0.93이면 결제 이벤트 7%가 유실되는 파이프라인이라는 뜻이고,
     * AI 매출도 유사한 비율로 과소집계되고 있다고 읽는다(수집 장애 조기 감지에도 쓴다).
     */
    @Query(value = """
            SELECT COUNT(DISTINCT o.id) AS paidOrders,
                   COUNT(DISTINCT CASE WHEN EXISTS (
                       SELECT 1 FROM behavior_events be
                       WHERE be.event_type = 'purchase_complete'
                         AND JSON_UNQUOTE(JSON_EXTRACT(be.properties, '$.orderId')) = CAST(o.id AS CHAR)
                   ) THEN o.id END) AS observedOrders
            FROM orders o
            JOIN order_item oi ON oi.order_id = o.id
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE o.status = 'PAID' AND o.paid_at >= :from AND o.paid_at < :to
            """, nativeQuery = true)
    CoverageRow aggregateCollectionCoverage(@Param("brandId") Long brandId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    interface CoverageRow {
        Long getPaidOrders();
        Long getObservedOrders();
    }
}
