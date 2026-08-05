package com.jarvis.product;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 상품 이미지 호스트 (02 D42) — DB에는 버킷 내 경로(key)만 저장하고 앞부분을 여기서 붙인다.
 *
 * <p>이미지가 Cloudflare 뒤로 옮겨질 예정이라, URL을 통째로 저장하면 그 시점에 5만여 행을 UPDATE해야 한다.
 * 호스트를 설정으로 빼두면 도메인이 바뀌어도 DB를 건드리지 않는다.
 */
@ConfigurationProperties(prefix = "app.image")
public record ImageProperties(String baseUrl) {

    /**
     * key 앞에 호스트를 붙여 완성된 URL로 만든다.
     *
     * <p>슬래시 정규화를 여기 한 곳에서만 한다 — base-url이 `/`로 끝나느냐에 따라 `//detail`이 되거나
     * 경로가 붙어버리는 것은 이런 설정에서 가장 흔한 사고다.
     */
    public String toUrl(String imageKey) {
        String host = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String key = imageKey.startsWith("/") ? imageKey.substring(1) : imageKey;
        return host + "/" + key;
    }
}
