package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** S-4 (04 §7) — brandId는 클라이언트/LLM 주장이 아니라 JWT 검증 후 DB에서 도출 */
@ExtendWith(MockitoExtension.class)
class SellerBrandResolverTest {

    @Mock BrandRepository brandRepository;

    @InjectMocks SellerBrandResolver resolver;

    @Test
    @DisplayName("S-4 — 판매자 memberId로 자기 브랜드 도출 (DB가 원천)")
    void resolve() {
        Brand brand = mock(Brand.class);
        when(brandRepository.findFirstBySellerId(7L)).thenReturn(Optional.of(brand));

        assertThat(resolver.resolve(7L)).isSameAs(brand);
    }

    @Test
    @DisplayName("S-4 — 연결된 브랜드 없으면 404 SELLER_BRAND_NOT_FOUND")
    void resolveWithoutBrand() {
        when(brandRepository.findFirstBySellerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SELLER_BRAND_NOT_FOUND);
    }
}
