package com.jarvis.global.config;

import com.jarvis.order.MockProperties;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 분산 락 (03 D-분산5) — Redis 기반 ShedLock으로 틱당 1대만 실행.
 * 정합성 최종 방어선은 각 잡의 조건부 UPDATE (01 §6) — 락은 중복 부수효과 차단용 2층.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT50S")
@EnableConfigurationProperties(MockProperties.class)
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "jarvis");
    }
}
