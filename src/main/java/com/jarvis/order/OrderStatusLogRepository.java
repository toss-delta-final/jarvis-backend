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

    interface MemberCountRow {
        Long getMemberId();
        Long getCnt();
    }

    /**
     * I-14 자사 주문 전이 로그 (04 §10) — 브랜드 스코프는 주문에 자사 아이템 포함 여부.
     * applyToStatus=false면 toStatuses는 센티널(빈 IN 방지 — searchCandidates와 같은 관성).
     */
    @Query(value = """
            SELECT l.* FROM order_status_logs l
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            ORDER BY l.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<OrderStatusLog> findSellerOrderEvents(@Param("brandId") Long brandId,
                                               @Param("applyToStatus") boolean applyToStatus,
                                               @Param("toStatuses") Collection<String> toStatuses,
                                               @Param("actorType") String actorType,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to,
                                               @Param("limit") int limit);

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

    /** I-14 groupBy=memberId — 회원별 전이 횟수(어뷰징 탐지: 반복 취소·반품) (04 §10) */
    @Query(value = """
            SELECT o.member_id AS memberId, COUNT(*) AS cnt
            FROM order_status_logs l
            JOIN orders o ON o.id = l.order_id
            WHERE l.order_id IN (SELECT DISTINCT oi.order_id FROM order_item oi
                                 JOIN product p ON p.id = oi.product_id
                                 WHERE p.brand_id = :brandId)
              AND (:applyToStatus = false OR l.to_status IN (:toStatuses))
              AND (:actorType IS NULL OR l.actor_type = :actorType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY o.member_id
            ORDER BY cnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MemberCountRow> countSellerOrderEventsByMember(@Param("brandId") Long brandId,
                                                        @Param("applyToStatus") boolean applyToStatus,
                                                        @Param("toStatuses") Collection<String> toStatuses,
                                                        @Param("actorType") String actorType,
                                                        @Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to,
                                                        @Param("limit") int limit);
}
