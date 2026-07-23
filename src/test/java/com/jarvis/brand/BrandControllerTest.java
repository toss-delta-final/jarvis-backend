package com.jarvis.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.brand.dto.BrandDetailResponse;
import com.jarvis.category.CategoryService;
import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardPageResponse;
import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P-6 조립 검증 — BrandService→ProductService 순환 회피로 컨트롤러가 조합을 담당하므로
 * (BrandController 주석 참조) 서비스 수준 단위 테스트를 컨트롤러에 둔다. Spring 컨텍스트 없음.
 */
@ExtendWith(MockitoExtension.class)
class BrandControllerTest {

    @Mock BrandService brandService;
    @Mock ProductService productService;
    @Mock CategoryService categoryService;

    @InjectMocks BrandController brandController;

    private Brand brand(Long id, String name, String logoUrl, String description) {
        Brand brand = mock(Brand.class);
        when(brand.getId()).thenReturn(id);
        when(brand.getName()).thenReturn(name);
        when(brand.getLogoUrl()).thenReturn(logoUrl);
        when(brand.getDescription()).thenReturn(description);
        return brand;
    }

    @Test
    @DisplayName("P-6 — 브랜드 소개 + 소분류 필터 축 + 상품 페이지를 brand 중첩 형태로 조립한다")
    void detailAssemblesBrandCategoriesAndProducts() {
        Brand brand = brand(1L, "려", "https://cdn/logo.png", "한방 헤어 브랜드");
        when(brandService.getBrand(1L)).thenReturn(brand);
        when(productService.getBrandCategoryIds(1L)).thenReturn(List.of(11L, 12L));
        List<CategoryTreeResponse.Child> summaries =
                List.of(new CategoryTreeResponse.Child(11L, "샴푸"), new CategoryTreeResponse.Child(12L, "트리트먼트"));
        when(categoryService.getSummaries(List.of(11L, 12L))).thenReturn(summaries);
        ProductCardPageResponse products = new ProductCardPageResponse(
                List.of(new ProductCardResponse(10L, "려 자양윤모 샴푸", "려",
                        15000, 20000, "https://cdn/p10.png", 4.5, 12, true)),
                0, 20, 21, 2);
        when(productService.getBrandProducts(1L, null, "popular", 0, 20)).thenReturn(products);

        ApiResponse<BrandDetailResponse> response = brandController.detail(1L, null, "popular", 0, 20);

        assertThat(response.success()).isTrue();
        BrandDetailResponse.BrandSummary summary = response.data().brand();
        assertThat(summary.id()).isEqualTo(1L);
        assertThat(summary.name()).isEqualTo("려");
        assertThat(summary.logoUrl()).isEqualTo("https://cdn/logo.png");
        assertThat(summary.description()).isEqualTo("한방 헤어 브랜드");
        assertThat(summary.categories()).isEqualTo(summaries);
        assertThat(response.data().products()).isSameAs(products);
    }

    @Test
    @DisplayName("P-6 — category·sort·page·size 쿼리를 상품 목록 조회에 그대로 전달한다")
    void detailPassesQueryParamsToProductService() {
        Brand brand = brand(1L, "려", null, null);
        when(brandService.getBrand(1L)).thenReturn(brand);
        when(productService.getBrandCategoryIds(1L)).thenReturn(List.of());
        when(categoryService.getSummaries(List.of())).thenReturn(List.of());
        ProductCardPageResponse empty = new ProductCardPageResponse(List.of(), 2, 10, 0, 0);
        when(productService.getBrandProducts(1L, 11L, "price_asc", 2, 10)).thenReturn(empty);

        ApiResponse<BrandDetailResponse> response = brandController.detail(1L, 11L, "price_asc", 2, 10);

        assertThat(response.data().products()).isSameAs(empty);
        verify(productService).getBrandProducts(1L, 11L, "price_asc", 2, 10);
    }

    @Test
    @DisplayName("P-6 — 미존재 브랜드는 상품·카테고리 조회 없이 BRAND_NOT_FOUND 전파")
    void detailBrandNotFoundShortCircuits() {
        when(brandService.getBrand(999L)).thenThrow(new BusinessException(ErrorCode.BRAND_NOT_FOUND));

        assertThatThrownBy(() -> brandController.detail(999L, null, "popular", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BRAND_NOT_FOUND);
        verifyNoInteractions(productService, categoryService);
    }
}
