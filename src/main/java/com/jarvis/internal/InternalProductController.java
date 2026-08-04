package com.jarvis.internal;

import com.jarvis.global.response.ApiResponse;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCandidateResponse;
import com.jarvis.product.dto.ProductChangesResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** I-1·I-3·I-17 (04 §10) — 도메인 서비스 재사용, 자체 로직 없음 (03 §3) */
@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;

    /**
     * I-1 — 추천 라운드1 후보 조회 (05 §I-1). 2026-07-27 개정: 후보 수 상한 없음(정형조건 일치
     * 전체 반환), `brandName`은 반복 파라미터로 복수 지정 가능.
     * 2026-08-03 개정: `color`도 반복 파라미터 — 같은 색의 표기가 789종이라(네이비 636 vs 남색 8)
     * 문자열 하나로는 안 잡힌다. 동의어 확장은 LLM 팀 소관이고 BE는 받은 것들을 OR로 본다.
     */
    @GetMapping("/search")
    public ApiResponse<List<ProductCandidateResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) Integer minPrice,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(required = false) List<String> brandName,
            @RequestParam(required = false) List<String> color) {
        return ApiResponse.success(productService.searchCandidates(
                keyword, categoryName, minPrice, maxPrice, brandName, color));
    }

    /** I-3 — 인기 상품(무관 질문 시 카드 유지용), 응답 형식 I-1과 동일 (05 §I-3) */
    @GetMapping("/popular")
    public ApiResponse<List<ProductCandidateResponse>> popular(
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.success(productService.getPopularCandidates(size));
    }

    /**
     * I-17 — 벡터DB 동기화 배치 pull (05 §I-17). (updatedAt, id) keyset 커서(Base64URL),
     * since="0"이면 전체 구축. 잘못된 커서는 400 INVALID_CURSOR.
     */
    @GetMapping("/changes")
    public ApiResponse<ProductChangesResponse> changes(
            @RequestParam(defaultValue = "0") String since,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(productService.getChanges(since, limit));
    }
}
