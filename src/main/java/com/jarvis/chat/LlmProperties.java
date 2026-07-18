package com.jarvis.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FastAPI 연동 주소 (03 §5) — baseUrl은 Spring→FastAPI 아웃바운드(P-5·I-20, 빈 값=미기동 skip),
 * sseUrl은 CH-1 응답에 실어 FE가 직결할 공개 URL.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String baseUrl, String sseUrl) {
}
