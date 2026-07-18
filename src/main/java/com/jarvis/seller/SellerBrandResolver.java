package com.jarvis.seller;

import com.jarvis.brand.Brand;
import com.jarvis.brand.BrandRepository;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 판매자 → 자기 브랜드 도출 (04 §7) — brandId는 클라이언트/LLM 주장이 아니라
 * JWT 검증 후 DB에서 도출한다 (S-4 티켓 claim·S-1~S-5 공통).
 */
@Component
@RequiredArgsConstructor
public class SellerBrandResolver {

    private final BrandRepository brandRepository;

    public Brand resolve(Long memberId) {
        return brandRepository.findFirstBySellerId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SELLER_BRAND_NOT_FOUND));
    }
}
