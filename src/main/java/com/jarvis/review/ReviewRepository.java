package com.jarvis.review;

import com.jarvis.review.dto.BrandReviewRow;
import com.jarvis.review.dto.ReviewRow;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** O-4 canReview 계산용 — 아이템당 후기 1개 (01 §3, 02 D4) */
    @Query("select r.orderItemId from Review r where r.orderItemId in :orderItemIds")
    List<Long> findOrderItemIdsIn(@Param("orderItemIds") Collection<Long> orderItemIds);

    /** M-1 미작성 자격 검증 (01 §3) */
    boolean existsByOrderItemId(Long orderItemId);

    String SELECT_ROW = """
            select new com.jarvis.review.dto.ReviewRow(
                r.id, r.rating, r.content, r.authorName, m.nickname, r.createdAt)
            from Review r left join Member m on m.id = r.memberId
            where r.productId = :productId and r.status = com.jarvis.review.ReviewStatus.VISIBLE
            """;
    String COUNT_ROW = """
            select count(r) from Review r
            where r.productId = :productId and r.status = com.jarvis.review.ReviewStatus.VISIBLE
            """;

    @Query(value = SELECT_ROW + " order by r.createdAt desc, r.id desc", countQuery = COUNT_ROW)
    Page<ReviewRow> findVisibleRowsLatest(@Param("productId") Long productId, Pageable pageable);

    @Query(value = SELECT_ROW + " order by r.rating desc, r.id desc", countQuery = COUNT_ROW)
    Page<ReviewRow> findVisibleRowsByRating(@Param("productId") Long productId, Pageable pageable);

    /** [rating, count] — P-3 page=0의 distribution (04 §2) */
    @Query("""
            select r.rating, count(r) from Review r
            where r.productId = :productId and r.status = com.jarvis.review.ReviewStatus.VISIBLE
            group by r.rating
            """)
    List<Object[]> countVisibleByRating(@Param("productId") Long productId);

    /** [productId, count, avg] — P-2 상세·카드 배치 집계 (02 D9) */
    @Query("""
            select r.productId, count(r), avg(r.rating) from Review r
            where r.productId in :productIds and r.status = com.jarvis.review.ReviewStatus.VISIBLE
            group by r.productId
            """)
    List<Object[]> aggregateVisibleByProductIds(@Param("productIds") Collection<Long> productIds);

    // ── I-31 자사 상품 리뷰 (노션 I-31) ──
    // P-3와 달리 브랜드 전체가 대상이라 Product를 조인해 소유권을 건다. VISIBLE만 보는 건 같다.
    // 크롤링 리뷰(member_id NULL, 02 D19)도 포함하므로 Member는 left join이고 닉네임은 coalesce다.
    // applyRating은 빈 컬렉션을 IN에 넣지 않으려는 플래그 — I-14 toStatus 전례.

    String BRAND_WHERE = """
            from Review r
              join Product p on p.id = r.productId
              left join Member m on m.id = r.memberId
            where p.brandId = :brandId
              and r.status = com.jarvis.review.ReviewStatus.VISIBLE
              and (:productId is null or r.productId = :productId)
              and (:applyRating = false or r.rating in :ratings)
              and r.createdAt >= :from and r.createdAt < :to
            """;

    String BRAND_SELECT = "select new com.jarvis.review.dto.BrandReviewRow("
            + "r.id, r.productId, p.name, r.rating, r.content, "
            + "coalesce(m.nickname, r.authorName), r.createdAt) ";
    String BRAND_COUNT = "select count(r) " + BRAND_WHERE;

    @Query(value = BRAND_SELECT + BRAND_WHERE + " order by r.createdAt desc, r.id desc",
            countQuery = BRAND_COUNT)
    Page<BrandReviewRow> findBrandReviewsLatest(
            @Param("brandId") Long brandId, @Param("productId") Long productId,
            @Param("applyRating") boolean applyRating, @Param("ratings") Collection<Integer> ratings,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    /** ratingAsc = 낮은 순 고정 — P-3의 sort=rating(높은 순)과 이름을 가른 이유다(노션 I-31 결정 1) */
    @Query(value = BRAND_SELECT + BRAND_WHERE + " order by r.rating asc, r.id desc",
            countQuery = BRAND_COUNT)
    Page<BrandReviewRow> findBrandReviewsRatingAsc(
            @Param("brandId") Long brandId, @Param("productId") Long productId,
            @Param("applyRating") boolean applyRating, @Param("ratings") Collection<Integer> ratings,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    /** [rating, count] — stats distribution. 없는 별점은 행이 없으므로 서비스가 0으로 채운다 */
    @Query("select r.rating, count(r) " + BRAND_WHERE + " group by r.rating")
    List<Object[]> countBrandReviewsByRating(
            @Param("brandId") Long brandId, @Param("productId") Long productId,
            @Param("applyRating") boolean applyRating, @Param("ratings") Collection<Integer> ratings,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** [productId, productName, count, avg] — stats byProduct. count 내림차순, 동률은 productId 오름차순 */
    @Query("select r.productId, p.name, count(r), avg(r.rating) " + BRAND_WHERE
            + " group by r.productId, p.name order by count(r) desc, r.productId asc")
    List<Object[]> aggregateBrandReviewsByProduct(
            @Param("brandId") Long brandId, @Param("productId") Long productId,
            @Param("applyRating") boolean applyRating, @Param("ratings") Collection<Integer> ratings,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
