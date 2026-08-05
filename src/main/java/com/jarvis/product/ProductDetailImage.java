package com.jarvis.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 상세페이지 하단에 순서대로 나열할 상세 이미지 (02 D42) — 소비처는 P-2 하나뿐이다.
 *
 * <p><b>{@code Product}에 {@code @OneToMany}로 매핑하지 않는다.</b> 상품을 여러 건 조회하는 경로
 * (P-4 인기·P-6 목록·CH-5 챗봇 카드·검색)에서 컬렉션을 건드리면 화면에 쓰이지도 않는 상세 이미지 때문에
 * N+1이 난다. 매핑 자체를 두지 않으면 접근 경로가 없어 그 사고가 원천적으로 발생하지 않는다.
 *
 * <p>{@code BaseTimeEntity}를 상속하지 않는 이유: 쓰기가 시드 적재뿐이라 갱신 개념이 없어 테이블에
 * {@code updated_at}이 없다(BaseTimeEntity 주석의 "created_at만 있는 테이블" 규칙).
 */
@Entity
@Table(name = "product_detail_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDetailImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * 0-based 노출 순서. 파일명(000, 001…)이 아니라 이 값이 정렬 기준이다 — 확장자가 상품 안에서도
     * 섞여(000.jpg + 001.png) 파일명 문자열 정렬은 어긋난다. 값이 연속일 필요는 없다.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 버킷 내 경로만: {@code products/{productId}/detail/000.jpg}. 호스트는 {@link ImageProperties} */
    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
