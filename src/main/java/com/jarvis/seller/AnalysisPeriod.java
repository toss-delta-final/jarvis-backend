package com.jarvis.seller;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 내부 분석 API(I-6 등)의 필수 조회 기간 — 노션 계약상 from/to 누락·형식 오류·역전은
 * 모두 400 INVALID_PERIOD로 통일한다(Spring 바인딩 에러 코드와 분리하려고 String으로 받아 파싱).
 */
public record AnalysisPeriod(LocalDate from, LocalDate to) {

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
