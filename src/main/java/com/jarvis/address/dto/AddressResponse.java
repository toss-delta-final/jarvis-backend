package com.jarvis.address.dto;

import com.jarvis.address.Address;

/** M-8 (04 §5) */
public record AddressResponse(Long addressId, String label, String recipient, String phone,
                              String zipCode, String address1, String address2,
                              boolean isDefault) {

    public static AddressResponse from(Address address) {
        return new AddressResponse(address.getId(), address.getLabel(), address.getRecipient(),
                address.getPhone(), address.getZipCode(), address.getAddress1(),
                address.getAddress2(), address.isDefault());
    }
}
