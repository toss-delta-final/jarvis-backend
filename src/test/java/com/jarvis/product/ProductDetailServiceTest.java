package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandService;
import com.jarvis.category.Category;
import com.jarvis.category.CategoryService;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.dto.ProductDetailResponse;
import com.jarvis.review.ReviewService;
import com.jarvis.review.dto.RatingStats;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/** P-2 상품 상세 — HIDDEN도 404 아닌 응답(purchasable=false), 평점 실시간 집계 (04 §2) */
@ExtendWith(MockitoExtension.class)
class ProductDetailServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks ProductService productService;

    @BeforeEach
    void stubLookups() {
        Category category = mock(Category.class, withSettings().strictness(Strictness.LENIENT));
        lenient().when(category.getId()).thenReturn(10L);
        lenient().when(category.getName()).thenReturn("티셔츠");
        Brand brand = mock(Brand.class, withSettings().strictness(Strictness.LENIENT));
        lenient().when(brand.getId()).thenReturn(20L);
        lenient().when(brand.getName()).thenReturn("브랜드");
        lenient().when(brand.getLogoUrl()).thenReturn("logo.png");
        lenient().when(categoryService.getCategory(10L)).thenReturn(category);
        lenient().when(brandService.getBrand(20L)).thenReturn(brand);
        lenient().when(productOptionRepository.findAllByProductIdOrderByIdAsc(anyLong()))
                .thenReturn(List.of());
        lenient().when(reviewService.getStats(anyLong())).thenReturn(RatingStats.EMPTY);
    }

    private Product product(Long id, ProductStatus status, String attributes) {
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(id);
        when(product.getName()).thenReturn("상품" + id);
        when(product.getPrice()).thenReturn(8900);
        when(product.getOriginalPrice()).thenReturn(12900);
        when(product.getStockQuantity()).thenReturn(10);
        when(product.getStatus()).thenReturn(status);
        when(product.isPurchasable()).thenReturn(status == ProductStatus.ON_SALE);
        when(product.getImageUrl()).thenReturn("img.jpg");
        when(product.getSummary()).thenReturn("요약");
        when(product.getDescription()).thenReturn("설명");
        when(product.getAttributes()).thenReturn(attributes);
        when(product.getCategoryId()).thenReturn(10L);
        when(product.getBrandId()).thenReturn(20L);
        return product;
    }

    private ProductOption option(Long id, String name, int extraPrice) {
        ProductOption option = mock(ProductOption.class, withSettings().strictness(Strictness.LENIENT));
        when(option.getId()).thenReturn(id);
        when(option.getName()).thenReturn(name);
        when(option.getExtraPrice()).thenReturn(extraPrice);
        return option;
    }

    @Test
    @DisplayName("P-2 — 상세 조립: 옵션 id순·브랜드/카테고리 요약·평점 실시간 집계·attributes JSON")
    void detailAssemblesAllParts() throws Exception {
        String json = "{\"색상\":\"블랙\"}";
        JsonNode attrs = JsonNodeFactory.instance.objectNode().put("색상", "블랙");
        Product product = product(1L, ProductStatus.ON_SALE, json);
        List<ProductOption> options = List.of(option(100L, "블랙", 0), option(101L, "화이트", 1000));
        when(objectMapper.readTree(json)).thenReturn(attrs);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productOptionRepository.findAllByProductIdOrderByIdAsc(1L)).thenReturn(options);
        when(reviewService.getStats(1L)).thenReturn(new RatingStats(82, 4.6));

        ProductDetailResponse res = productService.getDetail(1L);

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.name()).isEqualTo("상품1");
        assertThat(res.price()).isEqualTo(8900);
        assertThat(res.originalPrice()).isEqualTo(12900);
        assertThat(res.purchasable()).isTrue();
        assertThat(res.status()).isEqualTo("ON_SALE");
        assertThat(res.attributes()).isSameAs(attrs);
        assertThat(res.category().name()).isEqualTo("티셔츠");
        assertThat(res.brand().logoUrl()).isEqualTo("logo.png");
        assertThat(res.options()).extracting(ProductDetailResponse.OptionResponse::optionId)
                .containsExactly(100L, 101L);
        assertThat(res.rating().average()).isEqualTo(4.6);
        assertThat(res.rating().count()).isEqualTo(82);
    }

    @Test
    @DisplayName("P-2 — HIDDEN도 404가 아니라 응답(purchasable=false) — 장바구니 유지(C-1) 링크 보호")
    void hiddenProductStillResponds() {
        Product product = product(2L, ProductStatus.HIDDEN, null);
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));

        ProductDetailResponse res = productService.getDetail(2L);

        assertThat(res.status()).isEqualTo("HIDDEN");
        assertThat(res.purchasable()).isFalse();
        assertThat(res.attributes()).isNull();
    }

    @Test
    @DisplayName("P-2 — 미존재 상품은 PRODUCT_NOT_FOUND")
    void notFoundThrows() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getDetail(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("P-2 — attributes JSON 파싱 실패 시 null로 응답 (상세 자체는 성공)")
    void brokenAttributesFallsBackToNull() throws Exception {
        Product product = product(3L, ProductStatus.ON_SALE, "broken");
        when(objectMapper.readTree("broken")).thenThrow(new JsonParseException(null, "bad json"));
        when(productRepository.findById(3L)).thenReturn(Optional.of(product));

        ProductDetailResponse res = productService.getDetail(3L);

        assertThat(res.id()).isEqualTo(3L);
        assertThat(res.attributes()).isNull();
    }
}
