package com.jarvis.brand;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    /** I-1 brandName 해석. `uk_brand_name`으로 유일하므로 First를 붙이지 않는다 — CategoryRepository와 같은 이유 */
    Optional<Brand> findByName(String name);

    /** 판매자 → 자기 브랜드 도출 (04 §7 — 클라이언트 주장이 아니라 DB가 원천) */
    Optional<Brand> findFirstBySellerId(Long sellerId);
}
