package com.jarvis.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationListItemRepository
        extends JpaRepository<RecommendationListItem, RecommendationListItem.Key> {
}
