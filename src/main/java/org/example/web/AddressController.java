package org.example.web;

import jakarta.validation.Valid;
import org.example.service.AddressService;
import org.example.service.AuthService;
import org.example.web.dto.AddressRequest;
import org.example.web.dto.AddressResponse;
import org.example.web.dto.ApiPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {
    private final AuthService authService;
    private final AddressService addressService;

    public AddressController(AuthService authService, AddressService addressService) {
        this.authService = authService;
        this.addressService = addressService;
    }

    @GetMapping
    public ApiPageResponse<AddressResponse> list(
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        List<AddressResponse> items = addressService.list(authService.requireUser(token));
        return new ApiPageResponse<>(items.size(), items);
    }

    @PostMapping
    public AddressResponse create(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @Valid @RequestBody AddressRequest request) {
        return addressService.create(authService.requireUser(token), request);
    }

    @PutMapping("/{id}")
    public AddressResponse update(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable long id,
            @Valid @RequestBody AddressRequest request) {
        return addressService.update(authService.requireUser(token), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = "X-Session-Token", required = false) String token,
            @PathVariable long id) {
        addressService.delete(authService.requireUser(token), id);
    }
}
