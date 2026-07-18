package com.jarvis.category;

import com.jarvis.category.dto.CategoryTreeResponse;
import com.jarvis.global.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** P-1 (04 §2) */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<List<CategoryTreeResponse>> tree() {
        return ApiResponse.success(categoryService.getTree());
    }
}
