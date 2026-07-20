package com.jarvis.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByIdAsc();

    Optional<Category> findFirstByName(String name);

    /**
     * AI가 경로형 카테고리("여성의류 > 청바지")의 마지막 구간("청바지")만 보낼 때
     * 동일한 하위 카테고리를 찾는다. 앞쪽 부분 문자열 오매칭을 피하려고 경로 구분자까지 포함한다.
     */
    List<Category> findAllByNameEndingWith(String suffix);

    List<Category> findAllByParentId(Long parentId);
}
