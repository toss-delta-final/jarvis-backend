package com.jarvis.seller;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ImageProperties;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

/**
 * 판매자 업로드 이미지를 상품 경로 규약으로 옮긴다 (04 §7 S-6, 2026-08-10 개정).
 *
 * <p>S-6은 발급 시점에 {@code productId}를 모르므로 {@code products/temp/{uuid}.{ext}}로 받고,
 * 등록·수정으로 id가 생긴 뒤 크롤링 6만여 건이 이미 쓰는 규약 {@code products/{productId}/main.{ext}}로
 * 복사한다. 판매자 등록분만 이 규약 밖에 있던 것을 맞추는 것이다.
 *
 * <p><b>원본은 지우지 않는다</b> — {@code s3:DeleteObject}가 없고 필요도 없다. temp prefix에 걸린
 * 1일 만료 라이프사이클이 치운다. "옮기기"가 성립하는 근거는 삭제 권한이 아니라 그 규칙이다.
 *
 * <p>S3 지식을 여기 가둔다 — 판매자 상품 로직에 SDK가 섞이면 그 서비스의 테스트가 통째로 무거워진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductImageStorage {

    private final S3Client s3Client;
    private final S3Properties properties;
    private final ImageProperties imageProperties;

    /**
     * S-6이 발급한 임시 주소인지.
     *
     * <p>이 판정이 없으면 가격만 고치는 I-11 호출도 매번 S3를 때린다. 최종 주소·플레이스홀더·
     * 외부 주소(크롤링 상품의 11번가 CDN)는 그대로 둔다.
     */
    public boolean isTemporary(String imageUrl) {
        return imageUrl != null && imageUrl.contains("/" + properties.keyPrefix() + "/");
    }

    /**
     * temp 객체를 상품 경로로 복사하고 저장할 최종 URL을 만든다.
     *
     * <p>{@code ?v=}를 붙이는 이유 — 이미지를 교체해도 키가 같아 URL이 한 글자도 바뀌지 않는다.
     * 그러면 브라우저와 AI 서버가 옛 이미지를 계속 보게 되는데 오류가 나지 않아 아무도 모른다.
     */
    public String moveToProduct(String temporaryImageUrl, Long productId) {
        String sourceKey = keyOf(temporaryImageUrl);
        String destinationKey = "products/%d/main.%s".formatted(productId, extensionOf(sourceKey));
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(properties.bucket())
                    .sourceKey(sourceKey)
                    .destinationBucket(properties.bucket())
                    .destinationKey(destinationKey)
                    .build());
        } catch (RuntimeException e) {
            // 임시 주소를 그대로 저장하는 폴백은 두지 않는다 — 하루 뒤 이미지만 조용히 죽는다.
            // 호출부 트랜잭션을 롤백시키고, 임시 파일은 24시간 살아 있으므로 재업로드 없이 재시도된다
            log.error("상품 이미지 복사 실패 productId={} source={}", productId, sourceKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return imageProperties.toUrl(destinationKey) + "?v=" + Instant.now().getEpochSecond();
    }

    /** 호스트를 떼고 버킷 내 경로만 남긴다. 쿼리가 붙어 오면 잘라낸다 — 확장자에 섞이면 키가 망가진다 */
    private String keyOf(String imageUrl) {
        String withoutQuery = imageUrl.split("\\?", 2)[0];
        int prefix = withoutQuery.indexOf("/" + properties.keyPrefix() + "/");
        return withoutQuery.substring(prefix + 1);
    }

    private String extensionOf(String key) {
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
