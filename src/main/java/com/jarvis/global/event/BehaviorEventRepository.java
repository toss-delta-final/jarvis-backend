package com.jarvis.global.event;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BehaviorEventRepository extends JpaRepository<BehaviorEvent, Long> {

    /** INSERT 전 중복 검증용 — INSERT IGNORE 금지 (02 D35) */
    @Query("select be.clientEventId from BehaviorEvent be where be.clientEventId in :ids")
    List<String> findExistingClientEventIds(@Param("ids") Collection<String> ids);

    interface TypeCountRow {
        String getEventType();
        Long getCnt();
    }

    interface ProductTypeCountRow {
        Long getProductId();
        String getEventType();
        Long getCnt();
    }

    interface MemberTypeCountRow {
        Long getMemberId();
        String getEventType();
        Long getCnt();
    }

    /** I-7 퍼널 1·2단 — 자사 상품의 product_view/add_to_cart (02 §4) */
    @Query(value = """
            SELECT be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN ('product_view', 'add_to_cart')
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY be.event_type
            """, nativeQuery = true)
    List<TypeCountRow> countSellerFunnelEvents(@Param("brandId") Long brandId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    /** S-1 상품별 조회수·담김수 (04 §7) */
    @Query(value = """
            SELECT be.product_id AS productId, be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN ('product_view', 'add_to_cart')
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY be.product_id, be.event_type
            """, nativeQuery = true)
    List<ProductTypeCountRow> countSellerEventsByProduct(@Param("brandId") Long brandId,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);

    /** I-7 3단 — checkout_start의 properties.productIds 포함 여부는 Java 측 판정 (04 §10) */
    List<BehaviorEvent> findAllByEventTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String eventType, LocalDateTime from, LocalDateTime to);

    /** I-16 preChurnSignals — 마지막 로그인 이전 30일 행동 집계 (04 §10) */
    @Query(value = """
            SELECT be.member_id AS memberId, be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN (SELECT member_id, MAX(created_at) AS last_login
                  FROM account_event_logs
                  WHERE event_type = 'LOGIN_SUCCESS'
                  GROUP BY member_id) ll ON ll.member_id = be.member_id
            WHERE be.member_id IN (:memberIds)
              AND be.created_at BETWEEN ll.last_login - INTERVAL 30 DAY AND ll.last_login
            GROUP BY be.member_id, be.event_type
            """, nativeQuery = true)
    List<MemberTypeCountRow> countPreChurnSignals(@Param("memberIds") Collection<Long> memberIds);
}
