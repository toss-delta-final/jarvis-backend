package com.jarvis.category;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderByIdAsc();

    /**
     * I-1 categoryName 해석의 입구. <b>First를 붙이지 않는다</b> — `uk_category_name`으로 이름이
     * 전역 유일하기 때문이다. First(=LIMIT 1)를 두면 유일성이 깨졌을 때 <b>조용히 하나만 골라</b>
     * 검색 결과가 반쪽이 되지만, 이 형태는 즉시 예외로 드러난다. 이름은 LLM↔BE의 참조 키라
     * 그 전제가 깨지는 걸 코드가 감시해야 한다.
     */
    Optional<Category> findByName(String name);

    List<Category> findAllByParentId(Long parentId);
}
