package com.jarvis.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrandServiceTest {

    @Mock BrandRepository brandRepository;
    @Mock RedisCache cache;

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
        when(brandRepository.findByName("려")).thenReturn(Optional.of(brand));
        when(brandRepository.findByName("없는브랜드")).thenReturn(Optional.empty());

        assertThat(brandService.findIdByName("려")).contains(7L);
        assertThat(brandService.findIdByName("없는브랜드")).isEmpty();
    }

    @Test
    @DisplayName("카드 공통(04 §2) — getNames: 전량을 캐시(키 하나)로 두고 요청 id만 걸러 돌려준다")
    void getNames() {
        // 캐시는 검증 대상이 아니다 — 로더를 통과시켜 조회·필터 로직만 본다 (07 §3-1)
        when(cache.get(anyString(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        Brand ryo = mock(Brand.class);
        when(ryo.getId()).thenReturn(1L);
        when(ryo.getName()).thenReturn("려");
        Brand mise = mock(Brand.class);
        when(mise.getId()).thenReturn(2L);
        when(mise.getName()).thenReturn("미쟝센");
        when(brandRepository.findAll()).thenReturn(List.of(ryo, mise));

        // 캐시 맵에 없는 id(99L)는 결과에서도 빠진다 — 기존 findAllById 동작과 동일한 모양
        assertThat(brandService.getNames(List.of(1L, 2L, 99L)))
                .isEqualTo(Map.of(1L, "려", 2L, "미쟝센"));
    }
}
