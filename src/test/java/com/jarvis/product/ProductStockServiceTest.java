package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductStockServiceTest {

    @Mock ProductRepository productRepository;
    @Mock ProductChangeLogRepository productChangeLogRepository;

    @Captor ArgumentCaptor<List<ProductChangeLog>> logsCaptor;

    @InjectMocks ProductStockService productStockService;

    @Test
    @DisplayName("전 품목 차감 성공 — true, 재고 0 도달분만 품절 로그")
    void deductsAllAndLogsOnlySoldOut() {
        when(productRepository.deductStock(anyLong(), anyInt())).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(0));
        when(productRepository.findStockQuantity(20L)).thenReturn(Optional.of(98));

        boolean result = productStockService.deduct(Map.of(10L, 1, 20L, 2));

        assertThat(result).isTrue();
        verify(productChangeLogRepository).saveAll(logsCaptor.capture());
        assertThat(logsCaptor.getValue()).singleElement()
                .satisfies(log -> {
                    assertThat(log.getProductId()).isEqualTo(10L);
                    assertThat(log.getChangeType()).isEqualTo(ProductChangeType.STOCK);
                    assertThat(log.getNewValue()).isEqualTo("0");
                });
    }

    @Test
    @DisplayName("차감 전량 성공이어도 품절 도달분이 없으면 로그를 쓰지 않는다 — 주문에 의한 -1은 미기록 (02 D32)")
    void noLogWhenNothingSoldOut() {
        when(productRepository.deductStock(10L, 2)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(98));

        assertThat(productStockService.deduct(Map.of(10L, 2))).isTrue();

        verify(productChangeLogRepository, never()).saveAll(anyList());
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("하나라도 부족하면 false + 기차감분 보상 복원, 허위 품절 로그는 남기지 않는다")
    void restoresAppliedAndSkipsLogOnPartialFailure() {
        // 10L 차감 성공 후 재고 0 도달(품절 로그 후보) → 20L 차감 실패로 전체 롤백
        when(productRepository.deductStock(10L, 1)).thenReturn(1);
        when(productRepository.findStockQuantity(10L)).thenReturn(Optional.of(0));
        when(productRepository.deductStock(20L, 1)).thenReturn(0);

        boolean result = productStockService.deduct(Map.of(10L, 1, 20L, 1));

        assertThat(result).isFalse();
        verify(productRepository).restoreStock(10L, 1);
        verify(productChangeLogRepository, never()).saveAll(anyList());
        verify(productChangeLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("호출자가 뒤섞인 순서로 넘겨도 productId 오름차순으로 잠근다 — 동시 주문 데드락 방지")
    void locksInAscendingProductIdOrder() {
        Map<Long, Integer> shuffled = new LinkedHashMap<>();
        shuffled.put(30L, 1);
        shuffled.put(10L, 1);
        shuffled.put(20L, 1);
        when(productRepository.deductStock(anyLong(), anyInt())).thenReturn(1);
        lenient().when(productRepository.findStockQuantity(anyLong())).thenReturn(Optional.of(5));

        productStockService.deduct(shuffled);

        InOrder order = inOrder(productRepository);
        order.verify(productRepository).deductStock(10L, 1);
        order.verify(productRepository).deductStock(20L, 1);
        order.verify(productRepository).deductStock(30L, 1);
    }
}
