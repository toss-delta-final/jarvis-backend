package com.jarvis.category;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 2단 고정 계층 — parent_id NULL=대분류, 값 있음=소분류 (02 D20) */
@Entity
@Table(name = "category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 50)
    private String name;

    /** 소분류 전용 속성 축(키 배열 JSON) — 서버 검증 없는 soft 스키마 (02 D11) */
    @Column(name = "attribute_schema", columnDefinition = "json")
    private String attributeSchema;

    public boolean isRoot() {
        return parentId == null;
    }
}
