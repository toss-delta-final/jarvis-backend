package com.jarvis.member;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountEventLogRepository extends JpaRepository<AccountEventLog, Long> {

    interface BucketCountRow {
        String getBucket();
        Long getCnt();
    }

    interface LastLoginRow {
        Long getMemberId();
        LocalDateTime getLastLogin();
    }

    /** I-8 groupBy=ip — 집계 전용(raw 미반환), 마스킹은 서비스 소관 (04 §10) */
    @Query(value = """
            SELECT l.ip_address AS bucket, COUNT(*) AS cnt
            FROM account_event_logs l
            WHERE (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.ip_address
            ORDER BY cnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<BucketCountRow> countByIp(@Param("eventType") String eventType,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("limit") int limit);

    /** I-8 groupBy=eventType (04 §10) */
    @Query(value = """
            SELECT l.event_type AS bucket, COUNT(*) AS cnt
            FROM account_event_logs l
            WHERE (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.event_type
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<BucketCountRow> countByEventType(@Param("eventType") String eventType,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** I-8 groupBy=hour — 시간대 버킷(무차별 대입 시간 패턴 탐지) (04 §10) */
    @Query(value = """
            SELECT DATE_FORMAT(l.created_at, '%Y-%m-%dT%H:00') AS bucket, COUNT(*) AS cnt
            FROM account_event_logs l
            WHERE (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<BucketCountRow> countByHour(@Param("eventType") String eventType,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /** I-16 마지막 로그인의 단일 출처 = LOGIN_SUCCESS (02 D32) */
    @Query(value = """
            SELECT l.member_id AS memberId, MAX(l.created_at) AS lastLogin
            FROM account_event_logs l
            WHERE l.event_type = 'LOGIN_SUCCESS' AND l.member_id IN (:memberIds)
            GROUP BY l.member_id
            """, nativeQuery = true)
    List<LastLoginRow> findLastLogins(@Param("memberIds") Collection<Long> memberIds);
}
