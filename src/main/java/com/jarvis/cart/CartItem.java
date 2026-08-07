package com.jarvis.cart;

import com.jarvis.global.entity.BaseTimeEntity;
import com.jarvis.recommendation.ConversionAttribution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 = 주체(회원/게스트)당 cart_item 집합 — 헤더 테이블 없음 (02 §3).
 * member_id/guest_id 중 정확히 하나만 NOT NULL — XOR 서비스 검증 (02 D30).
 * 가격 컬럼 없음(현재가 표시 — 스냅샷은 주문 시점에만).
 */
@Entity
@Table(name = "cart_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseTimeEntity {

    public static final int MAX_QUANTITY = 99;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "guest_id", columnDefinition = "char(36)")
    private String guestId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "option_id")
    private Long optionId;

    @Column(nullable = false)
    private int quantity;

    /** 추천 카드에서 담았을 때만 채워진다 — 검증을 통과한 값만 들어온다 (노션 C-2·I-2) */
    @Column(name = "recommendation_request_id", columnDefinition = "char(36)")
    private String recommendationRequestId;

    @Column(name = "list_id", length = 64)
    private String listId;

    public static CartItem forMember(Long memberId, Long productId, Long optionId, int quantity,
                                     ConversionAttribution attribution) {
        CartItem item = new CartItem();
        item.memberId = memberId;
        item.productId = productId;
        item.optionId = optionId;
        item.quantity = quantity;
        item.applyAttribution(attribution);
        return item;
    }

    public static CartItem forGuest(String guestId, Long productId, Long optionId, int quantity,
                                    ConversionAttribution attribution) {
        CartItem item = new CartItem();
        item.guestId = guestId;
        item.productId = productId;
        item.optionId = optionId;
        item.quantity = quantity;
        item.applyAttribution(attribution);
        return item;
    }

    /**
     * 재담기 시 출처 갱신 — 노션 O-1 「attribution 규칙」의 {@code last_valid_touch}를 따라
     * <b>검증을 통과한 새 출처만 덮어쓴다.</b> 추천 없이 다시 담았다고 이전 출처를 지우면
     * 마지막 접점이 아니라 "마지막 접점의 유무"로 귀속이 갈려 버린다.
     */
    public void applyAttribution(ConversionAttribution attribution) {
        if (attribution == null || !attribution.isPresent()) {
            return;
        }
        this.recommendationRequestId = attribution.recommendationRequestId();
        this.listId = attribution.listId();
    }

    /** 주문 스냅샷으로 복사할 출처 (노션 O-1) — 담기 때 검증이 끝나 주문에선 재검증하지 않는다 */
    public ConversionAttribution attribution() {
        return listId == null ? ConversionAttribution.NONE
                : new ConversionAttribution(recommendationRequestId, listId);
    }

    /**
     * 게스트 라인이 회원 라인에 합산되며 사라질 때 출처를 물려준다 (02 D30 병합, 노션 O-1
     * 「게스트→회원 병합」) — <b>빈 칸일 때만</b> 채운다. 채우는 건 정보 추가지만 덮는 건 손실이다.
     */
    void inheritAttributionFrom(CartItem source) {
        if (this.listId == null) {
            this.recommendationRequestId = source.recommendationRequestId;
            this.listId = source.listId;
        }
    }

    /** 합산 상한 99 클램프 — 로그인 병합 전용. 담기(C-2/I-2) 초과는 호출 전 400 (노션 C-2, 2026-07-18) */
    public void addQuantity(int amount) {
        this.quantity = Math.min(MAX_QUANTITY, this.quantity + amount);
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    /** 게스트 → 회원 승계 (02 D30) — 회원 장바구니에 동일 상품+옵션이 없을 때 소유자만 변경 */
    public void assignTo(Long memberId) {
        this.memberId = memberId;
        this.guestId = null;
    }

    public boolean isOwnedBy(Long memberId, String guestId) {
        if (this.memberId != null) {
            return this.memberId.equals(memberId);
        }
        return this.guestId != null && this.guestId.equals(guestId);
    }
}
