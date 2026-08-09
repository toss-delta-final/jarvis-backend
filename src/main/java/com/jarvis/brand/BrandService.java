package com.jarvis.brand;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jarvis.global.cache.RedisCache;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandService {

    /** 07 §2 키 지도 — 전 브랜드 id→이름을 키 하나에. 등록·수정 API가 없어 TTL만으로 충분(시드 갱신만 최대 1시간 지연) */
    private static final String NAMES_KEY = "v1:brand:names";
    private static final Duration NAMES_TTL = Duration.ofHours(1);

    private final BrandRepository brandRepository;
    private final RedisCache cache;

    public Brand getBrand(Long id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.BRAND_NOT_FOUND));
    }

    /** I-1 brandName 필터 해석 (05 §I-1) — 미존재 이름이면 empty(후보 0건) */
    public Optional<Long> findIdByName(String name) {
        return brandRepository.findByName(name).map(Brand::getId);
    }

    /**
     * 카드 조립용 브랜드명 배치 조회 (04 §2 카드 공통 모양).
     *
     * <p>수십 건뿐이라 전량을 키 하나로 캐시하고 요청 id만 걸러 돌려준다(07 §3-1 —
     * 2026-08-10 부하 실측 후). id 단위 키로 쪼개면 MGET·부분 미스 처리가 붙는데,
     * 전체가 맵 하나 크기라 그 비용을 치를 이유가 없다.
     */
    public Map<Long, String> getNames(Collection<Long> ids) {
        Map<Long, String> all = cache.get(NAMES_KEY, NAMES_TTL,
                new TypeReference<Map<Long, String>>() { }, this::loadAllNames);
        return ids.stream().distinct().filter(all::containsKey)
                .collect(Collectors.toMap(Function.identity(), all::get));
    }

    private Map<Long, String> loadAllNames() {
        return brandRepository.findAll().stream()
                .collect(Collectors.toMap(Brand::getId, Brand::getName));
    }
}
