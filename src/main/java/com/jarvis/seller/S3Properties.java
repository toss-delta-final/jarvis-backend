package com.jarvis.seller;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 상품 이미지 업로드 버킷 (S-6). 시크릿이 아니라 공개 버킷 정보라 기본값을 yml에 두고 배포만 덮어쓴다.
 *
 * <p>자격증명은 여기 없다 — 인스턴스 IAM 역할을 AWS SDK 기본 체인이 찾는다.
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(String bucket,
                           String region,
                           String keyPrefix,
                           long presignExpiryMinutes,
                           long maxUploadBytes) {
}
