package com.jarvis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jarvis.product.ProductStockService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 상태 전이 로그의 해상도 규칙 (01 §6.5 규칙 4, 2026-08-06 개정) —
 * 아이템 상태 전이는 아이템마다 1행(orderItemId 필수), 주문 상태 전이만 null.
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusChangerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ClaimRepository claimRepository;
    @Mock private OrderStatusLogRepository logRepository;
    @Mock private ProductStockService productStockService;

    @InjectMocks private OrderStatusChanger changer;

    private static OrderItem item(long id, long orderId) {
        OrderItem i = mock(OrderItem.class);
        lenient().when(i.getId()).thenReturn(id);
        lenient().when(i.getOrderId()).thenReturn(orderId);
        return i;
    }

    /** 재고 복원 대상 정보까지 갖춘 아이템 — 복원 키는 optionName 스냅샷이 아니라 optionId다 (02 D33) */
    private static OrderItem item(long id, long orderId, Long productId, Long optionId, int quantity) {
        OrderItem i = item(id, orderId);
        lenient().when(i.getProductId()).thenReturn(productId);
        lenient().when(i.getOptionId()).thenReturn(optionId);
        lenient().when(i.getQuantity()).thenReturn(quantity);
        return i;
    }

    private static Claim returnClaim(long id, long orderItemId) {
        Claim c = mock(Claim.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getOrderItemId()).thenReturn(orderItemId);
        lenient().when(c.requestedItemStatus()).thenReturn(OrderItemStatus.RETURN_REQUESTED);
        lenient().when(c.completedItemStatus()).thenReturn(OrderItemStatus.RETURNED);
        return c;
    }

    private static Claim claim(long id, long orderItemId, String reason) {
        Claim c = mock(Claim.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getOrderItemId()).thenReturn(orderItemId);
        lenient().when(c.getReason()).thenReturn(reason);
        lenient().when(c.requestedItemStatus()).thenReturn(OrderItemStatus.CANCEL_REQUESTED);
        lenient().when(c.completedItemStatus()).thenReturn(OrderItemStatus.CANCELLED);
        return c;
    }

    private List<OrderStatusLog> savedLogs(int expectedCount) {
        ArgumentCaptor<OrderStatusLog> captor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("배송 전이: 같은 주문의 아이템 2개면 로그도 2행이고 각자 orderItemId를 갖는다")
    void shipmentLogsPerItem() {
        List<OrderItem> candidates = List.of(item(5551L, 500L), item(5552L, 500L));

        when(orderItemRepository.findAllByStatusAndStatusChangedAtLessThanEqual(
                eq(OrderItemStatus.SHIPPING), any())).thenReturn(candidates);
        when(orderItemRepository.transitionStatus(anyLong(), any(), any(), any())).thenReturn(1);

        int count = changer.transitionShipment(OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED,
                LocalDateTime.now(), true);

        assertThat(count).isEqualTo(2);
        List<OrderStatusLog> logs = savedLogs(2);
        assertThat(logs).allSatisfy(l -> {
            assertThat(l.getOrderId()).isEqualTo(500L);
            assertThat(l.getToStatus()).isEqualTo("DELIVERED");
            assertThat(l.getActorType()).isEqualTo(ActorType.SYSTEM);
        });
        assertThat(logs).extracting(OrderStatusLog::getOrderItemId)
                .containsExactly(5551L, 5552L); // 구 규칙이었다면 주문 단위 1행이었다
    }

    @Test
    @DisplayName("배송 전이: 조건부 UPDATE가 0건이면 그 아이템 로그도 남지 않는다")
    void shipmentSkipsLostRace() {
        List<OrderItem> candidates = List.of(item(5551L, 500L), item(5552L, 500L));

        when(orderItemRepository.findAllByStatusAndStatusChangedAtLessThanEqual(
                eq(OrderItemStatus.SHIPPING), any())).thenReturn(candidates);
        when(orderItemRepository.transitionStatus(eq(5551L), any(), any(), any())).thenReturn(1);
        when(orderItemRepository.transitionStatus(eq(5552L), any(), any(), any())).thenReturn(0);

        int count = changer.transitionShipment(OrderItemStatus.SHIPPING, OrderItemStatus.DELIVERED,
                LocalDateTime.now(), true);

        assertThat(count).isEqualTo(1);
        assertThat(savedLogs(1).get(0).getOrderItemId()).isEqualTo(5551L);
    }

    @Test
    @DisplayName("CONFIRMED 전이는 writeLog=false라 로그가 없다 (01 §6.5 규칙 2)")
    void confirmedWritesNoLog() {
        List<OrderItem> candidates = List.of(item(5551L, 500L));

        when(orderItemRepository.findAllByStatusAndStatusChangedAtLessThanEqual(
                eq(OrderItemStatus.DELIVERED), any())).thenReturn(candidates);
        when(orderItemRepository.transitionStatus(anyLong(), any(), any(), any())).thenReturn(1);

        changer.transitionShipment(OrderItemStatus.DELIVERED, OrderItemStatus.CONFIRMED,
                LocalDateTime.now(), false);

        savedLogs(0);
    }

    @Test
    @DisplayName("클레임 승인: 같은 주문의 아이템별로 1행씩이고 각자의 reason이 남는다")
    void claimApprovalLogsPerItemWithOwnReason() {
        List<Claim> due = List.of(claim(1L, 5551L, "단순변심"), claim(2L, 5552L, "배송지연"));
        List<OrderItem> items = List.of(item(5551L, 500L), item(5552L, 500L));

        when(claimRepository.findAllByStatusAndCreatedAtLessThanEqual(any(), any())).thenReturn(due);
        when(orderItemRepository.findAllById(any())).thenReturn(items);
        when(orderItemRepository.transitionStatus(anyLong(), any(), any(), any())).thenReturn(1);
        // 전량 취소 승격 경로는 이 테스트 관심 밖 — 남은 아이템이 있는 것으로 둔다
        when(orderItemRepository.countByOrderIdAndStatusNot(eq(500L), any())).thenReturn(1L);

        int approved = changer.approveDueClaims(LocalDateTime.now());

        assertThat(approved).isEqualTo(2);
        List<OrderStatusLog> logs = savedLogs(2);
        assertThat(logs).extracting(OrderStatusLog::getOrderItemId).containsExactly(5551L, 5552L);
        // 구 규칙은 (주문, from, to)로 묶어 첫 reason만 남기고 "배송지연"을 버렸다
        assertThat(logs).extracting(OrderStatusLog::getReason).containsExactly("단순변심", "배송지연");
        assertThat(logs).allSatisfy(l -> assertThat(l.getActorType()).isEqualTo(ActorType.USER));
    }

    @Test
    @DisplayName("클레임 승인: 취소·반품 확정분의 재고를 같은 트랜잭션에서 되돌린다 (반품도 복원 대상)")
    void claimApprovalRestoresStockForCancelAndReturn() {
        List<Claim> due = List.of(claim(1L, 5551L, "단순변심"), returnClaim(2L, 5552L));
        List<OrderItem> items = List.of(
                item(5551L, 500L, 10L, 100L, 2),    // 취소 — 옵션 있는 상품
                item(5552L, 500L, 20L, null, 3));   // 반품 — 옵션 없는 상품

        when(claimRepository.findAllByStatusAndCreatedAtLessThanEqual(any(), any())).thenReturn(due);
        when(orderItemRepository.findAllById(any())).thenReturn(items);
        when(orderItemRepository.transitionStatus(anyLong(), any(), any(), any())).thenReturn(1);
        when(orderItemRepository.countByOrderIdAndStatusNot(eq(500L), any())).thenReturn(1L);

        changer.approveDueClaims(LocalDateTime.now());

        verify(productStockService).restore(Map.of(
                new ProductStockService.StockKey(10L, 100L), 2,
                new ProductStockService.StockKey(20L, null), 3));
    }

    @Test
    @DisplayName("클레임 승인: 전이 경합에 진 아이템은 재고도 되돌리지 않는다")
    void lostRaceRestoresNothing() {
        List<Claim> due = List.of(claim(1L, 5551L, "단순변심"));
        List<OrderItem> items = List.of(item(5551L, 500L, 10L, 100L, 2));

        when(claimRepository.findAllByStatusAndCreatedAtLessThanEqual(any(), any())).thenReturn(due);
        when(orderItemRepository.findAllById(any())).thenReturn(items);
        when(orderItemRepository.transitionStatus(anyLong(), any(), any(), any())).thenReturn(0);

        assertThat(changer.approveDueClaims(LocalDateTime.now())).isZero();

        verify(productStockService, never()).restore(any());
    }

    @Test
    @DisplayName("주문 상태 전이는 orderItemId가 null이다 — 결제 성공·실패·생성")
    void orderLevelTransitionsHaveNullItemId() {
        Order order = mock(Order.class);
        lenient().when(order.getId()).thenReturn(500L);
        lenient().when(order.getStatus()).thenReturn(OrderStatus.PENDING);

        changer.logOrderCreated(order);
        changer.paymentFailed(order, "CARD_DECLINED");

        List<OrderStatusLog> logs = savedLogs(2);
        assertThat(logs).allSatisfy(l -> assertThat(l.getOrderItemId()).isNull());
        assertThat(logs.get(0).getToStatus()).isEqualTo("PENDING");
        assertThat(logs.get(1).getToStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(logs.get(1).getReason()).isEqualTo("CARD_DECLINED");
    }
}
