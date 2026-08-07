package com.jarvis.recommendation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListRepository extends JpaRepository<RecommendationList, Long> {

    boolean existsByListId(String listId);

    /** E-1 귀속 도출 — 배치의 listId를 한 번에 조회한다(이벤트당 조회 방지) */
    List<RecommendationList> findByListIdIn(Collection<String> listIds);

    /** C-2·I-2·O-1 전환 귀속 — 전환은 요청당 라인 수가 적어 배치 조회가 필요 없다 */
    Optional<RecommendationList> findByListId(String listId);
}
