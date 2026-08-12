package com.jarvis.global.config;

import com.jarvis.global.event.BehaviorEventMessage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 행동 이벤트 스트림 (08 D1) — 토픽 하나에 소비자 그룹 둘(persister·visitor-tracker)이 붙는다.
 *
 * <p>토픽을 여기서 선언하는 이유: 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값을 따라가
 * 컨슈머 병렬도가 조용히 달라진다. 파티션 수는 그룹의 최대 병렬도라 설계값(3)으로 고정한다.
 *
 * <p><b>이름은 여기 없다</b> — 토픽·그룹 이름은 환경별 접두어가 붙으므로 {@code app.kafka}에서
 * 온다 (08 D10, {@link BehaviorStreamProperties}).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(BehaviorStreamProperties.class)
public class KafkaConfig {

    /**
     * 리스너 애노테이션이 쓰는 <b>프로퍼티 키</b>. 이름 문자열이 아니라 키를 공유한다 —
     * 애노테이션에 이름을 따로 적으면 {@code app.kafka}가 정본이라는 규칙이 조용히 깨진다.
     */
    public static final String TOPIC = "${app.kafka.topic}";
    public static final String DLT = "${app.kafka.dlt}";
    public static final String PERSISTER_GROUP = "${app.kafka.groups.persister}";
    public static final String VISITOR_TRACKER_GROUP = "${app.kafka.groups.visitor-tracker}";
    public static final String DLT_MONITOR_GROUP = "${app.kafka.groups.dlt-monitor}";

    private static final int PARTITIONS = 3;

    /**
     * 기동 시 <b>해석된</b> 이름을 남긴다. 접두어는 환경변수에서 오므로 코드만 봐서는 이 인스턴스가
     * 어느 토픽·그룹에 붙었는지 알 수 없고, 잘못 붙어도 에러가 나지 않는다 — 유일한 증거가 이 줄이다.
     */
    @Bean
    public ApplicationRunner behaviorStreamNamesLogger(BehaviorStreamProperties names) {
        return args -> log.info("행동 이벤트 스트림 (08 D10) — topic={}, dlt={}, groups=[{}, {}, {}]",
                names.topic(), names.dlt(), names.groups().persister(),
                names.groups().visitorTracker(), names.groups().dltMonitor());
    }

    @Bean
    public NewTopic behaviorEventsTopic(BehaviorStreamProperties names) {
        // 복제본 1 — 단일 브로커(08 D6). 운영 전환 시 3대 + RF3.
        return TopicBuilder.name(names.topic())
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    /**
     * DLT도 파티션 수를 맞춘다 — 기본 recoverer가 원본과 <b>같은 파티션 번호</b>로 보내므로,
     * DLT가 1파티션이면 2번 파티션에서 실패한 레코드를 옮기다 그것마저 실패한다.
     */
    @Bean
    public NewTopic behaviorEventsDltTopic(BehaviorStreamProperties names) {
        return TopicBuilder.name(names.dlt())
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    /**
     * DLT 감시 전용 컨슈머 — <b>값을 바이트로 읽는다.</b>
     *
     * <p>DLT에 들어온 레코드 중에는 애초에 역직렬화가 안 돼서 밀려난 것도 있다. 본 컨슈머와 같은
     * JSON 역직렬화를 쓰면 그것들이 여기서도 실패해 <b>DLT의 DLT</b>로 밀려난다 — 감시가 감시를 낳는다.
     * 바이트로 받으면 무엇이 들어와도 읽히므로 그 고리가 끊긴다.
     */
    @Bean
    public ConsumerFactory<String, byte[]> dltConsumerFactory(KafkaProperties properties) {
        Map<String, Object> props = new HashMap<>(properties.buildConsumerProperties(null));
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        // JSON 역직렬화용 설정을 남겨두면 ByteArrayDeserializer가 못 알아듣는 키가 섞인다
        props.keySet().removeIf(key -> key.startsWith("spring.deserializer") || key.startsWith("spring.json"));
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> dltListenerContainerFactory(
            ConsumerFactory<String, byte[]> dltConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dltConsumerFactory);
        // 재시도도 DLT 재발행도 하지 않는다 — 여기서 실패하면 로그만 남기고 넘어간다
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }

    /**
     * 이벤트 발행용 기본 템플릿.
     *
     * <p>직접 선언하는 이유 — 아래 {@code dltRawTemplate}처럼 {@code KafkaTemplate} 빈이 하나라도 있으면
     * Boot의 자동 구성이 물러난다. 둘 다 명시해야 어느 쪽이 어떤 직렬화를 쓰는지도 코드에 드러난다.
     */
    @Bean
    public KafkaTemplate<String, BehaviorEventMessage> kafkaTemplate(KafkaProperties properties) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null)));
    }

    /**
     * 컨슈머 실패 처리 — 1초 간격 3회 재시도 후 DLT로 보낸다.
     *
     * <p>재시도가 짧은 이유는 실패 대부분이 DB 순간 장애라 몇 초면 회복되기 때문이고,
     * 무한 재시도를 하지 않는 이유는 역직렬화 불가 같은 <b>독성 레코드</b> 하나가 파티션을
     * 영구히 막기 때문이다. DLT는 폐기가 아니다 — 보존 7일 안에 되감아 재처리할 수 있다.
     */
    @Bean
    public CommonErrorHandler behaviorEventErrorHandler(
            KafkaTemplate<String, BehaviorEventMessage> kafkaTemplate,
            KafkaTemplate<String, byte[]> dltRawTemplate,
            BehaviorStreamProperties names) {
        // 값 타입에 맞는 직렬화를 쓴다 — 이게 없으면 DLT를 되감아 재처리할 수 없다.
        // 역직렬화 실패로 밀려난 레코드의 값은 **원본 바이트**인데, JSON 직렬화로 실으면
        // base64 문자열로 감싸져 나가 원본과 다른 것이 된다(2026-08-10 실측에서 확인).
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltRawTemplate);
        templates.put(Object.class, kafkaTemplate);
        // ⚠️ 목적지 이름·파티션을 **기본값에 맡기지 않는다**(2026-08-10). 종전엔 "기본 규칙은 .DLT"로
        // 알고 그렇게 선언했는데 recoverer가 실제로 쓴 이름은 -dlt였다 — 선언한 토픽은 아무도 쓰지 않고
        // 진짜 DLT는 브로커가 1파티션으로 자동 생성해, 2번 파티션의 실패 레코드를 옮기다 그것마저 실패했다.
        // 접두어가 붙는 지금은 더더욱 — app.kafka.dlt가 정본이고 여기가 그것을 그대로 쓴다 (08 D10).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(names.dlt(), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }

    /** DLT 발행 전용 — 원본 바이트를 그대로 싣기 위한 템플릿 (위 주석 참조) */
    @Bean
    public KafkaTemplate<String, byte[]> dltRawTemplate(KafkaProperties properties) {
        Map<String, Object> props = new HashMap<>(properties.buildProducerProperties(null));
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.keySet().removeIf(key -> key.startsWith("spring.json"));
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
