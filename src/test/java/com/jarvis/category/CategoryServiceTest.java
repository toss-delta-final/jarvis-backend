package com.jarvis.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jarvis.category.dto.CategoryTreeResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;

    @InjectMocks CategoryService categoryService;

    private Category category(long id, Long parentId, String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "parentId", parentId);
        ReflectionTestUtils.setField(c, "name", name);
        return c;
    }

    @Test
    @DisplayName("P-1 — 평면 rows를 대분류+소분류 2단 트리로 조립 (parent_id NULL=대분류, 02 D20)")
    void treeBuiltFromFlatRows() {
        when(categoryRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                category(1L, null, "상의"),
                category(2L, null, "하의"),
                category(11L, 1L, "티셔츠"),
                category(12L, 1L, "니트"),
                category(21L, 2L, "청바지")));

        List<CategoryTreeResponse> tree = categoryService.getTree();

        assertThat(tree).hasSize(2);
        CategoryTreeResponse top = tree.get(0);
        assertThat(top.id()).isEqualTo(1L);
        assertThat(top.name()).isEqualTo("상의");
        assertThat(top.children()).extracting(CategoryTreeResponse.Child::id)
                .containsExactly(11L, 12L);
        assertThat(top.children()).extracting(CategoryTreeResponse.Child::name)
                .containsExactly("티셔츠", "니트");
        assertThat(tree.get(1).children()).extracting(CategoryTreeResponse.Child::id)
                .containsExactly(21L);
    }

    @Test
    @DisplayName("P-1 — 대분류·소분류 모두 id 오름차순 정렬 유지 (02 D20)")
    void orderingByIdAscending() {
        // repository가 id asc로 반환한 순서를 트리 조립이 보존해야 함
        when(categoryRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                category(1L, null, "상의"),
                category(2L, null, "하의"),
                category(3L, null, "신발"),
                category(10L, 2L, "슬랙스"),
                category(11L, 2L, "청바지"),
                category(12L, 1L, "셔츠")));

        List<CategoryTreeResponse> tree = categoryService.getTree();

        assertThat(tree).extracting(CategoryTreeResponse::id).containsExactly(1L, 2L, 3L);
        assertThat(tree.get(1).children()).extracting(CategoryTreeResponse.Child::id)
                .containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("P-1 — 소분류 없는 대분류는 children이 빈 배열(null 아님)")
    void rootWithoutChildrenHasEmptyList() {
        when(categoryRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                category(1L, null, "잡화")));

        List<CategoryTreeResponse> tree = categoryService.getTree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("P-1 — 카테고리가 하나도 없으면 빈 트리")
    void emptyCategories() {
        when(categoryRepository.findAllByOrderByIdAsc()).thenReturn(List.of());

        assertThat(categoryService.getTree()).isEmpty();
    }
}
