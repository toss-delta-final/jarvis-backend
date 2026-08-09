package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;

/** 구매 가능 상태 파생 (04 §2·§3) — 화면·API별로 갈리지 않도록 규칙을 여기 한 곳에 고정한다 */
class PurchaseStateTest {

    @Test
    @DisplayName("판매중 + 재고 있음은 AVAILABLE, 재고 0은 SOLD_OUT")
    void derivesFromStatusAndStock() {
        assertThat(PurchaseState.of(ProductStatus.ON_SALE, 10)).isEqualTo(PurchaseState.AVAILABLE);
        assertThat(PurchaseState.of(ProductStatus.ON_SALE, 0)).isEqualTo(PurchaseState.SOLD_OUT);
    }

    @Test
    @DisplayName("숨김이 품절보다 우선 — 숨김은 돌아오지 않아 재입고 안내를 하면 안 된다")
    void hiddenWinsOverSoldOut() {
        // 재고까지 0이어도 HIDDEN — FE는 이 값으로 "재입고 알림" 대신 찜 해제를 권유한다
        assertThat(PurchaseState.of(ProductStatus.HIDDEN, 0)).isEqualTo(PurchaseState.HIDDEN);
        assertThat(PurchaseState.of(ProductStatus.HIDDEN, 10)).isEqualTo(PurchaseState.HIDDEN);
    }

    @Test
    @DisplayName("삭제도 소비자에겐 HIDDEN — 숨김이든 삭제든 '판매자가 내림'이고 살 수 없다")
    void deletedCollapsesIntoHidden() {
        assertThat(PurchaseState.of(ProductStatus.DELETED, 10)).isEqualTo(PurchaseState.HIDDEN);
        assertThat(PurchaseState.of(ProductStatus.DELETED, 0)).isEqualTo(PurchaseState.HIDDEN);
    }

    @Test
    @DisplayName("주문(O-1)은 주문 수량 기준 — 재고보다 많이 주문하면 SOLD_OUT")
    void comparesAgainstRequestedQuantity() {
        assertThat(PurchaseState.of(ProductStatus.ON_SALE, 3, 3)).isEqualTo(PurchaseState.AVAILABLE);
        assertThat(PurchaseState.of(ProductStatus.ON_SALE, 3, 4)).isEqualTo(PurchaseState.SOLD_OUT);
    }

    @Test
    @DisplayName("목록·상세는 하나라도 살 수 있으면 AVAILABLE (수량 1 기준)")
    void listDefaultsToQuantityOne() {
        assertThat(PurchaseState.of(ProductStatus.ON_SALE, 1)).isEqualTo(PurchaseState.AVAILABLE);
    }

}
