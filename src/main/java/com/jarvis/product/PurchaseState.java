package com.jarvis.product;

/**
 * 상품을 지금 살 수 있는지, 못 산다면 왜 못 사는지 (04 §2·§3, 2026-08-05 FE 요청).
 *
 * <p>구 {@code purchasable} boolean은 품절과 숨김을 하나로 뭉개서, 같은 false가 화면마다 다른 문구로
 * 나갔다(찜은 "판매 중지", 브랜드 목록은 "품절"). 둘은 사용자가 해야 할 행동이 다르다 — 품절은
 * 기다리면 되고 숨김은 다른 걸 찾아야 한다.
 *
 * <p>파생 규칙을 여기 한 곳에 둬서 화면·API별로 갈리지 않게 한다.
 */
public enum PurchaseState {

    AVAILABLE,
    /** 재고 부족 — 재입고되면 다시 살 수 있다 */
    SOLD_OUT,
    /** 판매자가 내림 — 돌아오지 않는다 */
    HIDDEN;

    /**
     * 숨김이 품절보다 우선한다 — 숨김은 돌아오지 않는 상품이라 "재입고되면 알려드릴게요"로 안내하면 안 된다.
     *
     * <p><b>재고를 인자로 받는다</b>(02 D33 개정) — {@code Product}에 재고가 없어졌고, 호출부마다
     * 봐야 할 재고가 다르다. 목록·상세는 <b>구매 가능한 옵션 재고의 합계</b>를, 장바구니·주문은
     * <b>그 줄이 담은 옵션의 재고</b>를 넘긴다. 상품을 넘기는 편의 오버로드를 남기지 않은 것은
     * 의도다 — 남기면 어느 재고인지 모르는 채 호출되고 조용히 틀린 답이 나간다.
     *
     * @param availableQuantity 판정 대상 재고 (합계 또는 그 옵션의 수량)
     * @param requestedQuantity 이만큼 살 수 있는지 — 목록·상세는 1(하나라도 살 수 있나), 주문(O-1)은 주문 수량
     */
    public static PurchaseState of(ProductStatus status, int availableQuantity, int requestedQuantity) {
        if (status != ProductStatus.ON_SALE) {
            return HIDDEN;
        }
        return availableQuantity < requestedQuantity ? SOLD_OUT : AVAILABLE;
    }

    /** 목록·상세용 — 하나라도 살 수 있으면 AVAILABLE */
    public static PurchaseState of(ProductStatus status, int availableQuantity) {
        return of(status, availableQuantity, 1);
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }
}
