package com.jarvis.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실제 {@code application.yml}이 이름을 제대로 파생시키는지 본다 (08 D10).
 *
 * <p>이 테스트가 필요한 이유 — 접두어는 yml 안에서 중첩 플레이스홀더로 풀린다. 그 배선이 깨지면
 * 앱이 <b>기동 자체를 못 하거나</b>(플레이스홀더 미해석) 접두어가 일부에만 붙는데, 이 repo에는
 * 전체 컨텍스트를 띄우는 테스트가 없어 배포 전까지 아무도 모른다.
 */
class BehaviorStreamPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EnableProperties.class);

    @EnableConfigurationProperties(BehaviorStreamProperties.class)
    static class EnableProperties {
    }

    @Test
    @DisplayName("접두어가 없으면 기존 이름 그대로 — 운영은 이 경로라 토픽·오프셋이 그대로 이어진다")
    void keepsOriginalNamesWithoutPrefix() {
        runner.run(context -> {
            BehaviorStreamProperties names = context.getBean(BehaviorStreamProperties.class);
            assertThat(names.topic()).isEqualTo("behavior-events");
            assertThat(names.dlt()).isEqualTo("behavior-events-dlt");
            assertThat(names.groups().persister()).isEqualTo("persister");
            assertThat(names.groups().visitorTracker()).isEqualTo("visitor-tracker");
            assertThat(names.groups().dltMonitor()).isEqualTo("dlt-monitor");
        });
    }

    @Test
    @DisplayName("접두어는 토픽과 컨슈머 그룹 **양쪽**에 붙는다 — 그룹이 안 갈리면 dev가 운영 그룹에 합류한다")
    void prefixesBothTopicsAndGroups() {
        runner.withPropertyValues("APP_KAFKA_PREFIX=dev-").run(context -> {
            BehaviorStreamProperties names = context.getBean(BehaviorStreamProperties.class);
            assertThat(names.topic()).isEqualTo("dev-behavior-events");
            assertThat(names.dlt()).isEqualTo("dev-behavior-events-dlt");
            assertThat(names.groups().persister()).isEqualTo("dev-persister");
            assertThat(names.groups().visitorTracker()).isEqualTo("dev-visitor-tracker");
            assertThat(names.groups().dltMonitor()).isEqualTo("dev-dlt-monitor");
        });
    }
}
