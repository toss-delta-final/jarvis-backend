package com.jarvis.chat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({StreamTicketProperties.class, ChatProperties.class, LlmProperties.class})
public class ChatConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Spring→FastAPI 아웃바운드 — 타임아웃 필수 (03 §5: 연결 2s/응답 3s 기준).
     *
     * <p>{@code @Primary}는 아래 {@link #profileWriteRestClient()}가 생기면서 붙였다. 기존 주입부
     * ({@code LlmNotifyClient}·{@code HomeRecommendationClient})는 타입으로 받고 있어 후보가 둘이
     * 되는데, 파라미터 이름 폴백에 기대면 <b>이름이 바뀌는 순간 기동 자체가 실패</b>한다. 컨텍스트를
     * 띄우는 테스트가 없어 그 실패는 배포에서야 드러난다 — 기본값을 명시해 그 경로를 없앤다.
     */
    @Primary
    @Bean
    public RestClient llmRestClient() {
        return RestClient.builder().requestFactory(requestFactory(Duration.ofSeconds(3))).build();
    }

    /**
     * 취향 그래프 <b>쓰기</b> 경로 전용 (I-33·I-34·I-36·I-37) — AI 응답 예산이 3s라 기본 클라이언트와
     * 같은 3s로 두면 <b>Spring이 먼저 끊어 AI의 {@code 504 UPSTREAM_TIMEOUT}을 못 받는다.</b>
     * 기다리는 쪽을 1s 더 길게 둬야 상류 타임아웃이 그대로 관측된다(노션 M-12·M-13·M-15·M-16).
     * 조회(I-32)는 예산 2s라 기본 3s로 충분하므로 {@link #llmRestClient()}를 그대로 쓴다.
     */
    @Bean
    public RestClient profileWriteRestClient() {
        return RestClient.builder().requestFactory(requestFactory(Duration.ofSeconds(4))).build();
    }

    /**
     * {@code SimpleClientHttpRequestFactory}(JDK {@code HttpURLConnection})를 쓰면 <b>PATCH가 아예
     * 나가지 못한다</b> — {@code HttpURLConnection.setRequestMethod}가 GET·POST·HEAD·OPTIONS·PUT·
     * DELETE·TRACE만 허용해 {@code ProtocolException: Invalid HTTP method: PATCH}로 죽는다. 그 IOException은
     * {@code ResourceAccessException}이 돼 타임아웃·연결실패와 같은 자리에서 잡히고, FE에는 원인을 알 수 없는
     * 500만 남는다. 실제로 <b>M-12(I-33)가 body와 무관하게 100% 500</b>이었고 다른 메서드인 M-11·M-13·M-15·M-16만
     * 멀쩡했다(2026-08-10 FE 제보). {@code JdkClientHttpRequestFactory}는 {@code java.net.http.HttpClient}
     * 기반이라 메서드 화이트리스트가 없다.
     *
     * <p>HTTP/1.1 고정: {@code HttpClient} 기본값(HTTP/2)은 평문 h2c 업그레이드를 시도한다. 상대는 uvicorn
     * 한 대뿐이고 얻을 게 없는데 프로토콜 협상이라는 실패 지점만 는다 — 종전 와이어 동작을 그대로 둔다.
     */
    private static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
