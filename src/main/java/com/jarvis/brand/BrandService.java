package com.jarvis.brand;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandService {

    private final BrandRepository brandRepository;

    public Brand getBrand(Long id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND));
    }

    /** I-1 brandName 필터 해석 (05 §I-1) — 미존재 이름이면 empty(후보 0건) */
    public Optional<Long> findIdByName(String name) {
        return brandRepository.findFirstByName(name).map(Brand::getId);
    }

    /** 카드 조립용 브랜드명 배치 조회 (04 §2 카드 공통 모양) */
    public Map<Long, String> getNames(Collection<Long> ids) {
        return brandRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName));
    }
}
