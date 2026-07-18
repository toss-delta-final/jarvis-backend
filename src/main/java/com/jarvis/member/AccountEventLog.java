package com.jarvis.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 계정 보안 이벤트 append-only 로그 (02 D32 — FK 미설정).
 * 적재 지점은 AuthService의 성공/실패 지점 (03 §3-1). "마지막 로그인"의 단일 출처 = LOGIN_SUCCESS.
 */
@Entity
@Table(name = "account_event_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AccountEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId; // 없는 계정 로그인 시도는 NULL + IP (무차별 대입 탐지 재료)

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false,
            columnDefinition = "ENUM('SIGNUP','LOGIN_SUCCESS','LOGIN_FAIL','LOGOUT','WITHDRAW')")
    private AccountEventType eventType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static AccountEventLog of(Long memberId, AccountEventType eventType, String ipAddress) {
        AccountEventLog log = new AccountEventLog();
        log.memberId = memberId;
        log.eventType = eventType;
        log.ipAddress = ipAddress;
        return log;
    }
}
