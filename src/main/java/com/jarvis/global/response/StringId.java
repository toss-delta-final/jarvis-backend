package com.jarvis.global.response;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * BIGINT id를 JSON 문자열로 내보낸다 — JS {@code Number.MAX_SAFE_INTEGER}(9,007,199,254,740,991)를
 * 넘는 id가 FE에서 정밀도 손실로 뭉개지기 때문(2026-08-06 FE 보고, 노션 📡 API 명세서 개정).
 *
 * <p><b>FE가 보는 응답에만</b> 붙인다. {@code /internal}(LLM팀 FastAPI)은 Python이 받아 정밀도
 * 문제가 없으므로 숫자를 유지한다 — 예외는 공개 C-2와 내부 I-2가 같은 CartService를 지나는
 * {@code CartOptionDetail} 하나뿐이다.
 *
 * <p>요청 DTO에는 붙이지 않는다. Jackson이 {@code "123"}과 {@code 123}을 모두 {@code Long}으로
 * 파싱하므로 FE가 문자열을 되보내도 그대로 받는다.
 *
 * <p>id가 아닌 집계 수치({@code reviewCount}·{@code sales} 등)는 대상이 아니다 — 전역 Long→String
 * 직렬화를 쓰지 않고 필드마다 명시하는 이유다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@JacksonAnnotationsInside // 없으면 Jackson이 메타 애노테이션을 들여다보지 않아 조용히 숫자로 나간다
@JsonSerialize(using = ToStringSerializer.class)
public @interface StringId {
}
