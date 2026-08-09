package com.jarvis.product;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 상품→브랜드 매핑을 메모리에 들고 있는다 (08 D4) — 스트림 컨슈머가 이벤트마다 브랜드를 해석하는 데 쓴다.
 *
 * <p><b>왜 DB 조회가 아닌가</b>: 이벤트마다 조회하면 컨슈머가 커넥션을 상시 소비한다.
 * RDS {@code max_connections} 30에 앱 4대 × 풀 7 = 28이라 여유가 2뿐이다(2026-08-09 실측).
 * 매핑은 상품 수천 건 규모라 통째로 얹어도 수백 KB다.
 *
 * <p><b>신선도</b>: 신상품은 다음 리프레시까지 미해석이라 그 이벤트만 스킵된다.
 * 30분 슬라이딩 지표의 몇 분 구멍이라 다음 창에서 자연히 메워진다 — 정확도보다 커넥션을 지킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductBrandIndex {

    private final ProductRepository productRepository;

    private volatile Map<Long, Long> brandByProduct = Map.of();

    /**
     * 기동 직후 1회 + 5분마다. {@code @PostConstruct}가 아니라 ready 이벤트인 이유는
     * DataSource가 완전히 준비된 뒤에 돌게 하려는 것이다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "PT5M")
    public void refresh() {
        try {
            Map<Long, Long> loaded = productRepository.findAllProductBrandIds().stream()
                    .filter(row -> row.getProductId() != null && row.getBrandId() != null)
                    .collect(Collectors.toMap(ProductRepository.ProductBrandRow::getProductId,
                            ProductRepository.ProductBrandRow::getBrandId,
                            (first, second) -> first, ConcurrentHashMap::new));
            brandByProduct = loaded;
            log.debug("상품→브랜드 매핑 갱신 — {}건", loaded.size());
        } catch (Exception e) {
            // 갱신 실패는 치명적이지 않다 — 직전 스냅샷을 계속 쓴다(신상품만 잠시 미해석)
            log.warn("상품→브랜드 매핑 갱신 실패 — 직전 스냅샷 유지({}건)", brandByProduct.size(), e);
        }
    }

    /** @return 브랜드 id, 매핑에 없으면 {@code null}(아직 못 본 신상품 등) */
    public Long brandOf(Long productId) {
        return productId == null ? null : brandByProduct.get(productId);
    }
}
