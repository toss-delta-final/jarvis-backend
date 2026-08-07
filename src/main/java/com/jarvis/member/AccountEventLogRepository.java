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

    interface IpAggRow {
        String getIp();
        Long getFailCount();
        Long getDistinctMembers();
        Long getNullMemberCnt();
        Long getTotalCnt();
        LocalDateTime getFirstSeen();
        LocalDateTime getLastSeen();
    }

    /**
     * I-8 groupBy=ip — IP별 실패·회원 분포 집계(무차별 대입 신호 재료, 노션 I-8).
     * 집계 전용(raw 미반환), 마스킹·비율·판정은 서비스 소관 (04 §10).
     */
    @Query(value = """
            SELECT l.ip_address AS ip,
                   SUM(CASE WHEN l.event_type = 'LOGIN_FAIL' THEN 1 ELSE 0 END) AS failCount,
                   COUNT(DISTINCT l.member_id) AS distinctMembers,
                   SUM(CASE WHEN l.member_id IS NULL THEN 1 ELSE 0 END) AS nullMemberCnt,
                   COUNT(*) AS totalCnt,
                   MIN(l.created_at) AS firstSeen,
                   MAX(l.created_at) AS lastSeen
            FROM account_event_logs l
            WHERE (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.ip_address
            ORDER BY failCount DESC, totalCnt DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<IpAggRow> aggregateByIp(@Param("eventType") String eventType,
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

    interface CohortIpAggRow {
        String getIp();
        Long getDistinctMembers();
        Long getSuspiciousMembers();
        Long getEventCount();
        LocalDateTime getFirstSeen();
        LocalDateTime getLastSeen();
    }

    /**
     * I-8 자사 코호트 groupBy=ip (노션 I-8 2026-08-06 브랜드 스코프 전환).
     *
     * <p>전역판과 달리 {@code member_id IN (코호트)}로 좁힌다 — member 조인이라 <b>게스트 행은 빠진다</b>.
     * 그래서 전역판의 {@code nullMemberRatio}(memberId NULL 비율)는 여기선 항상 0이 되어 제거했고,
     * {@code failCount}·{@code isSuspicious}(LOGIN_FAIL 버스트)도 브랜드 스코프에선 임계에 닿지 못해
     * 항상 false가 되므로 제거했다.
     *
     * <p>{@code suspiciousMembers}는 그 IP를 쓴 코호트 회원 중 I-14 어뷰징 기준에 걸리는 <b>회원 수</b>다.
     * 회원 키를 주지 않는 이유는 노션 I-8에 있다 — LLM에게 I-8×I-14 교차를 시키면 추측으로 특정 고객을
     * 허위 지목할 수 있다. 정렬은 어뷰징 회원이 많은 IP부터(그 다음 이벤트 수) — 판매자가 가장 먼저
     * 봐야 할 행이 위로 온다.
     */
    @Query(value = """
            SELECT l.ip_address AS ip,
                   COUNT(DISTINCT l.member_id) AS distinctMembers,
                   COUNT(DISTINCT CASE WHEN l.member_id IN (:suspiciousMemberIds)
                                       THEN l.member_id END) AS suspiciousMembers,
                   COUNT(*) AS eventCount,
                   MIN(l.created_at) AS firstSeen,
                   MAX(l.created_at) AS lastSeen
            FROM account_event_logs l
            WHERE l.member_id IN (:memberIds)
              AND (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.ip_address
            ORDER BY suspiciousMembers DESC, eventCount DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CohortIpAggRow> aggregateByIpForCohort(@Param("memberIds") Collection<Long> memberIds,
                                                @Param("suspiciousMemberIds") Collection<Long> suspiciousMemberIds,
                                                @Param("eventType") String eventType,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("limit") int limit);

    /** I-8 자사 코호트 groupBy=eventType */
    @Query(value = """
            SELECT l.event_type AS bucket, COUNT(*) AS cnt
            FROM account_event_logs l
            WHERE l.member_id IN (:memberIds)
              AND (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY l.event_type
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<BucketCountRow> countByEventTypeForCohort(@Param("memberIds") Collection<Long> memberIds,
                                                   @Param("eventType") String eventType,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /** I-8 자사 코호트 groupBy=hour */
    @Query(value = """
            SELECT DATE_FORMAT(l.created_at, '%Y-%m-%dT%H:00') AS bucket, COUNT(*) AS cnt
            FROM account_event_logs l
            WHERE l.member_id IN (:memberIds)
              AND (:eventType IS NULL OR l.event_type = :eventType)
              AND l.created_at >= :from AND l.created_at < :to
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<BucketCountRow> countByHourForCohort(@Param("memberIds") Collection<Long> memberIds,
                                              @Param("eventType") String eventType,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    // I-16 findLastLogins는 2026-08-06에 삭제했다 — lastLoginAt 제거로 이 조인 자체가 불필요해졌다.
    // 마지막 로그인 시각은 계정 보안 정보라 판매자에게 회원 단위로 줄 이유가 없다(노션 I-16).
}
