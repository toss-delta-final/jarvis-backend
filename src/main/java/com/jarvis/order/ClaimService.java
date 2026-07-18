package com.jarvis.order;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.order.dto.ClaimListResponse;
import com.jarvis.order.dto.ClaimRequest;
import com.jarvis.order.dto.ClaimResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O-5·O-6 (04 §4) — 클레임은 아이템 단위·전 수량 (01 D2), 배송 전 취소·배송 후 반품 2종 (01 D11).
 * 신청 시 claim 생성과 아이템 전이는 한 트랜잭션, 전이는 조건부 UPDATE로 경합 방어 (01 §5).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusChanger statusChanger;

    /** O-5 — 01 §3 매트릭스 위반 400, 활성 클레임 존재 409 */
    @Transactional
    public ClaimResponse request(Long memberId, Long orderItemId, ClaimRequest request) {
        OrderItem item = orderItemRepository.findById(orderItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        orderRepository.findById(item.getOrderId())
                .filter(order -> order.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        if (claimRepository.existsByOrderItemIdAndStatus(orderItemId, ClaimStatus.REQUESTED)) {
            throw new BusinessException(ErrorCode.CLAIM_ALREADY_REQUESTED);
        }
        boolean allowed = request.type() == ClaimType.CANCEL
                ? item.getStatus().canCancel()
                : item.getStatus().canReturn();
        if (!allowed) {
            throw new BusinessException(ErrorCode.CLAIM_NOT_ALLOWED);
        }
        OrderItemStatus from = request.type() == ClaimType.CANCEL
                ? OrderItemStatus.ORDERED : OrderItemStatus.DELIVERED;
        Claim claim = Claim.request(orderItemId, request.type(), request.reason());
        if (!statusChanger.requestClaim(orderItemId, from, claim.requestedItemStatus(), LocalDateTime.now())) {
            // 조건부 UPDATE 0건 = 동시 요청/스케줄러 전이에 밀림 (01 §5 경합 방어)
            throw new BusinessException(ErrorCode.CLAIM_NOT_ALLOWED);
        }
        return ClaimResponse.from(claimRepository.save(claim));
    }

    /** O-6 — 내 취소·반품 내역, 행에 orderNo 포함 (07-17 FE) */
    public ClaimListResponse myClaims(Long memberId, int page, int size) {
        return ClaimListResponse.from(claimRepository.findMyClaims(memberId, PageRequest.of(page, size)));
    }
}
