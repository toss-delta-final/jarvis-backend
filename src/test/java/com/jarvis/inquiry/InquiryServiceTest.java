package com.jarvis.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jarvis.inquiry.dto.InquiryListResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** M-9 내 문의 내역 조회 (04 §5, 02 D23) */
@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock InquiryRepository inquiryRepository;

    @InjectMocks InquiryService inquiryService;

    @Test
    @DisplayName("M-9 — 제목·내용·상태·답변을 담아 페이지로 반환")
    void myInquiriesMapped() {
        Inquiry inquiry = mock(Inquiry.class, withSettings().strictness(Strictness.LENIENT));
        when(inquiry.getId()).thenReturn(3L);
        when(inquiry.getTitle()).thenReturn("배송 문의");
        when(inquiry.getContent()).thenReturn("언제 오나요?");
        when(inquiry.getStatus()).thenReturn(InquiryStatus.DONE);
        when(inquiry.getAnswer()).thenReturn("내일 도착 예정입니다.");
        when(inquiry.getAnsweredAt()).thenReturn(LocalDateTime.of(2026, 7, 17, 10, 0));
        when(inquiry.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 16, 9, 0));
        when(inquiryRepository.findAllByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(inquiry), PageRequest.of(0, 10), 1));

        InquiryListResponse response = inquiryService.myInquiries(MEMBER_ID, 0, 10);

        assertThat(response.content()).hasSize(1);
        InquiryListResponse.Item item = response.content().get(0);
        assertThat(item.inquiryId()).isEqualTo(3L);
        assertThat(item.title()).isEqualTo("배송 문의");
        assertThat(item.status()).isEqualTo("DONE");
        assertThat(item.answer().content()).isEqualTo("내일 도착 예정입니다.");
        assertThat(item.answer().answeredAt()).isNotNull();
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("M-9 — 답변 전 문의는 answer가 명시적 null (Notion 계약)")
    void unansweredInquiryHasNullAnswer() {
        Inquiry inquiry = mock(Inquiry.class, withSettings().strictness(Strictness.LENIENT));
        when(inquiry.getId()).thenReturn(4L);
        when(inquiry.getTitle()).thenReturn("환불 문의");
        when(inquiry.getContent()).thenReturn("환불해주세요.");
        when(inquiry.getStatus()).thenReturn(InquiryStatus.PENDING);
        when(inquiry.getAnswer()).thenReturn(null);
        when(inquiry.getAnsweredAt()).thenReturn(null);
        when(inquiry.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 7, 16, 9, 0));
        when(inquiryRepository.findAllByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(inquiry), PageRequest.of(0, 10), 1));

        InquiryListResponse response = inquiryService.myInquiries(MEMBER_ID, 0, 10);

        assertThat(response.content().get(0).answer()).isNull();
    }
}
