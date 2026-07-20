package com.jarvis.global.response;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/**
 * envelope 직렬화 (03 D2 — 노션 「📡 API 명세서」가 기준).
 * 성공은 `data`를 **항상** 쓴다(값이 없으면 명시적 null — A-3·C-4·M-6·M-8d 등),
 * 실패는 `data` 없이 `error`만. 클래스 단위 NON_NULL로는 "성공 + data:null"을 표현할 수 없어 직접 쓴다.
 */
public class ApiResponseSerializer extends JsonSerializer<ApiResponse<?>> {

    @Override
    public void serialize(ApiResponse<?> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeBooleanField("success", value.success());
        if (value.success()) {
            gen.writeFieldName("data");
            if (value.data() == null) {
                gen.writeNull();
            } else {
                serializers.defaultSerializeValue(value.data(), gen);
            }
        } else if (value.error() != null) {
            serializers.defaultSerializeField("error", value.error(), gen);
        }
        gen.writeEndObject();
    }
}
