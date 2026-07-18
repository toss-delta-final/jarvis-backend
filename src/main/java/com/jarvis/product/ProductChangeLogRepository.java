package com.jarvis.product;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductChangeLogRepository extends JpaRepository<ProductChangeLog, Long> {

    interface ChangeRow {
        Long getProductId();
        String getProductName();
        String getChangeType();
        String getOldValue();
        String getNewValue();
        LocalDateTime getCreatedAt();
    }

    /** I-15 자사 상품 변경 이력 (04 §10, 노션 I-15) — 품절 신호 = STOCK newValue "0", productName 부착 */
    @Query(value = """
            SELECT l.product_id AS productId, p.name AS productName, l.change_type AS changeType,
                   l.old_value AS oldValue, l.new_value AS newValue, l.created_at AS createdAt
            FROM product_change_logs l
            JOIN product p ON p.id = l.product_id AND p.brand_id = :brandId
            WHERE (:changeType IS NULL OR l.change_type = :changeType)
              AND (:productId IS NULL OR l.product_id = :productId)
              AND l.created_at >= :from AND l.created_at < :to
            ORDER BY l.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ChangeRow> findSellerProductChanges(@Param("brandId") Long brandId,
                                             @Param("changeType") String changeType,
                                             @Param("productId") Long productId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to,
                                             @Param("limit") int limit);

    /** I-15 total — rows와 같은 조건의 전체 건수(LIMIT 미적용) */
    @Query(value = """
            SELECT COUNT(*)
            FROM product_change_logs l
            JOIN product p ON p.id = l.product_id AND p.brand_id = :brandId
            WHERE (:changeType IS NULL OR l.change_type = :changeType)
              AND (:productId IS NULL OR l.product_id = :productId)
              AND l.created_at >= :from AND l.created_at < :to
            """, nativeQuery = true)
    long countSellerProductChanges(@Param("brandId") Long brandId,
                                   @Param("changeType") String changeType,
                                   @Param("productId") Long productId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);
}
