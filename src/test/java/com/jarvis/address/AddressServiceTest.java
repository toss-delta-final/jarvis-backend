package com.jarvis.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jarvis.address.dto.AddressCreateRequest;
import com.jarvis.address.dto.AddressListResponse;
import com.jarvis.address.dto.AddressResponse;
import com.jarvis.address.dto.AddressUpdateRequest;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/** M-8 배송지 CRUD — 기본 배송지 규칙 (04 §5, 02 D29) */
@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock AddressRepository addressRepository;

    @InjectMocks AddressService addressService;

    @BeforeEach
    void setUp() {
        lenient().when(addressRepository.save(any(Address.class))).thenAnswer(inv -> {
            Address address = inv.getArgument(0);
            if (ReflectionTestUtils.getField(address, "id") == null) {
                ReflectionTestUtils.setField(address, "id", 11L);
            }
            return address;
        });
    }

    private AddressCreateRequest createRequest(Boolean isDefault) {
        return new AddressCreateRequest("집", "김자비", "010-1234-5678", "06236",
                "서울시 강남구", "101호", isDefault);
    }

    private Address savedAddress(Long id, boolean isDefault) {
        Address address = mock(Address.class, withSettings().strictness(Strictness.LENIENT));
        when(address.getId()).thenReturn(id);
        when(address.isDefault()).thenReturn(isDefault);
        return address;
    }

    @Test
    @DisplayName("M-8 — 첫 배송지는 isDefault 요청값과 무관하게 기본으로 저장")
    void firstAddressBecomesDefault() {
        when(addressRepository.existsByMemberId(MEMBER_ID)).thenReturn(false);

        AddressResponse response = addressService.create(MEMBER_ID, createRequest(false));

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).save(captor.capture());
        assertThat(captor.getValue().isDefault()).isTrue();
        assertThat(response.isDefault()).isTrue();
    }

    @Test
    @DisplayName("M-8 — isDefault=true로 추가하면 기존 기본이 해제된다 (같은 트랜잭션)")
    void newDefaultClearsPrevious() {
        // isDefault=true 요청은 존재 여부 조회 없이 바로 기본 지정 (단락 평가)
        addressService.create(MEMBER_ID, createRequest(true));

        // 조건부 UPDATE로 해제 — 단건 Optional 조회는 기본 2건 상태에서 영구 500이 되므로 금지
        verify(addressRepository).clearDefault(MEMBER_ID);
    }

    @Test
    @DisplayName("M-8 — 남의 배송지 수정은 404 ADDRESS_NOT_FOUND")
    void updateNotMine() {
        when(addressRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.update(MEMBER_ID, 5L,
                new AddressUpdateRequest("회사", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ADDRESS_NOT_FOUND);
    }

    @Test
    @DisplayName("M-8 — 유일한 배송지는 삭제 불가 400 ADDRESS_LAST_UNDELETABLE")
    void lastAddressUndeletable() {
        Address only = savedAddress(5L, true);
        when(addressRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.of(only));
        when(addressRepository.countByMemberId(MEMBER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> addressService.delete(MEMBER_ID, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ADDRESS_LAST_UNDELETABLE);
        verify(addressRepository, never()).delete(any(Address.class));
    }

    @Test
    @DisplayName("M-8 — 기본 배송지 삭제 시 등록순 가장 오래된 주소가 자동 승격 (02 D29)")
    void deleteDefaultPromotesOldest() {
        Address target = savedAddress(5L, true);
        Address oldest = savedAddress(2L, false);
        when(addressRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.of(target));
        when(addressRepository.countByMemberId(MEMBER_ID)).thenReturn(3L);
        when(addressRepository.findFirstByMemberIdAndIdNotOrderByIdAsc(MEMBER_ID, 5L))
                .thenReturn(Optional.of(oldest));

        addressService.delete(MEMBER_ID, 5L);

        verify(oldest).markDefault();
        verify(addressRepository).delete(target);
        // uk_address_default 위반 방지: 옛 기본 행 DELETE·flush가 승격(markDefault)보다 먼저여야 한다.
        InOrder order = inOrder(addressRepository, oldest);
        order.verify(addressRepository).delete(target);
        order.verify(addressRepository).flush();
        order.verify(oldest).markDefault();
    }

    @Test
    @DisplayName("M-8 — 기본이 아닌 배송지는 승격 없이 삭제")
    void deleteNonDefaultPlain() {
        Address target = savedAddress(5L, false);
        when(addressRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.of(target));

        addressService.delete(MEMBER_ID, 5L);

        verify(addressRepository).delete(target);
        verify(addressRepository, never()).findFirstByMemberIdAndIdNotOrderByIdAsc(any(), any());
    }

    @Test
    @DisplayName("M-8 — 수정에서 isDefault=true면 기존 기본 해제 후 자신이 기본")
    void updatePromotesDefault() {
        Address target = savedAddress(5L, false);
        when(addressRepository.findByIdAndMemberId(5L, MEMBER_ID)).thenReturn(Optional.of(target));

        addressService.update(MEMBER_ID, 5L,
                new AddressUpdateRequest(null, null, null, null, null, null, true));

        verify(addressRepository).clearDefault(MEMBER_ID);
        verify(target).markDefault();
    }

    @Test
    @DisplayName("M-8 — 목록은 기본 먼저, 이후 등록순")
    void listOrder() {
        Address a1 = savedAddress(3L, true);
        Address a2 = savedAddress(1L, false);
        when(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdAsc(MEMBER_ID))
                .thenReturn(List.of(a1, a2));

        AddressListResponse response = addressService.list(MEMBER_ID);

        assertThat(response.addresses()).extracting(AddressResponse::addressId).containsExactly(3L, 1L);
    }
}
