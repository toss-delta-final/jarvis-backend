package com.jarvis.global.event;

import com.jarvis.global.config.BehaviorStreamProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 컨슈머가 <b>브로커와 실제로 통하고 있는지</b>를 주기적으로 확인해 신호로 남긴다 (08 D5).
 *
 * <p><b>왜 폴링 여부로는 안 되는가</b>(2026-08-10 배포 검증에서 드러남): 컨슈머는 브로커가 죽어도
 * 빈 폴을 계속 돌고 유휴 이벤트도 계속 낸다. 그걸 생존 신호로 쓰면 <b>브로커가 죽은 동안에도
 * "정상"이라고 답한다</b> — 실제로 그렇게 오진됐다.
 *
 * <p>대신 컨슈머 클라이언트가 이미 갖고 있는 지표를 읽는다. 컨슈머 그룹은 원래 코디네이터에게
 * 하트비트를 보내므로(그래야 그룹에서 안 쫓겨난다), <b>합성 이벤트를 만들 필요 없이</b> 그 결과만 보면 된다.
 * 실측(2026-08-10): 브로커 정상 → {@code connection-count=3, last-heartbeat-seconds-ago=2},
 * 브로커 정지 → {@code connection-count=0}으로 즉시 떨어지고 하트비트 경과가 9→19→29로 증가.
 *
 * <p>둘 다 보는 이유 — 연결 수는 즉시 반응하지만 순간적으로 튈 수 있고, 하트비트 경과는
 * TCP는 붙어 있는데 브로커가 응답하지 않는 경우를 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorStreamProbe {

    /** 하트비트 간격(기본 3초)·세션 타임아웃(기본 45초)보다 넉넉히 — 일시적 지연을 장애로 보지 않는다 */
    private static final double HEARTBEAT_STALE_SECONDS = 60.0;

    private static final String CONNECTION_COUNT = "connection-count";
    private static final String LAST_HEARTBEAT = "last-heartbeat-seconds-ago";

    private final KafkaListenerEndpointRegistry registry;
    private final BehaviorStreamProperties names;
    private final BehaviorStreamHealth streamHealth;

    /**
     * 신호 TTL(90초)보다 짧게 돌아 한 번 걸러도 만료되지 않게 한다.
     * 앱이 통째로 죽으면 이 스케줄도 멈추므로 신호가 자연히 만료된다 — 컨슈머 정지도 같이 잡힌다.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void probe() {
        if (isBrokerConnected()) {
            streamHealth.markConnected();
        }
    }

    private boolean isBrokerConnected() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            // ⚠️ 해석된 이름으로 비교해야 한다 — 접두어가 붙는데 여기만 상수로 두면 매칭이 영영 실패해
            // 브로커가 멀쩡해도 "연결 없음"이 되고, S-1이 스트림을 안 믿고 계속 DB 폴백으로 돈다 (08 D10)
            if (!names.groups().visitorTracker().equals(container.getGroupId()) || !container.isRunning()) {
                continue;
            }
            for (Map<MetricName, ? extends Metric> metrics : container.metrics().values()) {
                Double connections = value(metrics, CONNECTION_COUNT);
                Double heartbeatAgo = value(metrics, LAST_HEARTBEAT);
                if (connections != null && connections > 0
                        && heartbeatAgo != null && heartbeatAgo < HEARTBEAT_STALE_SECONDS) {
                    return true;
                }
                log.debug("브로커 연결 미확인 — connections={}, lastHeartbeatAgo={}", connections, heartbeatAgo);
            }
        }
        return false;
    }

    private static Double value(Map<MetricName, ? extends Metric> metrics, String name) {
        return metrics.entrySet().stream()
                .filter(e -> name.equals(e.getKey().name()))
                .map(e -> e.getValue().metricValue())
                .filter(Double.class::isInstance)
                .map(Double.class::cast)
                .findFirst()
                .orElse(null);
    }
}
