package com.jarvis.seller.dto;

/**
 * I-9·I-11 응답의 {@code stocks[]} 원소 (노션 I-9·I-11, 2026-08-09).
 *
 * <p>에이전트가 "블랙 재고 50으로 바꿔줘"를 처리하려면 <b>이름 → id 변환</b>이 필요한데 그 출처가
 * 없었다. 그래서 I-9가 {@code optionName}을 함께 내리고, 그 {@code optionId}를 I-11에 그대로 넣는다.
 *
 * <p>판매자용이라 <b>품절 옵션도 그대로 내려간다</b> — 구매자용 I-1이 품절을 빼는 것과 반대다.
 * 판매자는 무엇이 품절인지 알아야 채워 넣을 수 있다. 옵션 없는 상품은 둘 다 {@code null}.
 */
public record StockView(Long optionId, String optionName, int quantity) {
}
