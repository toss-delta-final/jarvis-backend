package com.jarvis.address;

import com.jarvis.address.dto.AddressCreateRequest;
import com.jarvis.address.dto.AddressListResponse;
import com.jarvis.address.dto.AddressResponse;
import com.jarvis.address.dto.AddressUpdateRequest;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M-8 (04 §5) — /api/addresses/**는 USER 가드 (SecurityConfig) */
@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ApiResponse<AddressListResponse> list(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(addressService.list(authUser.memberId()));
    }

    @PostMapping
    public ApiResponse<AddressResponse> create(@Valid @RequestBody AddressCreateRequest request,
                                               @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(addressService.create(authUser.memberId(), request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<AddressResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody AddressUpdateRequest request,
                                               @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(addressService.update(authUser.memberId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal AuthUser authUser) {
        addressService.delete(authUser.memberId(), id);
        return ApiResponse.success(null);
    }
}
