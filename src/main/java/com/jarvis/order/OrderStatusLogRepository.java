package com.jarvis.order;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, Long> {

    interface StatusCountRow {
        String getBucket();
        Long getCnt();
    }

    interface OrderEventRow {
        Long getOrderId();
        String getFromStatus();
        String getToStatus();
        String getActorType();
        String getReason();
        Long getBuyerMemberId();
        LocalDateTime getCreatedAt();
    }

    interface MemberAggRow {
        Long getMemberId();
        Long getOrderCount();
        Long getCancelCount();
    }

    interface MemberHourRow {
        Long getMemberId();
        Long getMaxPerHour();
    }

    interface ReasonCountRow {
        String getReason();
        Long getCnt();
    }

    interface ClaimRow {
        Long getMemberId();
        String getToStatus();
        String getReason();
    }

    /**
     * S-1 평균 배송 소요(초) — 자사 주문의 SHIPPING→DELIVERED 전이 시각 차 평균. 전이 로그가 없으면 null.
     * 로그는 주문 단위 1행(D32)이라 order_id 자기조인으로 SHIPPING·DELIVERED 시각을 짝짓는다. 모의 배송 값.
     */
    @Query(value = """
            SELECT AVG(TIMESTAMPDIFF(SECOND, s.created_at, d.created_at))
            FROM order_status_logs s
            JOIN order_status_logs d ON d.order_id = s.order_id AND d.to_status = 'DELIVERED'
            WHERE s.to_status = 'SHIPPING'
              AND s.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
            """, nativeQuery = true)
    Double avgSellerDeliverySeconds(@Param("brandId") Long brandId);

    /**
     * I-14 자사 주문 전이 로그 (04 §10, 노션 I-14) — 브랜드 스코프는 주문에 자사 아이템 포함 여부.
     * buyerMemberId는 orders 조인으로 부착. applyToStatus=false면 toStatuses는 센티널(빈 IN 방지).
     */
    @Query(value = """
            SELECT l.order_id AS orderId, l.from_status AS fromStatus, l.to_status AS toStatus,
                   l.actor_type AS actorType, l.reason AS reason, o.member_id AS buyerMemberId,
                   l.created_at AS createdAt
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            ORDER BY l.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<OrderEventRow> findSellerOrderEvents(@Param("brandId") Long brandId,
                                              @Param("applyToStatus") boolean applyToStatus,
                                              @Param("toStatuses") Collection<String> toStatuses,
                                              @Param("actorType") String actorType,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("limit") int limit);

    /** I-14 total — rows와 같은 조건의 전체 건수(LIMIT 미적용) */
    @Query(value = """
            SELECT COUNT(*)
            FROM order_status_logs l
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            """, nativeQuery = true)
    long countSellerOrderEvents(@Param("brandId") Long brandId,
                                @Param("applyToStatus") boolean applyToStatus,
                                @Param("toStatuses") Collection<String> toStatuses,
                                @Param("actorType") String actorType,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    /** I-14 stats — to_status 분포 (04 §10) */
    @Query(value = """
            SELECT l.to_status AS bucket, COUNT(*) AS cnt
            FROM order_status_logs l
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.to_status
            """, nativeQuery = true)
    List<StatusCountRow> countSellerOrderEventsByStatus(@Param("brandId") Long brandId,
                                                        @Param("applyToStatus") boolean applyToStatus,
                                                        @Param("toStatuses") Collection<String> toStatuses,
                                                        @Param("actorType") String actorType,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to);

    /** I-14 stats cancelReasonsTop — 취소·반품 reason 상위(신청/확정 어느 쪽에 사유가 남아도 잡히게 4종) */
    @Query(value = """
            SELECT l.reason AS reason, COUNT(*) AS cnt
            FROM order_status_logs l
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND l.to_status IN ('CANCEL_REQUESTED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED')
              AND l.reason IS NOT NULL
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.reason
            ORDER BY cnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ReasonCountRow> findTopCancelReasons(@Param("brandId") Long brandId,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("limit") int limit);

    /** I-14 groupBy=memberId — 회원별 주문·취소 집계(어뷰징 탐지: 반복 취소) (노션 I-14) */
    @Query(value = """
            SELECT o.member_id AS memberId,
                   COUNT(DISTINCT l.order_id) AS orderCount,
                   COUNT(DISTINCT CASE WHEN l.to_status = 'CANCELLED' THEN l.order_id END) AS cancelCount
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY o.member_id
            ORDER BY cancelCount DESC, orderCount DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MemberAggRow> aggregateSellerOrderEventsByMember(@Param("brandId") Long brandId,
                                                          @Param("applyToStatus") boolean applyToStatus,
                                                          @Param("toStatuses") Collection<String> toStatuses,
                                                          @Param("actorType") String actorType,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          @Param("limit") int limit);

    /** I-14 maxOrdersPerHour — 회원별 시간당 distinct 주문 전이 수의 최대(폭주 주문 근사) */
    @Query(value = """
            SELECT hh.member_id AS memberId, MAX(hh.cnt) AS maxPerHour
            FROM (SELECT o.member_id AS member_id,
                         DATE_FORMAT(l.created_at, '%Y-%m-%d %H') AS hr,
                         COUNT(DISTINCT l.order_id) AS cnt
                  FROM order_status_logs l
                  JOIN orders o ON o.id = l.order_id
                  WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                       JOIN product p ON p.id = oi.product_id
                                       WHERE p.brand_id = :brandId)
                    AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
                    AND (:actorType IS NULL OR l.actor_type = :actorType)
                    AND l.created_at >= :from AND l.created_at < :to
                  GROUP BY o.member_id, hr) hh
            GROUP BY hh.member_id
            """, nativeQuery = true)
    List<MemberHourRow> maxSellerOrdersPerHourByMember(@Param("brandId") Long brandId,
                                                       @Param("applyToStatus") boolean applyToStatus,
                                                       @Param("toStatuses") Collection<String> toStatuses,
                                                       @Param("actorType") String actorType,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /** I-16 preChurnSignals.cancelCount — 이탈 회원들의 자사 스코프 취소 주문 수 (노션 I-16) */
    @Query(value = """
            SELECT COUNT(DISTINCT l.order_id)
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE o.member_id IN (:memberIds)
              AND l.to_status = 'CANCELLED'
              AND l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
            """, nativeQuery = true)
    long countChurnedMemberCancels(@Param("brandId") Long brandId,
                                   @Param("memberIds") Collection<Long> memberIds);

    /** I-16 preChurnSignals.returnReasonsTop — 이탈 회원들의 반품 reason 상위 */
    @Query(value = """
            SELECT l.reason AS reason, COUNT(*) AS cnt
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE o.member_id IN (:memberIds)
              AND l.to_status IN ('RETURN_REQUESTED', 'RETURNED')
              AND l.reason IS NOT NULL
              AND l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
            GROUP BY l.reason
            ORDER BY cnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ReasonCountRow> findChurnedMemberReturnReasons(@Param("brandId") Long brandId,
                                                        @Param("memberIds") Collection<Long> memberIds,
                                                        @Param("limit") int limit);

    /** I-16 preChurnEvent — 이탈 회원별 클레임 로그(최신순 — 서비스에서 회원별 첫 행 채택) */
    @Query(value = """
            SELECT o.member_id AS memberId, l.to_status AS toStatus, l.reason AS reason
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE o.member_id IN (:memberIds)
              AND l.to_status IN ('CANCEL_REQUESTED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED')
              AND l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
            ORDER BY l.created_at DESC
            """, nativeQuery = true)
    List<ClaimRow> findChurnedMemberClaims(@Param("brandId") Long brandId,
                                           @Param("memberIds") Collection<Long> memberIds);
}
