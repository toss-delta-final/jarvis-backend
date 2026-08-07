package com.jarvis.recommendation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListItemRepository
        extends JpaRepository<RecommendationListItem, RecommendationListItem.Key> {

    /** E-1 귀속 도출 — "이 상품이 그 목록 몇 번째인가". 배치의 listId를 한 번에 조회한다 */
    List<RecommendationListItem> findByListIdIn(Collection<String> listIds);

    /** C-2·I-2·O-1 전환 귀속 ③규칙 — 순위는 필요 없고 "포함되나"만 묻는다 */
    boolean existsByListIdAndProductId(String listId, Long productId);
}
