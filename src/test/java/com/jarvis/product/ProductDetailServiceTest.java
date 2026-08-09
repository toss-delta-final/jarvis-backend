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
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.dto.ProductDetailResponse;
import com.jarvis.review.ReviewService;
import com.jarvis.review.dto.RatingStats;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/** P-2 상품 상세 — HIDDEN도 404 아닌 응답(purchaseState=HIDDEN), 평점 실시간 집계 (04 §2) */
@ExtendWith(MockitoExtension.class)
class ProductDetailServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductOptionRepository productOptionRepository;
    @Mock com.jarvis.product.ProductStockRepository productStockRepository;
    @Mock BrandService brandService;
    @Mock CategoryService categoryService;
    @Mock ReviewService reviewService;
    @Mock ObjectMapper objectMapper;
    @Mock ProductDetailImageRepository productDetailImageRepository;
    @Mock RedisCache cache;
    /** 실제 조립 규칙을 그대로 태운다 — 목킹하면 슬래시 정규화가 검증에서 빠진다 */
    @Spy ImageProperties imageProperties = new ImageProperties("https://cdn.test");

    @InjectMocks ProductService productService;

    @BeforeEach
    void stubCache() {
        // 캐시는 검증 대상이 아니다 — 로더를 통과시켜 상세 조립 로직만 본다 (07 §3-1)
        lenient().when(cache.get(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    }

    @org.junit.jupiter.api.BeforeEach
    void stubStock() {
        // 재고는 product_stock에서 온다 (02 D33 개정). sumMap은 default 메서드라 목이 null을 주므로
        // 기본값을 깔아둔다 — 재고 시나리오가 필요한 테스트는 각자 덮어쓴다
        org.mockito.Mockito.lenient().when(productStockRepository.sumMap(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    java.util.Map<Long, Integer> map = new java.util.HashMap<>();
                    ((java.util.Collection<Long>) inv.getArgument(0)).forEach(id -> map.put(id, 10));
                    return map;
                });
    }

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
        lenient().when(productDetailImageRepository.findAllByProductIdOrderBySortOrderAsc(anyLong()))
                .thenReturn(List.of());
    }

    private ProductDetailImage detailImage(int sortOrder, String imageKey) {
        ProductDetailImage image = mock(ProductDetailImage.class,
                withSettings().strictness(Strictness.LENIENT));
        when(image.getSortOrder()).thenReturn(sortOrder);
        when(image.getImageKey()).thenReturn(imageKey);
        return image;
    }

    private Product product(Long id, ProductStatus status, String attributes) {
        Product product = mock(Product.class, withSettings().strictness(Strictness.LENIENT));
        when(product.getId()).thenReturn(id);
        when(product.getName()).thenReturn("상품" + id);
        when(product.getPrice()).thenReturn(8900);
        when(product.getOriginalPrice()).thenReturn(12900);
        when(product.getStatus()).thenReturn(status);
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
        // 블랙은 남고 화이트는 품절 — 상세는 검색과 달리 품절도 보여주고 이유를 표시한다 (2026-08-09)
        when(productStockRepository.findAllByProductId(1L)).thenReturn(List.of(
                com.jarvis.product.ProductStock.of(1L, 100L, 7),
                com.jarvis.product.ProductStock.of(1L, 101L, 0)));

        ProductDetailResponse res = productService.getDetail(1L);

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.name()).isEqualTo("상품1");
        assertThat(res.price()).isEqualTo(8900);
        assertThat(res.originalPrice()).isEqualTo(12900);
        assertThat(res.purchaseState()).isEqualTo("AVAILABLE");
        assertThat(res.status()).isEqualTo("ON_SALE");
        assertThat(res.attributes()).isSameAs(attrs);
        assertThat(res.category().name()).isEqualTo("티셔츠");
        assertThat(res.brand().logoUrl()).isEqualTo("logo.png");
        assertThat(res.options()).extracting(ProductDetailResponse.OptionResponse::optionId)
                .containsExactly(100L, 101L);
        // 품절 옵션도 목록에 남고, 왜 못 사는지가 옵션 줄에 붙는다
        assertThat(res.options()).extracting(ProductDetailResponse.OptionResponse::purchaseState)
                .containsExactly("AVAILABLE", "SOLD_OUT");
        assertThat(res.options()).extracting(ProductDetailResponse.OptionResponse::stockQuantity)
                .containsExactly(7, 0);
        assertThat(res.stockQuantity()).isEqualTo(7);
        assertThat(res.rating().average()).isEqualTo(4.6);
        assertThat(res.rating().count()).isEqualTo(82);
    }

    @Test
    @DisplayName("P-2 — HIDDEN도 404가 아니라 응답(purchaseState=HIDDEN) — 장바구니 유지(C-1) 링크 보호")
    void hiddenProductStillResponds() {
        Product product = product(2L, ProductStatus.HIDDEN, null);
        when(productRepository.findById(2L)).thenReturn(Optional.of(product));

        ProductDetailResponse res = productService.getDetail(2L);

        assertThat(res.status()).isEqualTo("HIDDEN");
        assertThat(res.purchaseState()).isEqualTo("HIDDEN");
        assertThat(res.attributes()).isNull();
    }

    @Test
    @DisplayName("P-2 — 상세 이미지: sort_order 순서 그대로, key에 호스트를 붙여 전체 URL로 (02 D42)")
    void detailImagesAreOrderedAndPrefixed() {
        Product product = product(4L, ProductStatus.ON_SALE, null);
        when(productRepository.findById(4L)).thenReturn(Optional.of(product));
        // 확장자가 섞여 있어도 파일명이 아니라 sort_order가 순서를 정한다.
        // 목 생성을 when(...) 인자 안에서 하면 중첩 스터빙이라 리스트를 먼저 만든다
        List<ProductDetailImage> images = List.of(
                detailImage(0, "products/4/detail/000.jpg"),
                detailImage(1, "products/4/detail/001.png"));
        when(productDetailImageRepository.findAllByProductIdOrderBySortOrderAsc(4L)).thenReturn(images);

        ProductDetailResponse res = productService.getDetail(4L);

        assertThat(res.detailImages()).containsExactly(
                "https://cdn.test/products/4/detail/000.jpg",
                "https://cdn.test/products/4/detail/001.png");
        // 대표 이미지는 배열에 섞이지 않는다 — 상단 1장과 하단 N장은 역할이 다르다
        assertThat(res.imageUrl()).isEqualTo("img.jpg");
        assertThat(res.detailImages()).doesNotContain("img.jpg");
    }

    @Test
    @DisplayName("P-2 — 상세 이미지가 없는 상품(131건)은 null이 아니라 빈 배열")
    void detailImagesEmptyWhenNone() {
        Product product = product(5L, ProductStatus.ON_SALE, null);
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));

        ProductDetailResponse res = productService.getDetail(5L);

        assertThat(res.detailImages()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("P-2 — 179장짜리 상품도 서버가 자르지 않고 전량 반환 (접기는 FE 소관)")
    void detailImagesAreNotTruncated() {
        Product product = product(6L, ProductStatus.ON_SALE, null);
        when(productRepository.findById(6L)).thenReturn(Optional.of(product));
        List<ProductDetailImage> images = java.util.stream.IntStream.range(0, 179)
                .mapToObj(i -> detailImage(i, String.format("products/6/detail/%03d.jpg", i)))
                .toList();
        when(productDetailImageRepository.findAllByProductIdOrderBySortOrderAsc(6L)).thenReturn(images);

        ProductDetailResponse res = productService.getDetail(6L);

        assertThat(res.detailImages()).hasSize(179);
        assertThat(res.detailImages().get(178)).isEqualTo("https://cdn.test/products/6/detail/178.jpg");
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
