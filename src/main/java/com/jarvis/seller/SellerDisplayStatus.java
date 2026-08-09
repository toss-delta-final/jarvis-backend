package com.jarvis.seller;

import com.jarvis.product.ProductStatus;

/**
 * S-3 화면 표시 상태 (노션 S-3) — DDL의 product.status에 SOLD_OUT은 없다(§7-⑤).
 * 화면 3탭을 맞추려 서버가 재고와 조합해 파생한다. HIDDEN이 재고보다 우선(숨김 상품은 재고 0이어도 품절 아님).
 * DELETED는 S-3 목록에서 아예 제외되므로 여기 대응 값이 없다.
 * 원본 status는 별도로 함께 내려간다 — 챗봇 수정(I-11) 토글 대상은 파생값이 아니라 원본이라서.
 */
public enum SellerDisplayStatus {
    ON_SALE,
    SOLD_OUT,
    HIDDEN;

    /**
     * 원본 status + 재고 → 표시 상태 (판매 중이 아니면 재고보다 우선).
     *
     * <p>{@code != ON_SALE}로 보는 이유: DELETED는 S-3 목록에서 이미 제외되지만, 만약 새어 들어와도
     * 재고가 남았다는 이유로 ON_SALE로 분류되면 안 된다 — 삭제한 상품이 판매 중으로 보이는 사고다.
     */
    public static SellerDisplayStatus of(ProductStatus status, int stockQuantity) {
        if (status != ProductStatus.ON_SALE) {
            return HIDDEN;
        }
        return stockQuantity == 0 ? SOLD_OUT : ON_SALE;
    }

    /**
     * 재고를 인자로 받는다 (02 D33 개정 — {@code Product}에 재고가 없다).
     * 넘기는 값은 <b>옵션 재고의 합계</b>이며, 합계 0과 "살 수 있는 옵션 없음"은 같은 뜻이다
     * (수량이 음수일 수 없으므로). 노션 S-3의 새 판정 문구와 이 산식이 어긋나지 않는 이유다.
     */
    public boolean matches(ProductStatus status, int stockQuantity) {
        return this == of(status, stockQuantity);
    }
}
