package com.jarvis.seller;

import com.jarvis.global.ratelimit.UploadRateLimiter;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.seller.dto.SellerImageUploadUrlRequest;
import com.jarvis.seller.dto.SellerImageUploadUrlResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S-6 상품 이미지 업로드 URL 발급 — 서버는 파일을 받지 않고 5분짜리 서명만 내준다.
 *
 * <p>업로드 성공 여부를 서버는 모른다. PUT은 브라우저와 S3 사이에서 일어나므로 재시도는 FE 몫이고
 * 발급 기록도 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SellerImageUploadService {

    /** 확장자 → 허용 Content-Type. 둘이 어긋나면 거부한다 — 확장자만 바꿔 올리는 경로를 막는다. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private final S3Presigner presigner;
    private final S3Properties properties;
    private final UploadRateLimiter uploadRateLimiter;

    public SellerImageUploadUrlResponse issue(Long memberId, SellerImageUploadUrlRequest request) {
        uploadRateLimiter.check(memberId);

        String extension = extensionOf(request.filename());
        String contentType = ALLOWED_TYPES.get(extension);
        // 타입을 서명에 박기 때문에, 여기서 통과시킨 값과 다른 타입으로는 PUT 자체가 되지 않는다
        if (contentType == null || !contentType.equals(request.contentType())) {
            throw new BusinessException(ErrorCode.IMAGE_TYPE_UNSUPPORTED);
        }
        if (request.size() > properties.maxUploadBytes()) {
            throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
        }

        String key = "%s/%s.%s".formatted(properties.keyPrefix(), UUID.randomUUID(), extension);
        String uploadUrl = presign(key, contentType, request.size());
        return new SellerImageUploadUrlResponse(uploadUrl, canonical(uploadUrl));
    }

    private String presign(String key, String contentType, long size) {
        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                // 상한이 아니라 일치 조건이다 — 선언한 크기와 실제 전송량이 다르면 S3가 거부한다
                .contentLength(size)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(properties.presignExpiryMinutes()))
                .putObjectRequest(putObject)
                .build();
        return presigner.presignPutObject(presignRequest).url().toString();
    }

    /**
     * 서명 파라미터를 떼어 등록 요청에 넘길 주소를 만든다.
     *
     * <p>이 주소는 <b>24시간짜리 임시 주소다</b>(2026-08-10) — {@code products/temp/}에 1일 만료
     * 라이프사이클이 걸려 있다. DB에 남는 값은 등록·수정 때 {@link ProductImageStorage}가 만드는
     * 최종 주소다.
     *
     * <p>버킷·리전으로 URL을 다시 조립하지 않고 <b>발급된 URL에서 잘라내는</b> 이유는, 그래야
     * "서명한 자리"와 "저장하는 자리"가 어긋날 수 없기 때문이다. 엔드포인트 형식(virtual-host/path)이
     * 바뀌어도 둘이 같이 따라간다.
     */
    private String canonical(String uploadUrl) {
        int query = uploadUrl.indexOf('?');
        return query < 0 ? uploadUrl : uploadUrl.substring(0, query);
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new BusinessException(ErrorCode.IMAGE_TYPE_UNSUPPORTED);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
