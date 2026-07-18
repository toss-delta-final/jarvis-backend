package com.jarvis.product;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductChangeLogRepository extends JpaRepository<ProductChangeLog, Long> {

    /** I-15 자사 상품 변경 이력 (04 §10) — 품절 신호 = STOCK newValue "0" */
    @Query(value = """
            SELECT l.* FROM product_change_logs l
            JOIN product p ON p.id = l.product_id AND p.brand_id = :brandId
            WHERE (:changeType IS NULL OR l.change_type = :changeType)
              AND (:productId IS NULL OR l.product_id = :productId)
              AND l.created_at >= :from AND l.created_at < :to
            ORDER BY l.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ProductChangeLog> findSellerProductChanges(@Param("brandId") Long brandId,
                                                    @Param("changeType") String changeType,
                                                    @Param("productId") Long productId,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to,
                                                    @Param("limit") int limit);
}
