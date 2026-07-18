package com.jarvis.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** S-2 자사 아이템 단위 주문 목록 (04 §7) — PAID 주문만, 최신순 */
    @Query("""
            select oi from OrderItem oi
            where oi.productId in (select p.id from Product p where p.brandId = :brandId)
              and oi.orderId in (select o.id from Order o where o.status = com.jarvis.order.OrderStatus.PAID)
            order by oi.id desc
            """)
    Page<OrderItem> findSellerOrderItems(@Param("brandId") Long brandId, Pageable pageable);

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
