package com.jarvis.recommendation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListRepository extends JpaRepository<RecommendationList, Long> {

    boolean existsByListId(String listId);

    /** E-1 귀속 도출 — 배치의 listId를 한 번에 조회한다(이벤트당 조회 방지) */
    List<RecommendationList> findByListIdIn(Collection<String> listIds);
}
