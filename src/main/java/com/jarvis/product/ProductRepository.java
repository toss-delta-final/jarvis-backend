package com.jarvis.product;

import com.jarvis.product.dto.CandidateRow;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * 판매자 수정 진입점 전용 비관적 쓰기 락 — 절대값 재고 수정이 주문 조건부 차감(D33)과 경합해
     * 판매분을 유령 복원하는 lost update를 막는다. 락 안에서 읽은 현재 재고가 change log old_value가 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    // 재고 차감·복원·조회는 ProductStockRepository로 이관 (02 D33 개정, 2026-08-09)

    interface ProductBrandRow {
        Long getProductId();
        Long getBrandId();
    }

    /**
     * 상품→브랜드 매핑 전량 (08 D4) — 실시간 방문자 컨슈머가 기동 시 메모리에 얹어두고 쓴다.
     * 이벤트마다 조회하면 컨슈머가 커넥션을 상시 물어 RDS 여유(2)를 먹는다.
     */
    @Query(value = "SELECT p.id AS productId, p.brand_id AS brandId FROM product p", nativeQuery = true)
    List<ProductBrandRow> findAllProductBrandIds();

    /**
     * I-17 상품 변경분 커서 조회 (05 §I-17) — (updated_at, id) 복합 인덱스 keyset 페이지네이션.
     * 커서 이후 조건: updatedAt > cur.updatedAt OR (= AND id > cur.id). since="0"이면 cursor=null →
     * 전체(처음부터). ON_SALE/HIDDEN 모두 포함(HIDDEN도 AI가 생성물을 삭제해야 하므로).
     */
    @Query("""
            select p from Product p
            where :cursorUpdatedAt is null
               or p.updatedAt > :cursorUpdatedAt
               or (p.updatedAt = :cursorUpdatedAt and p.id > :cursorId)
            order by p.updatedAt asc, p.id asc
            """)
    List<Product> findChangesSince(@Param("cursorUpdatedAt") LocalDateTime cursorUpdatedAt,
                                   @Param("cursorId") Long cursorId, Pageable pageable);

    /**
     * S-1 상품별 지표 조인용 — 판매자 브랜드는 시드 규모가 작아 전건 로드 (04 §7).
     *
     * <p><b>삭제분을 포함한다.</b> 대시보드·통계(S-1·S-4)는 과거 집계라 삭제된 상품의 지난 매출·이벤트가
     * 사라지면 안 된다. 판매자 상품 <i>목록</i>(S-3)만 삭제분을 빼야 하므로 아래 별도 메서드를 쓴다.
     */
    List<Product> findAllByBrandId(Long brandId);

    /** S-3 판매자 상품 목록 전용 — 삭제분 제외 (status &lt;&gt; DELETED). 집계 경로와 갈라놓은 이유는 위 참조 */
    List<Product> findAllByBrandIdAndStatusNot(Long brandId, ProductStatus status);

    // S-1 재고 부족 알림은 ProductStockRepository#findLowStock으로 이관 — 옵션 단위가 됐다 (02 D33 개정)

    Page<Product> findAllByBrandIdAndStatus(Long brandId, ProductStatus status, Pageable pageable);

    Page<Product> findAllByBrandIdAndCategoryIdAndStatus(Long brandId, Long categoryId,
                                                         ProductStatus status, Pageable pageable);

    /** P-6 브랜드홈 필터 축 — 판매 중 상품이 속한 소분류 (02 D20) */
    @Query("""
            select distinct p.categoryId from Product p
            where p.brandId = :brandId and p.status = com.jarvis.product.ProductStatus.ON_SALE
            """)
    List<Long> findCategoryIdsByBrand(@Param("brandId") Long brandId);

    /**
     * P-6 popular 정렬 — 표시 판매량 = base_sales_count + order_item×PAID 집계 (02 D18).
     * order 도메인은 Phase 3 — 엔티티 없이 테이블 집계라 네이티브 (03 §4 JdbcTemplate 허용과 같은 근거).
     */
    @Query(value = """
            SELECT p.* FROM product p
            LEFT JOIN (SELECT oi.product_id AS pid, SUM(oi.quantity) AS sold
                       FROM order_item oi
                       JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID'
                       GROUP BY oi.product_id) s ON s.pid = p.id
            WHERE p.brand_id = :brandId
              AND p.status = 'ON_SALE'
              AND (:categoryId IS NULL OR p.category_id = :categoryId)
            ORDER BY (p.base_sales_count + COALESCE(s.sold, 0)) DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM product p
            WHERE p.brand_id = :brandId
              AND p.status = 'ON_SALE'
              AND (:categoryId IS NULL OR p.category_id = :categoryId)
            """,
            nativeQuery = true)
    Page<Product> findBrandProductsOrderByPopularity(@Param("brandId") Long brandId,
                                                     @Param("categoryId") Long categoryId,
                                                     Pageable pageable);

    /** P-4 1순위 — 최근 7일 판매수 상위 (04 §2). 품절(재고 0)은 제외 — 메인 노출 상품은 살 수 있어야 한다(노션 P-4 2026-07-28) */
    @Query(value = """
            SELECT oi.product_id FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID' AND o.paid_at >= :since
            JOIN product p ON p.id = oi.product_id AND p.status = 'ON_SALE'
            WHERE EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id AND ps.quantity > 0)
            GROUP BY oi.product_id
            ORDER BY SUM(oi.quantity) DESC, oi.product_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPopularIdsBySales(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** P-4 2순위 — behavior_events product_view 수 (04 §2). 1순위와 같은 이유로 품절 제외 */
    @Query(value = """
            SELECT be.product_id FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.status = 'ON_SALE'
            WHERE EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id AND ps.quantity > 0)
              AND be.event_type = 'product_view' AND be.created_at >= :since
              AND be.product_id NOT IN (:excludedIds)
            GROUP BY be.product_id
            ORDER BY COUNT(*) DESC, be.product_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPopularIdsByViews(@Param("since") LocalDateTime since,
                                     @Param("excludedIds") List<Long> excludedIds,
                                     @Param("limit") int limit);

    /** M-7 — behavior_events product_view 재활용(02 D3), 상품별 최신 1건·최신순. FK 미설정이라 product 조인으로 유령 id 제거 */
    @Query(value = """
            SELECT be.product_id FROM behavior_events be
            JOIN product p ON p.id = be.product_id
            WHERE be.event_type = 'product_view' AND be.member_id = :memberId
            GROUP BY be.product_id
            ORDER BY MAX(be.created_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRecentViewedIds(@Param("memberId") Long memberId, @Param("limit") int limit);

    /**
     * P-5 signals의 {@code recentlyViewedProductIds} (노션 I-22) — M-7과 거의 같지만 정렬 기준이
     * <b>{@code occurred_at}(FE 발생 시각)</b>이다. M-7은 화면 표시용이라 {@code created_at}(서버 수신)으로
     * 충분하지만, 이쪽은 FastAPI가 <b>순서에 recency decay를 거는</b> 입력이라 순서가 곧 가중치다.
     * SDK 버퍼(10건/5초) 때문에 같은 배치로 올라온 조회들은 수신 시각이 뭉쳐 순서가 뒤집힐 수 있고,
     * 그러면 가중치가 조용히 거꾸로 걸린다 — {@code occurred_at}이 존재하는 이유가 정확히 이것이다(02 D38).
     */
    @Query(value = """
            SELECT be.product_id FROM behavior_events be
            JOIN product p ON p.id = be.product_id
            WHERE be.event_type = 'product_view' AND be.member_id = :memberId
            GROUP BY be.product_id
            ORDER BY MAX(be.occurred_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRecentViewedIdsByOccurredAt(@Param("memberId") Long memberId,
                                               @Param("limit") int limit);

    /**
     * I-1 라운드1 후보 조회 (05 §I-1) — 정형 진실(가격·재고·판매상태)은 여기서 확정,
     * 살 수 없는 상품은 후보에서 제외. 2026-07-27 개정: 후보 수 상한 폐지 — 일치하는 행을 전부 반환한다.
     * 평점은 같은 쿼리에서 집계(02 D9) — 후보 수가 무제한이라 id IN 배치 집계를 쓸 수 없다.
     * 정렬을 걸지 않는 것도 같은 이유(응답 순서 무보장, 05 §I-1) — 순위는 FastAPI 리랭킹 소관이라
     * 매칭 전체에 filesort를 거는 비용만 남는다.
     * applyCategory/applyBrand=false면 ids는 센티널(빈 IN 방지) — excluded()와 같은 관성.
     *
     * <p><b>색상은 3갈래</b>(2026-08-03 LLM팀 실측 합의) — ① 색상 미지정 ② <b>attributes에 색상 축이
     * 없으면 통과</b>(색상 미상이 카탈로그의 34%라 거르면 전멸한다) ③ 있으면 값만 비교. 구 구현은
     * attributes JSON <i>전문</i>을 LIKE로 훑어 정밀도가 화이트 37.9%였다 — `_extra.visual_features`
     * 설명문이 오탐 출처였다. `json_extract`로 색상 키만 좁혀 그 오탐을 없앤다.
     * 복수 색상은 정규식 하나로 합쳐 넘긴다(JPQL은 리스트에 대한 동적 OR을 표현할 수 없다) —
     * 부분 일치를 유지해야 "그레이"가 "다크그레이"를 잡는다(실측 75건).
     */
    @Query("""
            select new com.jarvis.product.dto.CandidateRow(p, count(r), avg(r.rating))
            from Product p
              left join Review r on r.productId = p.id
                and r.status = com.jarvis.review.ReviewStatus.VISIBLE
            where p.status = com.jarvis.product.ProductStatus.ON_SALE
              and exists (select 1 from ProductStock ps where ps.productId = p.id and ps.quantity > 0)
              and (:applyCategory = false or p.categoryId in :categoryIds)
              and (:applyBrand = false or p.brandId in :brandIds)
              and (:minPrice is null or p.price >= :minPrice)
              and (:maxPrice is null or p.price <= :maxPrice)
              and (:keyword is null
                   or lower(p.name) like lower(concat('%', :keyword, '%'))
                   or lower(p.summary) like lower(concat('%', :keyword, '%'))
                   or lower(p.attributes) like lower(concat('%', :keyword, '%')))
              and (:colorPattern is null
                   or function('json_extract', p.attributes, '$."색상"') is null
                   or function('regexp_instr',
                               lower(cast(function('json_extract', p.attributes, '$."색상"') as string)),
                               :colorPattern) > 0)
            group by p
            """)
    List<CandidateRow> searchCandidates(@Param("keyword") String keyword,
                                        @Param("applyCategory") boolean applyCategory,
                                        @Param("categoryIds") List<Long> categoryIds,
                                        @Param("applyBrand") boolean applyBrand,
                                        @Param("brandIds") List<Long> brandIds,
                                        @Param("minPrice") Integer minPrice,
                                        @Param("maxPrice") Integer maxPrice,
                                        @Param("colorPattern") String colorPattern);

    /**
     * S-3/I-9 자사 상품 목록 (04 §7·§10) — HIDDEN도 노출(본인 화면), 정렬은 Pageable
     * (latest=id desc / price_asc / price_desc). 표시 판매량은 별도 집계 부착.
     *
     * <p>DELETED는 status 미지정(전량)에서도 빠진다 — 판매자에게 보이지 않는 것이 삭제의 정의라
     * status=DELETED를 명시로 넘겨도 빈 결과다(별도 400을 두지 않는 이유: 존재를 노출하지 않는다).
     */
    @Query("""
            select p from Product p
            where p.brandId = :brandId
              and p.status <> com.jarvis.product.ProductStatus.DELETED
              and (:status is null or p.status = :status)
              and (:q is null or lower(p.name) like lower(concat('%', :q, '%')))
            """)
    Page<Product> findSellerProducts(@Param("brandId") Long brandId,
                                     @Param("status") ProductStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);

    /** P-4 3순위 — 최신순 채움 (04 §2). 1순위와 같은 이유로 품절 제외 */
    @Query(value = """
            SELECT p.id FROM product p
            WHERE p.status = 'ON_SALE' AND p.id NOT IN (:excludedIds)
              AND EXISTS (SELECT 1 FROM product_stock ps WHERE ps.product_id = p.id AND ps.quantity > 0)
            ORDER BY p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findLatestIds(@Param("excludedIds") List<Long> excludedIds, @Param("limit") int limit);
}
