package com.jarvis.product;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도메인 경계를 넘는 참조(brand/category)는 객체 대신 id 보관 (03 §3-1).
 * 할인율·평점은 파생 계산 — 컬럼 없음 (02 D9·D15).
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /** 소분류(leaf)만 참조 — 서비스·시드 검증 (02 D20·D26②) */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "original_price", nullable = false)
    private int originalPrice;

    @Column(nullable = false)
    private int price;

    // 재고 필드 없음 — ProductStock으로 이관 (02 D33 개정). 여기 두면 옵션이 있는 상품에서
    // 거짓말하는 값이 된다. 합계가 필요한 화면은 ProductStockRepository에서 집계한다

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /** 크롤링 시점 누적 판매량, 시드 후 불변 — 표시 판매량 = 이 값 + order_item 집계 (02 D18) */
    @Column(name = "base_sales_count", nullable = false)
    private int baseSalesCount;

    @Column(length = 500)
    private String summary;

    /** 키 축은 category.attribute_schema, 값은 자유 텍스트 JSON (02 D7·D11) */
    @Column(columnDefinition = "json")
    private String attributes;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    /** I-10 등록 (04 §10) — base_sales_count는 크롤링 전용이라 0 고정 (02 D18) */
    public static Product create(Long brandId, Long categoryId, String name, int originalPrice,
                                 int price, String imageUrl, String summary,
                                 String attributes, String description, ProductStatus status) {
        Product product = new Product();
        product.brandId = brandId;
        product.categoryId = categoryId;
        product.name = name;
        product.originalPrice = originalPrice;
        product.price = price;
        product.imageUrl = imageUrl;
        product.baseSalesCount = 0;
        product.summary = summary;
        product.attributes = attributes;
        product.description = description;
        product.status = status;
        return product;
    }

    // I-11 상품 수정 (04 §10) — 검증(price ≤ originalPrice 등)·change log는 SellerProductService 소관

    public void changeName(String name) {
        this.name = name;
    }

    public void changeSummary(String summary) {
        this.summary = summary;
    }

    public void changeAttributes(String attributes) {
        this.attributes = attributes;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changeImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void changePrice(int price) {
        this.price = price;
    }

    public void changeOriginalPrice(int originalPrice) {
        this.originalPrice = originalPrice;
    }

    public void changeStatus(ProductStatus status) {
        this.status = status;
    }
}
