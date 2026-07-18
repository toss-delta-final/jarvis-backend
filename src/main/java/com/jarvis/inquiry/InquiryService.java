package com.jarvis.inquiry;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.inquiry.dto.InquiryListResponse;
import com.jarvis.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** M-9 조회 + I-5 접수 (04 §5, 05 §I-5) — 접수는 internal 콜백이 유일한 경로(문의 단일 채널), 답변은 고도화 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InquiryService {

    private final InquiryRepository inquiryRepository;
    private final MemberRepository memberRepository;

    public InquiryListResponse myInquiries(Long memberId, int page, int size) {
        return InquiryListResponse.from(
                inquiryRepository.findAllByMemberIdOrderByIdDesc(memberId, PageRequest.of(page, size)));
    }

    /** I-5 — 게스트(userId null)는 403(문의는 로그인 필요, 기능 정의 9번). userId는 티켓 메아리라 존재 검증 */
    @Transactional
    public Long create(Long memberId, String title, String content) {
        if (memberId == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return inquiryRepository.save(Inquiry.receive(memberId, title, content)).getId();
    }
}
