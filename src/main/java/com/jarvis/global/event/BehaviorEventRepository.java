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

    interface ProductVisitorRow {
        Long getProductId();
        Long getVisitors();
    }

    interface DateTypeCountRow {
        String getDay();
        String getEventType();
        Long getCnt();
    }

    interface LastActivityRow {
        Long getMemberId();
        LocalDateTime getLastActivity();
    }

    interface LastEventRow {
        Long getMemberId();
        String getEventType();
    }

    interface MemberCntRow {
        Long getMemberId();
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

    interface CheckoutRow {
        LocalDateTime getCreatedAt();
        String getProperties();
    }

    /**
     * I-7 3단·I-13 checkout_start (04 §10) — checkout_start는 product_id 컬럼이 비어 있고
     * properties.productIds(JSON 배열)로만 상품에 귀속된다. 브랜드 필터를 SQL에서 끝내려고
     * JSON_OVERLAPS(MariaDB 10.9+)로 자사 상품 id 집합과 교집합이 있는 행만 가져온다
     * (기간 내 전 브랜드 이벤트를 앱으로 끌어오던 방식 대체 — 2026-07-18).
     * 매칭된 상품 id 산출은 호출부가 properties를 파싱해 계속 담당(숫자 노드만 인정 — 종전 동작 유지).
     */
    @Query(value = """
            SELECT be.created_at AS createdAt, be.properties AS properties
            FROM behavior_events be
            WHERE be.event_type = 'checkout_start'
              AND be.created_at >= :from AND be.created_at < :to
              AND JSON_OVERLAPS(JSON_EXTRACT(be.properties, '$.productIds'), :targetIdsJson)
            """, nativeQuery = true)
    List<CheckoutRow> findBrandCheckouts(@Param("targetIdsJson") String targetIdsJson,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /** I-13 상품×타입 집계 — product_id 컬럼 기반(checkout_start는 서비스에서 JSON 판정) */
    @Query(value = """
            SELECT be.product_id AS productId, be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN (:types)
              AND (:productId IS NULL OR be.product_id = :productId)
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY be.product_id, be.event_type
            """, nativeQuery = true)
    List<ProductTypeCountRow> countSellerEventsByProductType(@Param("brandId") Long brandId,
                                                             @Param("types") Collection<String> types,
                                                             @Param("productId") Long productId,
                                                             @Param("from") LocalDateTime from,
                                                             @Param("to") LocalDateTime to);

    /** I-13 groupBy=eventType — 타입별 합계 (product_id 컬럼 기반) */
    @Query(value = """
            SELECT be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN (:types)
              AND (:productId IS NULL OR be.product_id = :productId)
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY be.event_type
            """, nativeQuery = true)
    List<TypeCountRow> countSellerEventsByType(@Param("brandId") Long brandId,
                                               @Param("types") Collection<String> types,
                                               @Param("productId") Long productId,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    /** I-13 uniqueVisitors — distinct(member_id, guest_id), 게스트 포함(둘 다 없으면 session_key로 근사) */
    @Query(value = """
            SELECT be.product_id AS productId,
                   COUNT(DISTINCT COALESCE(CAST(be.member_id AS CHAR), be.guest_id, be.session_key)) AS visitors
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN (:types)
              AND (:productId IS NULL OR be.product_id = :productId)
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY be.product_id
            """, nativeQuery = true)
    List<ProductVisitorRow> countSellerVisitorsByProduct(@Param("brandId") Long brandId,
                                                         @Param("types") Collection<String> types,
                                                         @Param("productId") Long productId,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);

    /** I-13 groupBy=date — 일자×타입 시계열 (product_id 컬럼 기반) */
    @Query(value = """
            SELECT DATE_FORMAT(be.created_at, '%Y-%m-%d') AS day, be.event_type AS eventType,
                   COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.event_type IN (:types)
              AND (:productId IS NULL OR be.product_id = :productId)
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY day, be.event_type
            """, nativeQuery = true)
    List<DateTypeCountRow> countSellerEventsByDateType(@Param("brandId") Long brandId,
                                                       @Param("types") Collection<String> types,
                                                       @Param("productId") Long productId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /** I-16 코호트 — 기간 내 자사 상품과 상호작용(product_id 연계 이벤트)한 회원 (노션 I-16) */
    @Query(value = """
            SELECT DISTINCT be.member_id
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.member_id IS NOT NULL
              AND be.created_at >= :from AND be.created_at < :to
            """, nativeQuery = true)
    List<Long> findChurnCohortMemberIds(@Param("brandId") Long brandId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    /** I-16 lastActivityAt — 브랜드 무관 전체 behavior_events 기준(무활동 판정의 분모) */
    @Query(value = """
            SELECT be.member_id AS memberId, MAX(be.created_at) AS lastActivity
            FROM behavior_events be
            WHERE be.member_id IN (:memberIds)
            GROUP BY be.member_id
            """, nativeQuery = true)
    List<LastActivityRow> findLastActivities(@Param("memberIds") Collection<Long> memberIds);

    /** I-16 preChurnEvent 폴백 재료 — 회원별 마지막 이벤트 타입(동시각 중복은 서비스에서 첫 행 채택) */
    @Query(value = """
            SELECT be.member_id AS memberId, be.event_type AS eventType
            FROM behavior_events be
            JOIN (SELECT member_id, MAX(created_at) AS last_at
                  FROM behavior_events
                  WHERE member_id IN (:memberIds)
                  GROUP BY member_id) t
              ON t.member_id = be.member_id AND t.last_at = be.created_at
            """, nativeQuery = true)
    List<LastEventRow> findLastEventTypes(@Param("memberIds") Collection<Long> memberIds);

    /** I-16 sessions30d — 최근 30일 session_start distinct 세션 수 */
    @Query(value = """
            SELECT be.member_id AS memberId, COUNT(DISTINCT be.session_key) AS cnt
            FROM behavior_events be
            WHERE be.member_id IN (:memberIds)
              AND be.event_type = 'session_start'
              AND be.created_at >= :since
            GROUP BY be.member_id
            """, nativeQuery = true)
    List<MemberCntRow> countRecentSessions(@Param("memberIds") Collection<Long> memberIds,
                                           @Param("since") LocalDateTime since);

    /** I-16 priceIncreaseExposed — PRICE 인상 기록 이후 해당 상품을 조회한 이탈 회원 수(근사) */
    @Query(value = """
            SELECT COUNT(DISTINCT be.member_id)
            FROM behavior_events be
            JOIN product_change_logs pcl ON pcl.product_id = be.product_id
            JOIN product p ON p.id = pcl.product_id AND p.brand_id = :brandId
            WHERE be.member_id IN (:memberIds)
              AND be.event_type = 'product_view'
              AND pcl.change_type = 'PRICE'
              AND pcl.old_value IS NOT NULL
              AND CAST(pcl.new_value AS DECIMAL(12,0)) > CAST(pcl.old_value AS DECIMAL(12,0))
              AND be.created_at >= pcl.created_at
            """, nativeQuery = true)
    long countPriceIncreaseExposedMembers(@Param("brandId") Long brandId,
                                          @Param("memberIds") Collection<Long> memberIds);
}
