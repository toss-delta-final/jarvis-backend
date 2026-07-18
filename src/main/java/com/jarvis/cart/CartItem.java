package com.jarvis.cart;

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

    public static CartItem forMember(Long memberId, Long productId, Long optionId, int quantity) {
        CartItem item = new CartItem();
        item.memberId = memberId;
        item.productId = productId;
        item.optionId = optionId;
        item.quantity = quantity;
        return item;
    }

    public static CartItem forGuest(String guestId, Long productId, Long optionId, int quantity) {
        CartItem item = new CartItem();
        item.guestId = guestId;
        item.productId = productId;
        item.optionId = optionId;
        item.quantity = quantity;
        return item;
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
