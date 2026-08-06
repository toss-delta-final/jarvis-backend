package com.jarvis.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mock 배송/클레임 전이 간격 (01 §6) — 데모 리허설 때 짧게 줄일 수 있게 설정으로 분리 (01 D4).
 * 기본 5/10/5분 (application.yml app.mock.*).
 *
 * <p>구 shippingMinutes(ORDERED→SHIPPING 간격)는 2026-08-06 제거했다 — 그 전이가 판매자 발송(I-30)
 * 소관이 되면서 스케줄러가 더는 일으키지 않기 때문이다(01 D4 개정).
 */
@ConfigurationProperties(prefix = "app.mock")
public record MockProperties(long deliveryMinutes, long confirmMinutes, long claimApproveMinutes) {
}
