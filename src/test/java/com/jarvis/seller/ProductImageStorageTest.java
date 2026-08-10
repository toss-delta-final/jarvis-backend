package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ImageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;

/** S-6 임시 이미지를 상품 경로 규약으로 옮기기 (04 §7 S-6, 2026-08-10) */
@ExtendWith(MockitoExtension.class)
class ProductImageStorageTest {

    private static final String HOST = "https://bucket.s3.ap-northeast-2.amazonaws.com";
    private static final String TEMP = HOST + "/products/temp/a3f1.jpg";

    @Mock S3Client s3Client;

    private ProductImageStorage storage;

    @BeforeEach
    void setUp() {
        S3Properties properties =
                new S3Properties("bucket", "ap-northeast-2", "products/temp", 5, 2_097_152);
        storage = new ProductImageStorage(s3Client, properties, new ImageProperties(HOST));
    }

    @Test
    @DisplayName("S-6이 준 임시 주소만 옮김 대상으로 본다")
    void detectsTemporaryUrl() {
        assertThat(storage.isTemporary(TEMP)).isTrue();
        // 이미 옮겨진 주소를 다시 복사하면 가격만 고치는 I-11 호출이 매번 S3를 때린다
        assertThat(storage.isTemporary(HOST + "/products/205/main.jpg?v=1")).isFalse();
        assertThat(storage.isTemporary("/images/placeholder.webp")).isFalse();
        // 크롤링 상품은 11번가 CDN을 가리킨다 — 우리 버킷 밖이라 옮길 수 없다
        assertThat(storage.isTemporary("https://cdn.011st.com/11src/product/1/B.jpg")).isFalse();
        assertThat(storage.isTemporary(null)).isFalse();
    }

    @Test
    @DisplayName("상품 경로로 복사하고 캐시 무효화 쿼리를 붙인 주소를 돌려준다")
    void copiesToProductKey() {
        String moved = storage.moveToProduct(TEMP, 205L);

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        assertThat(captor.getValue().sourceBucket()).isEqualTo("bucket");
        assertThat(captor.getValue().sourceKey()).isEqualTo("products/temp/a3f1.jpg");
        // 크롤링 6만여 건이 이미 쓰는 규약 — 판매자 등록분만 벗어나 있었다
        assertThat(captor.getValue().destinationKey()).isEqualTo("products/205/main.jpg");

        // 키가 같아 URL이 안 바뀌면 교체해도 옛 이미지가 계속 보인다
        assertThat(moved).startsWith(HOST + "/products/205/main.jpg?v=");
    }

    @Test
    @DisplayName("확장자를 유지한다 — webp를 jpg로 부르면 그 주소에 파일이 없다")
    void keepsExtension() {
        storage.moveToProduct(HOST + "/products/temp/b2.webp", 7L);

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        assertThat(captor.getValue().destinationKey()).isEqualTo("products/7/main.webp");
    }

    @Test
    @DisplayName("쿼리가 붙어 와도 키가 망가지지 않는다")
    void stripsQueryBeforeBuildingKey() {
        storage.moveToProduct(TEMP + "?X-Amz-Signature=sig", 9L);

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        assertThat(captor.getValue().sourceKey()).isEqualTo("products/temp/a3f1.jpg");
        assertThat(captor.getValue().destinationKey()).isEqualTo("products/9/main.jpg");
    }

    @Test
    @DisplayName("복사에 실패하면 예외를 올려 등록·수정을 롤백시킨다")
    void failsLoudlyWhenCopyFails() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(SdkClientException.create("no credentials"));

        // 임시 주소를 그대로 저장해두면 하루 뒤 이미지만 조용히 죽는다
        assertThatThrownBy(() -> storage.moveToProduct(TEMP, 205L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
