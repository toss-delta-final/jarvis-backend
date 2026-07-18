package com.jarvis.brand;

import com.jarvis.brand.dto.BrandDetailResponse;
import com.jarvis.category.CategoryService;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.ProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P-6 (04 §2). 브랜드 정보 + 상품 목록의 조합만 담당(분기·규칙 없음) —
 * BrandService→ProductService 의존을 만들면 P-2(브랜드 요약)와 순환이라 컨트롤러에서 조립.
 */
@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@Validated
public class BrandController {

    private final BrandService brandService;
    private final ProductService productService;
    private final CategoryService categoryService;

    @GetMapping("/{id}")
    public ApiResponse<BrandDetailResponse> detail(
            @PathVariable Long id,
            @RequestParam(required = false) Long category,
            @RequestParam(defaultValue = "popular") @Pattern(regexp = "popular|latest|price_asc|price_desc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        Brand brand = brandService.getBrand(id);
        return ApiResponse.success(BrandDetailResponse.from(brand,
                categoryService.getSummaries(productService.getBrandCategoryIds(id)),
                productService.getBrandProducts(id, category, sort, page, size)));
    }
}
