package com.jarvis.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;

    @InjectMocks CategoryService categoryService;

    @Test
    @DisplayName("I-1 — 경로형 카테고리는 마지막 구간 이름으로도 해석한다")
    void resolvesPathCategoryByLastSegment() {
        Category jeans = mock(Category.class);
        when(jeans.getId()).thenReturn(31L);
        when(categoryRepository.findFirstByName("청바지")).thenReturn(Optional.empty());
        when(categoryRepository.findAllByNameEndingWith(" > 청바지")).thenReturn(List.of(jeans));

        assertThat(categoryService.resolveIdsByName("청바지"))
                .contains(List.of(31L));
        verify(categoryRepository).findAllByNameEndingWith(" > 청바지");
    }

    @Test
    @DisplayName("I-1 — AI가 수식어를 포함한 카테고리를 보내면 뒤쪽 실제 카테고리로 해석한다")
    void resolvesCategoryAfterDroppingLeadingModifier() {
        Category jeans = mock(Category.class);
        when(jeans.getId()).thenReturn(31L);
        when(categoryRepository.findFirstByName("부츠컷 청바지")).thenReturn(Optional.empty());
        when(categoryRepository.findAllByNameEndingWith(" > 부츠컷 청바지")).thenReturn(List.of());
        when(categoryRepository.findFirstByName("청바지")).thenReturn(Optional.empty());
        when(categoryRepository.findAllByNameEndingWith(" > 청바지")).thenReturn(List.of(jeans));

        assertThat(categoryService.resolveIdsByName("부츠컷 청바지"))
                .contains(List.of(31L));
    }

    @Test
    @DisplayName("I-1 — 경로 별칭도 없으면 미존재 카테고리로 처리한다")
    void unknownCategoryHasNoAlias() {
        when(categoryRepository.findFirstByName("없는분류")).thenReturn(Optional.empty());
        when(categoryRepository.findAllByNameEndingWith(" > 없는분류")).thenReturn(List.of());

        assertThat(categoryService.resolveIdsByName("없는분류")).isEmpty();
    }
}
