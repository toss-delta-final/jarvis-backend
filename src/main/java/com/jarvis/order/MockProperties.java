package com.jarvis.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mock 배송/클레임 전이 간격 (01 §6) — 데모 리허설 때 짧게 줄일 수 있게 설정으로 분리 (01 D4).
 * 기본 5/5/10/5분 (application.yml app.mock.*).
 */
@ConfigurationProperties(prefix = "app.mock")
public record MockProperties(long shippingMinutes, long deliveryMinutes,
                             long confirmMinutes, long claimApproveMinutes) {
}
