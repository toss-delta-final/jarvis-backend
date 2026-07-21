package com.jarvis.product;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 재고 조건부 차감 (02 D33) — 결제 성공(PAID 전이)과 같은 트랜잭션에서만 호출.
     * 0건 매치 = 재고 부족 → 결제 실패(OUT_OF_STOCK) 처리. updated_at 갱신은 I-17 증분 커서용.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Product p SET p.stockQuantity = p.stockQuantity - :qty, p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.id = :id AND p.stockQuantity >= :qty
            """)
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 차감 실패 시 같은 트랜잭션 내 보상 복원 — 취소/반품 복원(MVP 미구현)과 무관 */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Product p SET p.stockQuantity = p.stockQuantity + :qty, p.updatedAt = CURRENT_TIMESTAMP
            WHERE p.id = :id
            """)
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

    /** 차감 직후 잔여 확인 — 0 도달 시 product_change_logs STOCK 기록 (02 D33) */
    @Query("SELECT p.stockQuantity FROM Product p WHERE p.id = :id")
    Optional<Integer> findStockQuantity(@Param("id") Long id);

    /** S-1 상품별 지표 조인용 — 판매자 브랜드는 시드 규모가 작아 전건 로드 (04 §7) */
    List<Product> findAllByBrandId(Long brandId);

    /** S-1 재고 부족 알림 — ON_SALE 중 재고 ≤ threshold, 재고 오름차순 (노션 S-1) */
    @Query("""
            select p from Product p
            where p.brandId = :brandId
              and p.status = com.jarvis.product.ProductStatus.ON_SALE
              and p.stockQuantity <= :threshold
            order by p.stockQuantity asc, p.id asc
            """)
    List<Product> findLowStock(@Param("brandId") Long brandId, @Param("threshold") int threshold);

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

    /** P-4 1순위 — 최근 7일 판매수 상위 (04 §2) */
    @Query(value = """
            SELECT oi.product_id FROM order_item oi
            JOIN orders o ON o.id = oi.order_id AND o.status = 'PAID' AND o.paid_at >= :since
            JOIN product p ON p.id = oi.product_id AND p.status = 'ON_SALE'
            GROUP BY oi.product_id
            ORDER BY SUM(oi.quantity) DESC, oi.product_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPopularIdsBySales(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** P-4 2순위 — behavior_events product_view 수 (04 §2) */
    @Query(value = """
            SELECT be.product_id FROM behavior_events be
            JOIN product p ON p.id = be.product_id AND p.status = 'ON_SALE'
            WHERE be.event_type = 'product_view' AND be.created_at >= :since
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
     * I-1 라운드1 후보 조회 (05 §I-1) — 정형 진실(가격·재고·판매상태)은 여기서 확정,
     * 살 수 없는 상품은 후보에서 제외. LIMIT은 Pageable(라운드1 상한 — 후보 폭발 방지).
     * applyCategory=false면 categoryIds는 센티널(빈 IN 방지) — excluded()와 같은 관성.
     */
    @Query("""
            select p from Product p
            where p.status = com.jarvis.product.ProductStatus.ON_SALE
              and p.stockQuantity > 0
              and (:applyCategory = false or p.categoryId in :categoryIds)
              and (:brandId is null or p.brandId = :brandId)
              and (:minPrice is null or p.price >= :minPrice)
              and (:maxPrice is null or p.price <= :maxPrice)
              and (:keyword is null
                   or lower(p.name) like lower(concat('%', :keyword, '%'))
                   or lower(p.summary) like lower(concat('%', :keyword, '%'))
                   or lower(p.attributes) like lower(concat('%', :keyword, '%')))
              and (:color is null or lower(p.attributes) like lower(concat('%', :color, '%')))
            order by p.baseSalesCount desc, p.id desc
            """)
    List<Product> searchCandidates(@Param("keyword") String keyword,
                                   @Param("applyCategory") boolean applyCategory,
                                   @Param("categoryIds") List<Long> categoryIds,
                                   @Param("brandId") Long brandId,
                                   @Param("minPrice") Integer minPrice,
                                   @Param("maxPrice") Integer maxPrice,
                                   @Param("color") String color,
                                   Pageable pageable);

    /**
     * S-3/I-9 자사 상품 목록 (04 §7·§10) — HIDDEN도 노출(본인 화면), 정렬은 Pageable
     * (latest=id desc / price_asc / price_desc). 표시 판매량은 별도 집계 부착.
     */
    @Query("""
            select p from Product p
            where p.brandId = :brandId
              and (:status is null or p.status = :status)
              and (:q is null or lower(p.name) like lower(concat('%', :q, '%')))
            """)
    Page<Product> findSellerProducts(@Param("brandId") Long brandId,
                                     @Param("status") ProductStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);

    /** P-4 3순위 — 최신순 채움 (04 §2) */
    @Query(value = """
            SELECT p.id FROM product p
            WHERE p.status = 'ON_SALE' AND p.id NOT IN (:excludedIds)
            ORDER BY p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findLatestIds(@Param("excludedIds") List<Long> excludedIds, @Param("limit") int limit);
}
