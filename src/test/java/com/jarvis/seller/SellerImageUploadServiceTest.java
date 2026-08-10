package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.global.ratelimit.UploadRateLimiter;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.seller.dto.SellerImageUploadUrlRequest;
import com.jarvis.seller.dto.SellerImageUploadUrlResponse;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** S-6 업로드 URL 발급 — 검증 분기와 저장용 URL 생성 */
@ExtendWith(MockitoExtension.class)
class SellerImageUploadServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final String SIGNED =
            "https://bucket.s3.ap-northeast-2.amazonaws.com/products/temp/a3f1.jpg"
                    + "?X-Amz-Signature=sig&X-Amz-Expires=300";

    @Mock S3Presigner presigner;
    @Mock UploadRateLimiter uploadRateLimiter;
    @Mock PresignedPutObjectRequest presigned;

    private SellerImageUploadService service;

    @BeforeEach
    void setUp() {
        S3Properties properties = new S3Properties(
                "bucket", "ap-northeast-2", "products/temp", 5, MAX_BYTES);
        service = new SellerImageUploadService(presigner, properties, uploadRateLimiter);
    }

    private void stubPresign() throws Exception {
        when(presigned.url()).thenReturn(URI.create(SIGNED).toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    private SellerImageUploadUrlRequest request(String filename, String contentType, long size) {
        return new SellerImageUploadUrlRequest(filename, contentType, size);
    }

    @Test
    @DisplayName("정상 요청은 uploadUrl과 쿼리스트링 없는 imageUrl을 함께 준다")
    void issuesBothUrls() throws Exception {
        stubPresign();

        SellerImageUploadUrlResponse response =
                service.issue(MEMBER_ID, request("shirt.jpg", "image/jpeg", 284_913));

        assertThat(response.uploadUrl()).isEqualTo(SIGNED);
        // 저장되는 쪽에 서명이 남으면 만료 시점에 이미지가 조용히 죽는다
        assertThat(response.imageUrl())
                .isEqualTo("https://bucket.s3.ap-northeast-2.amazonaws.com/products/temp/a3f1.jpg")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("선언한 size가 그대로 서명에 들어간다 — 상한이 아니라 일치 조건이라 강제된다")
    void signsDeclaredContentLength() throws Exception {
        stubPresign();

        service.issue(MEMBER_ID, request("shirt.jpg", "image/jpeg", 284_913));

        ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        assertThat(captor.getValue().putObjectRequest().contentLength()).isEqualTo(284_913L);
        assertThat(captor.getValue().putObjectRequest().contentType()).isEqualTo("image/jpeg");
        assertThat(captor.getValue().putObjectRequest().key()).startsWith("products/temp/");
    }

    @Test
    @DisplayName("확장자와 contentType이 어긋나면 거부 — 확장자만 바꿔 올리는 경로를 막는다")
    void rejectsMismatchedContentType() {
        assertThatThrownBy(() -> service.issue(MEMBER_ID, request("shirt.png", "image/jpeg", 100)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TYPE_UNSUPPORTED);

        verify(presigner, never()).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    @DisplayName("화이트리스트 밖 확장자는 거부한다")
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> service.issue(MEMBER_ID, request("a.gif", "image/gif", 100)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("확장자가 없으면 거부한다")
    void rejectsMissingExtension() {
        assertThatThrownBy(() -> service.issue(MEMBER_ID, request("shirt", "image/jpeg", 100)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("상한을 넘으면 형식 오류와 다른 코드로 거부 — FE가 '더 작은 사진으로'를 안내해야 한다")
    void rejectsOversize() {
        assertThatThrownBy(() ->
                service.issue(MEMBER_ID, request("shirt.jpg", "image/jpeg", MAX_BYTES + 1)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("상한과 같은 크기는 통과 — 초과부터 막는다")
    void exactlyAtLimitPasses() throws Exception {
        stubPresign();

        service.issue(MEMBER_ID, request("shirt.jpg", "image/jpeg", MAX_BYTES));

        verify(presigner).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    @DisplayName("확장자 대문자도 같은 확장자로 본다")
    void extensionIsCaseInsensitive() throws Exception {
        stubPresign();

        service.issue(MEMBER_ID, request("SHIRT.JPG", "image/jpeg", 100));

        verify(presigner).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    @DisplayName("발급 제한을 먼저 확인한다 — 검증을 통과하지 못할 요청도 횟수에 든다")
    void checksRateLimitFirst() {
        assertThatThrownBy(() -> service.issue(MEMBER_ID, request("a.gif", "image/gif", 100)))
                .isInstanceOf(BusinessException.class);

        verify(uploadRateLimiter).check(MEMBER_ID);
    }
}
