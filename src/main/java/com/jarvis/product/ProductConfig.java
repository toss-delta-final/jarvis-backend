package com.jarvis.product;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 상품 도메인 설정 바인딩 (ChatConfig와 같은 패턴 — 도메인 패키지가 자기 프로퍼티를 등록한다) */
@Configuration
@EnableConfigurationProperties(ImageProperties.class)
public class ProductConfig {
}
