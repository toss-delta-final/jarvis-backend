package com.jarvis.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 행동 이벤트 토픽·컨슈머 그룹 이름 (08 D10). 값은 전부 {@code application.yml}에서 파생된다.
 *
 * <p>이름을 상수가 아니라 프로퍼티로 두는 이유 — 브로커가 1대뿐이라(D6) dev와 운영이 같은 브로커에
 * 붙는다. 이름이 컴파일 타임에 고정되면 두 환경이 <b>같은 컨슈머 그룹의 멤버</b>가 되어 운영 이벤트
 * 일부를 dev가 가져가 커밋한다. 접두어는 그 합류를 막는다.
 *
 * <p>리스너 애노테이션도 같은 프로퍼티 키를 읽는다 — 이름 리터럴이 yml 한 곳에만 있어야
 * "선언한 이름"과 "실제 쓰는 이름"이 갈리지 않는다 (08 D3 연혁).
 */
@ConfigurationProperties(prefix = "app.kafka")
public record BehaviorStreamProperties(String prefix, String topic, String dlt, Groups groups) {

    public record Groups(String persister, String visitorTracker, String dltMonitor) {
    }
}
