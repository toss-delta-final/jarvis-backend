package com.jarvis.seller.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * I-8 자사 코호트 계정 이벤트 집계 (노션 I-8 — 2026-08-06 전역 → 브랜드 스코프 전환).
 *
 * <p>전역판({@link AccountEventAggregateResponse})과 <b>같은 DTO를 쓰지 않는다.</b> 전역 경로는 삭제가
 * 아니라 존치라(무차별 대입은 브랜드 귀속이 불가능한 플랫폼 보안 신호라 admin 부활 시 그대로 쓴다)
 * 두 계약이 함께 살아 있는데, 행 스키마가 서로 다르다. 한 DTO에 합치면 어느 쪽에서도 안 쓰는
 * nullable 필드가 생기고, 그게 곧 "값이 없다"와 "이 스코프엔 해당 없다"를 구분 못 하는 응답이 된다.
 *
 * <p>{@code scope}는 구버전(전역) 응답과의 오독을 막는 에코다 — 같은 이름의 API가 다른 모집단을
 * 세고 있다는 사실이 응답 자체에 드러나야 한다.
 */
public record BrandAccountEventAggregateResponse(Long brandId, String groupBy, String scope,
                                                 String eventType, LocalDate from, LocalDate to,
                                                 List<?> rows) {

    private static final String SCOPE_BRAND = "brand";

    public static BrandAccountEventAggregateResponse of(Long brandId, String groupBy,
                                                        String eventType, LocalDate from,
                                                        LocalDate to, List<?> rows) {
        return new BrandAccountEventAggregateResponse(brandId, groupBy, SCOPE_BRAND, eventType,
                from, to, rows);
    }

    /** groupBy=eventType|hour — 노션에 예시가 없어 전역판과 같은 {key, count}를 유지한다 */
    public record Bucket(String key, long count) {
    }

    /**
     * groupBy=ip. 전역판 {@code IpRow}에서 세 필드가 빠지고 하나가 들어왔다(노션 I-8 2026-08-06):
     * <ul>
     *   <li>{@code nullMemberRatio} 제거 — 코호트가 member 조인이라 <b>항상 0</b>이다
     *   <li>{@code isSuspicious}·{@code failCount} 제거 — 브랜드 스코프에선 LOGIN_FAIL이 급감해 임계에
     *       닿지 못하므로 <b>항상 false</b>다. "isSuspicious는 코드 판정 — 번복 금지" 프롬프트 규칙과
     *       결합하면 LLM이 "어뷰징 없음"을 확신으로 오보하는 가장 위험한 필드라, 재정의가 아니라 제거했다
     *   <li>{@code suspiciousMemberCount} 신설 — 그 IP를 쓴 코호트 회원 중 I-14 어뷰징 기준
     *       ({@code cancelRatio > 0.5} 또는 {@code maxOrdersPerHour > 10})에 걸리는 <b>회원 수</b>
     * </ul>
     *
     * <p>회원 키가 아니라 개수만 내려보낸다 — LLM에게 I-8×I-14 교차를 시키면 추측으로 특정 고객을
     * 허위 지목할 수 있다(식별정보 비공개 규약).
     */
    public record IpRow(String ipMasked, long distinctMembers, long suspiciousMemberCount,
                        long eventCount, OffsetDateTime firstSeen, OffsetDateTime lastSeen) {
    }
}
