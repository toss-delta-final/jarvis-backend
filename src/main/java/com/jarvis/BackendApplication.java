package com.jarvis;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        // JVM·MariaDB 세션 모두 Asia/Seoul 고정 — orderNo 날짜 파생·mock 간격 계산 기준 (03 §5)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(BackendApplication.class, args);
    }
}
