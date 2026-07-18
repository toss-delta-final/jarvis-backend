package com.jarvis.review;

import com.jarvis.review.dto.ReviewRow;
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
}
