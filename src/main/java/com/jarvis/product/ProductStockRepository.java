package com.jarvis.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 조회·차감 (02 D33 개정).
 *
 * <p><b>{@code optionId}가 null인 행을 파생 쿼리로 찾을 수 없다</b> — Spring Data가 만드는
 * {@code option_id = ?}는 NULL에 매칭되지 않아 옵션 없는 상품이 조용히 "재고 없음"이 된다.
 * 그래서 옵션을 지목하는 메서드는 전부 JPQL로 {@code is null} 분기를 명시한다.
 */
public interface ProductStockRepository extends JpaRepository<ProductStock, Long> {

    List<ProductStock> findAllByProductId(Long productId);

    List<ProductStock> findAllByProductIdIn(Collection<Long> productIds);

    @Query("""
            select s from ProductStock s
            where s.productId = :productId
              and ((:optionId is null and s.optionId is null) or s.optionId = :optionId)
            """)
    Optional<ProductStock> findOne(@Param("productId") Long productId,
                                   @Param("optionId") Long optionId);

    /**
     * 판매자 재고 수정(I-11) 진입점 전용 비관적 쓰기 락 — 절대값 수정이 주문의 조건부 차감과 경합해
     * 판매분을 유령 복원하는 lost update를 막는다. 락 안에서 읽은 값이 change log의 old_value다.
     *
     * <p>구현 이전엔 이 락이 {@code product} 행에 걸려 있었다. 재고가 이 테이블로 내려온 뒤에는
     * 그 락으로 재고를 지킬 수 없다 — 잠그는 행과 바뀌는 행이 달라졌다(02 D33 개정).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ProductStock s
            where s.productId = :productId
              and ((:optionId is null and s.optionId is null) or s.optionId = :optionId)
            """)
    Optional<ProductStock> findOneForUpdate(@Param("productId") Long productId,
                                            @Param("optionId") Long optionId);

    /**
     * 조건부 차감 (02 D33) — 결제 성공(PAID 전이)과 같은 트랜잭션에서만 호출.
     * 0건 매치 = 재고 부족 → 결제 실패(OUT_OF_STOCK) 처리.
     *
     * <p>{@code product.updated_at}은 건드리지 않는다 — 그 값은 I-17 증분 커서용이고,
     * I-17 응답에 재고가 없어서(05 §I-17) 재고 변경으로 재동기화를 유발할 이유가 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ProductStock s set s.quantity = s.quantity - :qty
            where s.productId = :productId
              and ((:optionId is null and s.optionId is null) or s.optionId = :optionId)
              and s.quantity >= :qty
            """)
    int deduct(@Param("productId") Long productId, @Param("optionId") Long optionId,
               @Param("qty") int qty);

    /** 무조건 복원 — 차감 실패 시의 보상 복원과 취소/반품 확정분 복원이 함께 쓴다 (둘 다 ProductStockService 경유) */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ProductStock s set s.quantity = s.quantity + :qty
            where s.productId = :productId
              and ((:optionId is null and s.optionId is null) or s.optionId = :optionId)
            """)
    int restore(@Param("productId") Long productId, @Param("optionId") Long optionId,
                @Param("qty") int qty);

    /** 차감 직후 잔여 확인 — 0 도달 시 product_change_logs STOCK 기록 (02 D33) */
    @Query("""
            select s.quantity from ProductStock s
            where s.productId = :productId
              and ((:optionId is null and s.optionId is null) or s.optionId = :optionId)
            """)
    Optional<Integer> findQuantity(@Param("productId") Long productId,
                                   @Param("optionId") Long optionId);

    /** 상품이 지금 팔 수 있는지 — 살 수 있는 옵션이 하나라도 있으면 참 (구 stock_quantity > 0) */
    @Query("""
            select (count(s) > 0) from ProductStock s
            where s.productId = :productId and s.quantity > 0
            """)
    boolean hasPurchasable(@Param("productId") Long productId);

    /** 목록 화면의 합계 표시용 — 저장 컬럼이 아니라 파생값이다 (02 D33 개정) */
    @Query("""
            select s.productId, coalesce(sum(s.quantity), 0) from ProductStock s
            where s.productId in :productIds
            group by s.productId
            """)
    List<Object[]> sumByProductIds(@Param("productIds") Collection<Long> productIds);

    /** 목록 화면이 상품마다 재고를 다시 묻지 않도록 한 번에 받아간다 — 없는 상품은 0으로 채운다 */
    default Map<Long, Integer> sumMap(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> totals = new HashMap<>();
        for (Object[] row : sumByProductIds(productIds)) {
            totals.put((Long) row[0], ((Number) row[1]).intValue());
        }
        productIds.forEach(id -> totals.putIfAbsent(id, 0));
        return totals;
    }

    /** S-1 재고 부족 알림 — 옵션 단위. 정렬은 재고 오름차순 (노션 S-1) */
    @Query("""
            select s from ProductStock s
            join Product p on p.id = s.productId
            where p.brandId = :brandId
              and p.status = com.jarvis.product.ProductStatus.ON_SALE
              and s.quantity <= :threshold
            order by s.quantity asc, s.productId asc, s.optionId asc
            """)
    List<ProductStock> findLowStock(@Param("brandId") Long brandId,
                                    @Param("threshold") int threshold);
}
