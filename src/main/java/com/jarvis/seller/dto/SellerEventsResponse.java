package com.jarvis.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * I-13 행동 이벤트 집계 (노션 I-13) — 판매자 스코프의 귀속 경로는 이벤트마다 다르다:
 * product_view·add_to_cart는 behavior_events.product_id→product→brand, checkout_start는
 * properties.productIds JSON, purchaseComplete는 order_item×product×brand(주문 정본, #62).
 * shape가 groupBy로 갈린다: product → rows+total, eventType → counts, date → series — NON_NULL로 상호 배제.
 * counts 키는 event_type camelCase(product_view→productView).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SellerEventsResponse(Long brandId, LocalDate from, LocalDate to, String groupBy,
                                   List<ProductRow> rows, Integer total,
                                   Map<String, Long> counts, List<Map<String, Object>> series) {

    public static SellerEventsResponse ofProduct(Long brandId, LocalDate from, LocalDate to,
                                                 List<ProductRow> rows) {
        return new SellerEventsResponse(brandId, from, to, "product", rows, rows.size(), null, null);
    }

    public static SellerEventsResponse ofEventType(Long brandId, LocalDate from, LocalDate to,
                                                   Map<String, Long> counts) {
        return new SellerEventsResponse(brandId, from, to, "eventType", null, null, counts, null);
    }

    public static SellerEventsResponse ofDate(Long brandId, LocalDate from, LocalDate to,
                                              List<Map<String, Object>> series) {
        return new SellerEventsResponse(brandId, from, to, "date", null, null, null, series);
    }

    /**
     * viewToCartRate = addToCart/productView(분모 0이거나 두 타입이 조회 대상 밖이면 null)
     *
     * <p><b>2026-08-06 신설 5필드</b>:
     * <ul>
     *   <li>{@code salesQuantity} — 판매 <b>수량</b>. {@code purchaseComplete}(주문 <b>건수</b>)와
     *       단위가 다르다 — 61건 / 74개는 "주문당 평균 1.2개"를 뜻하므로 직접 비교하면 안 된다.
     *       취소·반품 처리도 다르다: 이쪽은 아이템 상태 3종을 제외하고(I-6 {@code salesCount}와 같은 산식)
     *       저쪽은 아이템 상태를 보지 않는다(I-7 4단 정합). <b>같은 행의 두 필드가 다른 규칙을 쓰는 건
     *       의도된 것</b>이다 — 수량·매출 계열이 S-1·I-6과 하나의 숫자여야 판매자가 화면 간 불일치를
     *       보지 않는다. {@code eventType} 필터에 {@code purchase_complete}가 없으면 {@code null}
     *   <li>체류시간 4종 — {@code medianDwellSeconds}가 <b>주 지표</b>다. 체류시간은 롱테일 분포라
     *       평균은 방치 탭 몇 건에 크게 흔들린다. {@code avgDwellSeconds}는 분포 왜곡 확인용 참고값이고,
     *       {@code dwellSampleCount} 없이 평균·중앙값만 해석하면 안 된다(표본이 적으면 판정 보류).
     *       {@code dwellSource}는 산출 방식 표기 — 지금은 {@code next_event}뿐이며 {@code page_leave}를
     *       수집하게 되면 값이 바뀐다. <b>판정 기준이 바뀌면 집계 구간을 나눠야 하므로</b> 응답에 남긴다
     * </ul>
     * {@code eventType} 필터에 {@code product_view}가 없거나 표본이 0이면 체류시간 4종 모두 {@code null}이다.
     */
    public record ProductRow(Long productId, String productName, Map<String, Long> counts,
                             Long salesQuantity,
                             Double medianDwellSeconds, Double avgDwellSeconds,
                             Long dwellSampleCount, String dwellSource,
                             Double viewToCartRate, Long uniqueVisitors) {
    }
}
