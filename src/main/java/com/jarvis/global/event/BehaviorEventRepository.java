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

    // countSellerEventsByProduct는 2026-08-06 제거 — 유일한 소비처였던 S-1 products[]가 사라졌다.

    /**
     * S-1 실시간 방문자 — 최근 30분 자사 상품 관련 이벤트의 distinct session_key (노션 S-1).
     * session_key는 30분 무활동 시 재발급이라 "실시간 접속" 정의와 정합.
     *
     * <p><b>챗봇 sentinel 제외</b>(2026-08-10 정정) — 챗봇 경로의 서버 적재 이벤트는 session_key가
     * {@code chat:{채팅sessionId}}로, 브라우저 세션과 <b>ID 공간이 다르다</b>(노션 E-1·I-2 한계 ①).
     * 세지 않고 두면 챗봇으로 담은 사람이 자기 브라우저 세션과 별개로 한 번 더 세어져 방문자가 부풀었다.
     * 접두사는 {@code CartEventRecorder.CHAT_SESSION_KEY_PREFIX}이고, 스트림 집계(08 D4)도 같은 기준으로
     * 거른다 — <b>두 경로가 같은 숫자를 내야 폴백이 값을 바꾸지 않는다.</b>
     *
     * <p>스트림이 살아 있으면 이 쿼리는 돌지 않는다(08 D5 폴백 전용).
     */
    @Query(value = """
            SELECT COUNT(DISTINCT be.session_key)
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            WHERE be.created_at >= :since
              AND be.session_key NOT LIKE 'chat:%'
            """, nativeQuery = true)
    long countActiveVisitors(@Param("brandId") Long brandId, @Param("since") LocalDateTime since);

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

    /**
     * I-13 uniqueVisitors — distinct(회원, 게스트), 게스트 포함(둘 다 없으면 session_key로 근사).
     * 승계된 게스트 구간은 귀속 계정으로 접어 센다(GUEST-LIFECYCLE) — 게스트 신원이 2시간마다 회전하므로
     * 접지 않으면 "로그아웃 상태로 보다가 로그인해 산 한 사람"이 둘로 세어진다. 끝내 로그인하지 않은
     * 게스트는 구간마다 별개로 남는데, 그건 신원을 밝히지 않은 방문이라 합칠 근거가 없다.
     */
    @Query(value = """
            SELECT be.product_id AS productId,
                   COUNT(DISTINCT COALESCE(CAST(be.member_id AS CHAR),
                                           CAST(g.converted_member_id AS CHAR),
                                           be.guest_id, be.session_key)) AS visitors
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            LEFT JOIN guest g ON g.id = be.guest_id
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

    /**
     * I-16 코호트 — 기간 내 자사 상품과 상호작용(product_id 연계 이벤트)한 회원 (노션 I-16).
     * 회원 이벤트 + 그 회원에게 귀속된 게스트 구간의 이벤트를 함께 본다 — 백필을 폐기하면서 로그를
     * 고쳐 쓰지 않기로 했으므로, 로그인 전 탐색은 guest.converted_member_id로만 회원과 이어진다
     * (GUEST-LIFECYCLE). 접지 않으면 가입 직전에 한참 둘러본 회원이 코호트에서 통째로 빠진다.
     */
    @Query(value = """
            SELECT DISTINCT COALESCE(be.member_id, g.converted_member_id)
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IS NOT NULL
              AND be.created_at >= :from AND be.created_at < :to
            """, nativeQuery = true)
    List<Long> findChurnCohortMemberIds(@Param("brandId") Long brandId,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    /**
     * I-16 lastActivityAt — 브랜드 무관 전체 behavior_events 기준(무활동 판정의 분모).
     * 귀속된 게스트 구간을 포함하지 않으면 로그아웃 상태로 계속 둘러본 회원이 "무활동"으로 잡혀
     * 이탈로 오판된다(GUEST-LIFECYCLE 연결 규칙).
     */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   MAX(be.created_at) AS lastActivity
            FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IN (:memberIds)
            GROUP BY COALESCE(be.member_id, g.converted_member_id)
            """, nativeQuery = true)
    List<LastActivityRow> findLastActivities(@Param("memberIds") Collection<Long> memberIds);

    /** I-16 preChurnEvent 폴백 재료 — 회원별 마지막 이벤트 타입(동시각 중복은 서비스에서 첫 행 채택) */
    @Query(value = """
            SELECT t.member_id AS memberId, be.event_type AS eventType
            FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            JOIN (SELECT COALESCE(be2.member_id, g2.converted_member_id) AS member_id,
                         MAX(be2.created_at) AS last_at
                  FROM behavior_events be2
                  LEFT JOIN guest g2 ON g2.id = be2.guest_id
                  WHERE COALESCE(be2.member_id, g2.converted_member_id) IN (:memberIds)
                  GROUP BY COALESCE(be2.member_id, g2.converted_member_id)) t
              ON t.member_id = COALESCE(be.member_id, g.converted_member_id)
             AND t.last_at = be.created_at
            """, nativeQuery = true)
    List<LastEventRow> findLastEventTypes(@Param("memberIds") Collection<Long> memberIds);

    /** I-16 sessions30d — 최근 30일 session_start distinct 세션 수 (귀속된 게스트 구간 포함) */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   COUNT(DISTINCT be.session_key) AS cnt
            FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IN (:memberIds)
              AND be.event_type = 'session_start'
              AND be.created_at >= :since
            GROUP BY COALESCE(be.member_id, g.converted_member_id)
            """, nativeQuery = true)
    List<MemberCntRow> countRecentSessions(@Param("memberIds") Collection<Long> memberIds,
                                           @Param("since") LocalDateTime since);

    /** I-16 priceIncreaseExposed — PRICE 인상 기록 이후 해당 상품을 조회한 이탈 회원 수(근사) */
    @Query(value = """
            SELECT COUNT(DISTINCT COALESCE(be.member_id, g.converted_member_id))
            FROM behavior_events be
            JOIN product_change_logs pcl ON pcl.product_id = be.product_id
            JOIN product p ON p.id = pcl.product_id AND p.brand_id = :brandId
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IN (:memberIds)
              AND be.event_type = 'product_view'
              AND pcl.change_type = 'PRICE'
              AND pcl.old_value IS NOT NULL
              AND CAST(pcl.new_value AS DECIMAL(12,0)) > CAST(pcl.old_value AS DECIMAL(12,0))
              AND be.created_at >= pcl.created_at
            """, nativeQuery = true)
    long countPriceIncreaseExposedMembers(@Param("brandId") Long brandId,
                                          @Param("memberIds") Collection<Long> memberIds);

    interface DwellSampleRow {
        Long getProductId();
        Long getDwellSeconds();
    }

    /**
     * I-13 체류시간 표본 (노션 I-13 2026-08-06 신설) — 같은 {@code session_key} 안에서
     * {@code product_view}부터 <b>다음 이벤트까지</b>의 초 차이다. 끝 이벤트 종류는 한정하지 않는다 —
     * 담기든 타 상품 조회든 주문서 진입이든 그게 이탈 시각이다.
     *
     * <p><b>{@code page_leave}를 수집하지 않아 세션의 마지막 조회는 표본에서 빠진다.</b> 구조적
     * 한계이며 버그가 아니다 — 응답의 {@code dwellSource}가 이 사실을 표기한다.
     *
     * <p>이상치 3종을 여기서 거른다 — ① {@code properties._timeShifted}(브라우저 시계를 못 믿어
     * 서버가 시각을 대체한 행) ② 음수(시계 역전 잔여분) ③ 1800초 초과(세션 30분 무활동 재발급과 정합).
     * 상한은 <b>BE 고정</b>이다 — 호출마다 달라지면 같은 질문에 다른 숫자가 나온다.
     *
     * <p>중앙값·평균은 서비스에서 계산한다. SQL 창함수로 접으면 이 저장소엔 DB 테스트가 없어
     * <b>숫자가 맞는지 증명할 방법이 없다</b> — 표본을 그대로 받아 Java에서 접는 편이 검증 가능하다.
     *
     * <p>챗봇 sentinel 세션({@code chat:...})은 자연히 빠진다 — 그 세션엔 {@code product_view}가
     * 없기 때문이다(FE만 발화한다). 브라우저 세션에서 조회한 뒤 챗봇으로 담으면 두 이벤트가 다른
     * {@code session_key}라 이어지지 않아 그 표본이 통째로 누락된다(노션 I-2 한계 ②).
     */
    @Query(value = """
            SELECT s.product_id AS productId,
                   TIMESTAMPDIFF(SECOND, s.occurred_at, s.next_at) AS dwellSeconds
            FROM (SELECT be.product_id, be.event_type, be.occurred_at, be.properties,
                         LEAD(be.occurred_at) OVER (
                             PARTITION BY be.session_key ORDER BY be.occurred_at, be.id) AS next_at
                  FROM behavior_events be
                  WHERE be.created_at >= :from AND be.created_at < :to) s
            JOIN product p ON p.id = s.product_id AND p.brand_id = :brandId
            WHERE s.event_type = 'product_view'
              AND s.next_at IS NOT NULL
              AND (:productId IS NULL OR s.product_id = :productId)
              AND COALESCE(JSON_EXTRACT(s.properties, '$._timeShifted'), FALSE) <> TRUE
              AND TIMESTAMPDIFF(SECOND, s.occurred_at, s.next_at) BETWEEN 0 AND :maxSeconds
            """, nativeQuery = true)
    List<DwellSampleRow> findDwellSamples(@Param("brandId") Long brandId,
                                          @Param("productId") Long productId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to,
                                          @Param("maxSeconds") int maxSeconds);

    interface RecommendationFunnelRow {
        Long getImpression();
        Long getClick();
        Long getAddToCart();
    }

    /**
     * S-1 추천 퍼널 3단 (「AI 추천 성과」) — 노출 → 클릭 → 담기. 구매 단은 주문 정본이라
     * {@code OrderItemRepository}가 따로 센다(I-13 purchaseComplete와 같은 이유 — 이벤트로는
     * 상품 귀속이 구조적으로 불가능하다).
     *
     * <p>{@code AI_RECOMMENDED} 목록에 귀속된 이벤트만 센다 — P-5 대체분({@code POPULAR_FALLBACK})은
     * AI가 뽑은 게 아니라 제외다. 자사 스코프는 {@code product_id} 컬럼 조인이며, 세 이벤트 모두
     * 그 컬럼이 채워져 있어 귀속 경로가 하나다.
     */
    @Query(value = """
            SELECT SUM(CASE WHEN be.event_type = 'product_visible' THEN 1 ELSE 0 END) AS impression,
                   SUM(CASE WHEN be.event_type = 'product_click' THEN 1 ELSE 0 END) AS click,
                   SUM(CASE WHEN be.event_type = 'add_to_cart' THEN 1 ELSE 0 END) AS addToCart
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            JOIN recommendation_list rl ON rl.list_id = be.list_id AND rl.source = 'AI_RECOMMENDED'
            WHERE be.event_type IN ('product_visible', 'product_click', 'add_to_cart')
              AND be.created_at >= :from AND be.created_at < :to
            """, nativeQuery = true)
    RecommendationFunnelRow aggregateRecommendationFunnel(@Param("brandId") Long brandId,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    interface MemberTypeCountRow {
        Long getMemberId();
        String getEventType();
        Long getCnt();
    }

    interface ActivitySpanRow {
        Long getMemberId();
        LocalDateTime getFirstSeen();
        LocalDateTime getLastActivity();
    }

    /**
     * I-38 회원×타입 이벤트 집계 — {@code product_id} 컬럼으로 브랜드에 귀속되는 타입만
     * ({@code product_view}·{@code add_to_cart}). {@code checkout_start}는 그 컬럼이 비어 있어
     * {@link #countCustomerCheckoutStarts}가 JSON으로 따로 센다(I-13과 같은 귀속 경로 분리).
     *
     * <p>고객 식별은 I-16 코호트와 같은 {@code COALESCE(member_id, converted_member_id)}다 —
     * 접지 않으면 로그인 전 탐색이 통째로 빠져 같은 사람의 피처가 둘로 갈린다.
     */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   be.event_type AS eventType, COUNT(*) AS cnt
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE be.event_type IN (:types)
              AND COALESCE(be.member_id, g.converted_member_id) IS NOT NULL
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY COALESCE(be.member_id, g.converted_member_id), be.event_type
            """, nativeQuery = true)
    List<MemberTypeCountRow> countCustomerEventsByType(@Param("brandId") Long brandId,
                                                       @Param("types") Collection<String> types,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    interface MemberCheckoutRow {
        Long getMemberId();
        String getProperties();
    }

    /**
     * I-38 회원별 {@code checkout_start} 재료 — 자사 상품이 담긴 주문서 진입(주문서 1회=1, 02 §4).
     *
     * <p>{@link #findBrandCheckouts}와 같은 구조다: 브랜드 필터는 {@code JSON_OVERLAPS}로 SQL에서
     * 끝내되 <b>매칭 판정은 호출부가 properties를 파싱해 다시 한다</b>(숫자 노드만 인정). SQL에서
     * 바로 세면 I-7 3단·I-13 {@code checkoutStart}와 판정 기준이 미세하게 갈려 같은 기간에 다른
     * 숫자가 나온다.
     */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   be.properties AS properties
            FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE be.event_type = 'checkout_start'
              AND COALESCE(be.member_id, g.converted_member_id) IS NOT NULL
              AND be.created_at >= :from AND be.created_at < :to
              AND JSON_OVERLAPS(JSON_EXTRACT(be.properties, '$.productIds'), :targetIdsJson)
            """, nativeQuery = true)
    List<MemberCheckoutRow> findCustomerCheckouts(@Param("targetIdsJson") String targetIdsJson,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to);

    /**
     * I-38 sessions — 기간 내 {@code session_start}의 distinct {@code session_key}
     * (I-16 {@code sessions30d}와 같은 정의, 기간만 {@code from}~{@code to}).
     *
     * <p>{@code session_start}엔 {@code product_id}가 없어 브랜드로 좁힐 수 없다 — 그래서 자사
     * 코호트로 이미 추린 {@code memberIds}로만 거른다.
     */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   COUNT(DISTINCT be.session_key) AS cnt
            FROM behavior_events be
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IN (:memberIds)
              AND be.event_type = 'session_start'
              AND be.created_at >= :from AND be.created_at < :to
            GROUP BY COALESCE(be.member_id, g.converted_member_id)
            """, nativeQuery = true)
    List<MemberCntRow> countCustomerSessions(@Param("memberIds") Collection<Long> memberIds,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * I-38 recency·tenure 재료 — <b>자사 브랜드</b> 상호작용의 처음·마지막 시각.
     *
     * <p>브랜드 무관으로 세면 이 판매자에게 타 브랜드 활동 여부가 새어 나간다(I-16이 브랜드 무관인
     * 건 "정말 무활동인가"를 판정하는 다른 목적 때문이다). 하한을 걸지 않아 {@code firstSeen}은
     * {@code from} 이전까지 거슬러 가고(tenure), 상한만 {@code to}라 recency가 음수가 되지 않는다.
     */
    @Query(value = """
            SELECT COALESCE(be.member_id, g.converted_member_id) AS memberId,
                   MIN(be.created_at) AS firstSeen, MAX(be.created_at) AS lastActivity
            FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.brand_id = :brandId
            LEFT JOIN guest g ON g.id = be.guest_id
            WHERE COALESCE(be.member_id, g.converted_member_id) IN (:memberIds)
              AND be.created_at < :to
            GROUP BY COALESCE(be.member_id, g.converted_member_id)
            """, nativeQuery = true)
    List<ActivitySpanRow> findCustomerActivitySpans(@Param("brandId") Long brandId,
                                                    @Param("memberIds") Collection<Long> memberIds,
                                                    @Param("to") LocalDateTime to);
}
