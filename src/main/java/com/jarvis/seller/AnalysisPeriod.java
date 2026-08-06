package com.jarvis.seller;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 내부 분석 API(I-6 등)의 필수 조회 기간 — 노션 계약상 from/to 누락·형식 오류·역전은
 * 모두 400 INVALID_PERIOD로 통일한다(Spring 바인딩 에러 코드와 분리하려고 String으로 받아 파싱).
 * 조회 API(I-29)는 기간이 선택이라 {@link #optional}을 쓴다 — 04 에러 카탈로그의
 * "기간 필수는 분석 API 한정"(2026-08-06 정리).
 */
public record AnalysisPeriod(LocalDate from, LocalDate to) {

    /**
     * 선택 기간 — 누락은 오류가 아니고 형식 오류·역전만 INVALID_PERIOD다.
     * 한쪽만 준 경우는 그 방향으로만 열린 구간으로 받는다(반대쪽은 null = 무제한).
     */
    public static AnalysisPeriod optional(String from, String to) {
        LocalDate parsedFrom = parseOrNull(from);
        LocalDate parsedTo = parseOrNull(to);
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        return new AnalysisPeriod(parsedFrom, parsedTo);
    }

    private static LocalDate parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
    }

    public static AnalysisPeriod of(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        LocalDate parsedFrom;
        LocalDate parsedTo;
        try {
            parsedFrom = LocalDate.parse(from);
            parsedTo = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        if (parsedFrom.isAfter(parsedTo)) {
            throw new BusinessException(ErrorCode.INVALID_PERIOD);
        }
        return new AnalysisPeriod(parsedFrom, parsedTo);
    }
}
