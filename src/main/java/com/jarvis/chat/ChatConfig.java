package com.jarvis.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({StreamTicketProperties.class, ChatProperties.class, LlmProperties.class})
public class ChatConfig {

    /** Spring→FastAPI 아웃바운드 — 타임아웃 필수 (03 §5: 연결 2s/응답 3s 기준) */
    @Bean
    public RestClient llmRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 취향 그래프 <b>쓰기</b> 경로 전용 (I-33·I-34·I-36·I-37) — AI 응답 예산이 3s라 기본 클라이언트와
     * 같은 3s로 두면 <b>Spring이 먼저 끊어 AI의 {@code 504 UPSTREAM_TIMEOUT}을 못 받는다.</b>
     * 기다리는 쪽을 1s 더 길게 둬야 상류 타임아웃이 그대로 관측된다(노션 M-12·M-13·M-15·M-16).
     * 조회(I-32)는 예산 2s라 기본 3s로 충분하므로 {@link #llmRestClient()}를 그대로 쓴다.
     */
    @Bean
    public RestClient profileWriteRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
