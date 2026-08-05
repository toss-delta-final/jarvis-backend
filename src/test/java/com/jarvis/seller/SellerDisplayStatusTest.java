package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jarvis.product.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S-3 표시 상태 파생 (노션 S-3) — 원본 status + 재고를 화면 3탭으로 접는다 */
class SellerDisplayStatusTest {

    @Test
    @DisplayName("판매중은 재고로 갈린다 — 재고 0이면 SOLD_OUT")
    void derivesFromStock() {
        assertThat(SellerDisplayStatus.of(ProductStatus.ON_SALE, 5)).isEqualTo(SellerDisplayStatus.ON_SALE);
        assertThat(SellerDisplayStatus.of(ProductStatus.ON_SALE, 0)).isEqualTo(SellerDisplayStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("숨김이 재고보다 우선 — 숨긴 상품은 재고 0이어도 품절이 아니다")
    void hiddenWinsOverStock() {
        assertThat(SellerDisplayStatus.of(ProductStatus.HIDDEN, 0)).isEqualTo(SellerDisplayStatus.HIDDEN);
        assertThat(SellerDisplayStatus.of(ProductStatus.HIDDEN, 5)).isEqualTo(SellerDisplayStatus.HIDDEN);
    }

    @Test
    @DisplayName("삭제 상품이 새어 들어와도 ON_SALE로 분류되지 않는다 (안전망 — S-3 목록에서 이미 제외됨)")
    void deletedNeverLooksOnSale() {
        // 재고가 남은 채 삭제된 상품이 판매 중으로 보이면 판매자가 재고를 오해한다
        assertThat(SellerDisplayStatus.of(ProductStatus.DELETED, 5)).isEqualTo(SellerDisplayStatus.HIDDEN);
        assertThat(SellerDisplayStatus.of(ProductStatus.DELETED, 0)).isEqualTo(SellerDisplayStatus.HIDDEN);
    }
}
