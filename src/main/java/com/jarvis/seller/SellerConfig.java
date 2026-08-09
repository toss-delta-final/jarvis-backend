package com.jarvis.seller;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** 판매자 도메인 설정 바인딩 (ProductConfig와 같은 패턴 — 도메인 패키지가 자기 프로퍼티를 등록한다) */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class SellerConfig {

    /**
     * presigned URL 발급 전용 — 실제 S3 호출을 하지 않으므로 HTTP 클라이언트가 필요 없다.
     *
     * <p>자격증명은 <b>서명 시점에</b> 기본 체인으로 해석된다. 로컬처럼 자격증명이 없는 환경에서도
     * 기동은 되고 이 엔드포인트를 부를 때만 실패한다 — 기동을 막으면 S3와 무관한 개발까지 멈춘다.
     */
    @Bean
    S3Presigner s3Presigner(S3Properties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
