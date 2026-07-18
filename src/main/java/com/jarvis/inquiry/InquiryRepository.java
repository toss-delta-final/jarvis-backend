package com.jarvis.inquiry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** M-9 — 최신 접수 순 */
    Page<Inquiry> findAllByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);
}
