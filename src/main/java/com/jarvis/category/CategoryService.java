package com.jarvis.category;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    /** 07 §2 키 지도. `v1:`은 값 스키마가 바뀌었을 때 버전만 올려 통째로 버리기 위한 프리픽스다 */
    private static final String TREE_KEY = "v1:category:tree";
    private static final Duration TREE_TTL = Duration.ofHours(1);

    private final CategoryRepository categoryRepository;
    private final RedisCache cache;

    /**
     * P-1 — 카테고리 트리 (07 §3-1 캐시 대상). 거의 안 변하는데 모든 페이지가 헤더에서 부른다.
     *
     * <p>무효화는 TTL뿐이다 — 카테고리를 런타임에 바꾸는 경로가 없기 때문이다(등록·수정 API 없음,
     * 시드로만 들어온다). 시드를 갱신하면 최대 TTL만큼 반영이 늦는다.
     */
    public List<CategoryTreeResponse> getTree() {
        return cache.get(TREE_KEY, TREE_TTL, new TypeReference<>() { }, this::loadTree);
    }

    private List<CategoryTreeResponse> loadTree() {
        List<Category> all = categoryRepository.findAllByOrderByIdAsc();
        Map<Long, List<Category>> childrenByParent = all.stream()
                .filter(c -> !c.isRoot())
                .collect(Collectors.groupingBy(Category::getParentId));
        return all.stream()
                .filter(Category::isRoot)
                .map(root -> CategoryTreeResponse.from(root,
                        childrenByParent.getOrDefault(root.getId(), List.of())))
                .toList();
    }

    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * I-1 categoryName 해석 (02 D20, 05 §I-1) — 대분류명이면 하위 소분류 전체 포함,
     * 소분류명이면 해당만. 미존재 이름이면 empty(후보 0건).
     */
    public Optional<List<Long>> resolveIdsByName(String name) {
        return categoryRepository.findByName(name)
                .map(category -> category.isRoot()
                        ? categoryRepository.findAllByParentId(category.getId()).stream()
                                .map(Category::getId).toList()
                        : List.of(category.getId()));
    }

    /** I-1 후보 응답의 categoryName 배치 조회 */
    public Map<Long, String> getNames(Collection<Long> ids) {
        return categoryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    /** P-6 브랜드홈 필터용 소분류 요약 — id 순 정렬 (02 D20) */
    public List<CategoryTreeResponse.Child> getSummaries(Collection<Long> ids) {
        return categoryRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(Category::getId))
                .map(CategoryTreeResponse.Child::from)
                .toList();
    }
}
