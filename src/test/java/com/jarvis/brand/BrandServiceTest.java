package com.jarvis.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {

    @Mock BrandRepository brandRepository;

    @InjectMocks BrandService brandService;

    @Test
    @DisplayName("P-6 — 존재하는 브랜드 id는 브랜드 엔티티를 그대로 반환한다")
    void getBrandFound() {
        Brand brand = mock(Brand.class);
        when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

        assertThat(brandService.getBrand(1L)).isSameAs(brand);
    }

    @Test
    @DisplayName("P-6 — 미존재 브랜드 id는 BRAND_NOT_FOUND(404)")
    void getBrandNotFound() {
        when(brandRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> brandService.getBrand(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BRAND_NOT_FOUND);
    }

    @Test
    @DisplayName("I-1 — brandName 필터: 존재하는 이름은 id, 미존재 이름은 empty")
    void findIdByName() {
        Brand brand = mock(Brand.class);
        when(brand.getId()).thenReturn(7L);
        when(brandRepository.findFirstByName("려")).thenReturn(Optional.of(brand));
        when(brandRepository.findFirstByName("없는브랜드")).thenReturn(Optional.empty());

        assertThat(brandService.findIdByName("려")).contains(7L);
        assertThat(brandService.findIdByName("없는브랜드")).isEmpty();
    }

    @Test
    @DisplayName("카드 공통(04 §2) — getNames: id 목록을 id→브랜드명 맵으로 배치 조회한다")
    void getNames() {
        Brand ryo = mock(Brand.class);
        when(ryo.getId()).thenReturn(1L);
        when(ryo.getName()).thenReturn("려");
        Brand mise = mock(Brand.class);
        when(mise.getId()).thenReturn(2L);
        when(mise.getName()).thenReturn("미쟝센");
        when(brandRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(ryo, mise));

        assertThat(brandService.getNames(List.of(1L, 2L)))
                .isEqualTo(Map.of(1L, "려", 2L, "미쟝센"));
    }
}
