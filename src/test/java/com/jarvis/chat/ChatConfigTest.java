package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * RestClient bean이 둘이 된 뒤(취향 그래프 쓰기 전용 4s 추가) <b>타입 주입이 깨지지 않는지</b>
 * 지킨다. 기존 주입부({@code LlmNotifyClient}·{@code HomeRecommendationClient})는 타입으로 받는데,
 * 후보가 둘이면 {@code @Primary} 없이는 기동 자체가 실패한다.
 *
 * <p>이 프로젝트에 스프링 컨텍스트를 띄우는 테스트가 없어 그 실패는 <b>배포에서야</b> 드러난다.
 * {@code ApplicationContextRunner}는 DB·Redis 없이 설정 클래스만 올려 그 구멍을 메운다.
 */
class ChatConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class)
            .withPropertyValues("app.llm.base-url=http://ai.test");

    @Test
    @DisplayName("RestClient 후보가 둘이어도 타입만으로 주입이 결정된다 — 없으면 앱이 안 뜬다")
    void typeInjectionStaysUnambiguous() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RestClient.class)).hasSize(2);
            // 후보가 둘인데 @Primary가 없으면 이 줄에서 NoUniqueBeanDefinitionException이 난다
            assertThat(context.getBean(RestClient.class))
                    .isSameAs(context.getBean("llmRestClient", RestClient.class));
        });
    }

    @Test
    @DisplayName("쓰기 전용 클라이언트는 별개 인스턴스다 — 같으면 4s가 안 걸린 것")
    void profileWriteClientIsSeparate() {
        runner.run(context -> assertThat(context.getBean("profileWriteRestClient", RestClient.class))
                .isNotSameAs(context.getBean("llmRestClient", RestClient.class)));
    }

    /**
     * <b>진짜 소켓으로 PATCH를 보낸다.</b> 이 자리를 mock으로 대신할 수 없다 —
     * {@code MockRestServiceServer.bindTo(builder)}는 request factory를 통째로 갈아끼워서
     * 여기서 터지는 종류의 결함을 <b>구조적으로 못 본다</b>. 실제로 {@code ProfileGraphClient} 테스트는
     * 전부 통과하는데 M-12는 운영에서 body와 무관하게 100% 500이었다(2026-08-10 FE 제보):
     * {@code SimpleClientHttpRequestFactory}의 {@code HttpURLConnection}이 PATCH를 거부해
     * 요청이 소켓에 나가지도 못했다. 메서드 하나 때문에 생긴 구멍이라 메서드로 확인해야 한다.
     */
    @Test
    @DisplayName("아웃바운드 PATCH가 실제로 나간다 — 요청 팩토리가 메서드를 거르면 여기서 잡힌다")
    void outboundPatchReachesTheServer() throws Exception {
        AtomicReference<String> seenMethod = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/edge", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://" + server.getAddress().getHostString()
                + ":" + server.getAddress().getPort() + "/edge";

        try {
            runner.run(context -> {
                // 지금 PATCH를 쓰는 건 I-33뿐이지만 한쪽만 고쳐두면 다음 PATCH가 같은 데 빠진다
                for (String beanName : new String[] {"llmRestClient", "profileWriteRestClient"}) {
                    seenMethod.set(null);
                    String response = context.getBean(beanName, RestClient.class)
                            .patch()
                            .uri(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"predicate\":\"likes\"}")
                            .retrieve()
                            .body(String.class);

                    assertThat(seenMethod.get()).as(beanName).isEqualTo("PATCH");
                    assertThat(response).as(beanName).isEqualTo("{\"ok\":true}");
                }
            });
        } finally {
            server.stop(0);
        }
    }

}
