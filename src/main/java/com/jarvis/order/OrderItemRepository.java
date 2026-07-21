package com.jarvis.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
     * S-2 주문 단위 탭 카운트 (노션 S-2) — 자사 아이템이 포함된 PAID 주문을 대표상태로 분류.
     * 대표 탭 규칙(위에서부터): 활성 클레임(*_REQUESTED) 또는 전량 종결(CANCELLED/RETURNED) → CLAIM,
     * ORDERED 존재 → ORDERED, SHIPPING 존재 → SHIPPING, 그 외(DELIVERED/CONFIRMED) → DELIVERED.
     * item.status는 claim REQUESTED와 동기 전이(01 §5)라 클레임 판정에 claim 테이블 조인 불필요.
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
                GROUP BY oi.order_id
            ) t
            GROUP BY t.tab
            """, nativeQuery = true)
    List<StatusCountRow> countSellerOrderTabs(@Param("brandId") Long brandId);

    /** S-2 페이지 orderId — 대표 탭 필터(null=전체) + 주문일시 최신순. 대표상태 파생은 countSellerOrderTabs와 동일. */
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
                GROUP BY oi.order_id, o.created_at
            ) t
            WHERE (:tab IS NULL OR t.tab = :tab)
            ORDER BY t.created_at DESC, t.order_id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Long> findSellerOrderIdsByTab(@Param("brandId") Long brandId, @Param("tab") String tab,
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

    /** I-7 4단 purchase 정본 — order_item×product×brand, 주문서 1회=1 (02 §4) */
    @Query(value = """
            SELECT COUNT(DISTINCT oi.order_id)
            FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
            JOIN product p ON p.id = oi.product_id AND p.brand_id = :brandId
            WHERE o.paid_at >= :from AND o.paid_at < :to
            """, nativeQuery = true)
    long countSellerPurchaseOrders(@Param("brandId") Long brandId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);
}
