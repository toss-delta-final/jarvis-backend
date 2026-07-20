package com.jarvis.category;

import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
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

    private final CategoryRepository categoryRepository;

    public List<CategoryTreeResponse> getTree() {
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
        String candidate = name.trim();
        while (!candidate.isEmpty()) {
            Optional<List<Long>> resolved = resolveExactOrPath(candidate);
            if (resolved.isPresent()) {
                return resolved;
            }
            int separator = candidate.indexOf(' ');
            if (separator < 0) {
                break;
            }
            candidate = candidate.substring(separator + 1).trim();
        }
        return Optional.empty();
    }

    private Optional<List<Long>> resolveExactOrPath(String name) {
        Optional<Category> exact = categoryRepository.findFirstByName(name);
        if (exact.isPresent()) {
            Category category = exact.get();
            return Optional.of(category.isRoot()
                    ? categoryRepository.findAllByParentId(category.getId()).stream()
                            .map(Category::getId).toList()
                    : List.of(category.getId()));
        }

        // sample_100은 충돌 방지를 위해 "상위 > 하위" 이름으로 저장한다. 질의 분해기가
        // 마지막 구간만 categoryName으로 보내도 해당 카테고리들을 함께 검색한다.
        List<Long> pathMatches = categoryRepository.findAllByNameEndingWith(" > " + name).stream()
                .map(Category::getId)
                .distinct()
                .toList();
        return pathMatches.isEmpty() ? Optional.empty() : Optional.of(pathMatches);
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
