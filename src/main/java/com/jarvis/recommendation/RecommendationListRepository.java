package com.jarvis.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListRepository extends JpaRepository<RecommendationList, Long> {

    boolean existsByListId(String listId);
}
