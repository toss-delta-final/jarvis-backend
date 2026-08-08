package com.jarvis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

}
