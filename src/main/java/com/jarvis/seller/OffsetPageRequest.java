package com.jarvis.seller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * offset을 limit 배수 그리드로 스냅하지 않는 진짜 row offset용 Pageable.
 * internal 판매자 조회(I-9·I-31)는 page/size가 아니라 limit/offset 어휘를 쓰는데,
 * PageRequest.of(offset / limit, limit)로 근사하면 limit의 배수가 아닌 offset이 조용히
 * 다른 위치를 가리킨다 — 그래서 getOffset()을 직접 구현한다.
 *
 * <p>2026-08-06 I-31 추가 때 SellerProductService의 private record에서 꺼냈다(같은 필요가 둘이 됐다).
 */
record OffsetPageRequest(int start, int limit, Sort sortBy) implements Pageable {

    @Override
    public int getPageNumber() {
        return limit > 0 ? start / limit : 0;
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return start;
    }

    @Override
    public Sort getSort() {
        return sortBy;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(start + limit, limit, sortBy);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageRequest(Math.max(0, start - limit), limit, sortBy) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, limit, sortBy);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageRequest(pageNumber * limit, limit, sortBy);
    }

    @Override
    public boolean hasPrevious() {
        return start > 0;
    }
}
