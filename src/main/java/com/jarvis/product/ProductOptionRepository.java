package com.jarvis.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    List<ProductOption> findAllByProductIdOrderByIdAsc(Long productId);

    /** I-1/I-3 후보의 옵션 배치 조회 — 후보마다 조회하면 N+1이라 한 번에 가져와 그룹핑한다 */
    List<ProductOption> findAllByProductIdInOrderByProductIdAscIdAsc(List<Long> productIds);
}
