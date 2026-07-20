package com.jarvis.address;

import com.jarvis.address.dto.AddressCreateRequest;
import com.jarvis.address.dto.AddressListResponse;
import com.jarvis.address.dto.AddressResponse;
import com.jarvis.address.dto.AddressUpdateRequest;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-8 (04 §5, 02 D29) — 불변식: 주소가 1개 이상이면 기본 배송지가 정확히 1개.
 * 첫 등록 자동 기본 + 삭제 시 등록순 승격 + 지정 이동만 허용(해제 없음)으로 유지된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

    private final AddressRepository addressRepository;

    public AddressListResponse list(Long memberId) {
        return new AddressListResponse(
                addressRepository.findAllByMemberIdOrderByIsDefaultDescIdAsc(memberId).stream()
                        .map(AddressResponse::from)
                        .toList());
    }

    /** 첫 배송지는 요청값과 무관하게 기본. is_default 지정 시 기존 기본 해제 — 같은 트랜잭션 (04 §5) */
    @Transactional
    public AddressResponse create(Long memberId, AddressCreateRequest request) {
        boolean isDefault = request.wantsDefault() || !addressRepository.existsByMemberId(memberId);
        if (isDefault) {
            addressRepository.clearDefault(memberId);
        }
        Address address = addressRepository.save(Address.create(memberId, request.label(),
                request.recipient(), request.phone(), request.zipCode(),
                request.address1(), request.address2(), isDefault));
        return AddressResponse.from(address);
    }

    @Transactional
    public AddressResponse update(Long memberId, Long addressId, AddressUpdateRequest request) {
        Address address = getOwned(memberId, addressId);
        address.update(request.label(), request.recipient(), request.phone(),
                request.zipCode(), request.address1(), request.address2());
        if (request.wantsDefault() && !address.isDefault()) {
            addressRepository.clearDefault(memberId);
            address.markDefault();
        }
        return AddressResponse.from(address);
    }

    /** 기본 배송지는 다른 주소가 있을 때만 삭제 가능 — 등록순 가장 오래된 주소 자동 승격 (02 D29) */
    @Transactional
    public void delete(Long memberId, Long addressId) {
        Address address = getOwned(memberId, addressId);
        if (address.isDefault()) {
            if (addressRepository.countByMemberId(memberId) <= 1) {
                throw new BusinessException(ErrorCode.ADDRESS_LAST_UNDELETABLE);
            }
            addressRepository.findFirstByMemberIdAndIdNotOrderByIdAsc(memberId, addressId)
                    .ifPresent(Address::markDefault);
        }
        addressRepository.delete(address);
    }

    private Address getOwned(Long memberId, Long addressId) {
        return addressRepository.findByIdAndMemberId(addressId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
    }
}
