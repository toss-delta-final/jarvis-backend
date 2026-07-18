package com.jarvis.category.dto;

import com.jarvis.category.Category;
import java.util.List;

/** P-1 — 대분류+소분류 트리. 아이콘은 FE 정적 매핑이라 BE 미제공 (04 §2) */
public record CategoryTreeResponse(Long id, String name, List<Child> children) {

    public record Child(Long id, String name) {

        public static Child from(Category category) {
            return new Child(category.getId(), category.getName());
        }
    }

    public static CategoryTreeResponse from(Category root, List<Category> children) {
        return new CategoryTreeResponse(root.getId(), root.getName(),
                children.stream().map(Child::from).toList());
    }
}
