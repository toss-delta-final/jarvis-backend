package com.jarvis.address.dto;

import java.util.List;

/** M-8a (04 §5) — 목록은 {"addresses": [...]}로 감싼다 (Notion 계약) */
public record AddressListResponse(List<AddressResponse> addresses) {
}
