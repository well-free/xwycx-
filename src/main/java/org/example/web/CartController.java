package org.example.web;

import jakarta.validation.Valid;
import org.example.service.AuthService;
import org.example.service.CartService;
import org.example.web.dto.ApiPageResponse;
import org.example.web.dto.CartItemRequest;
import org.example.web.dto.CartItemResponse;
import org.example.web.dto.CartQuantityRequest;
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
@RequestMapping("/api/cart")
public class CartController {
    private final AuthService authService;
    private final CartService cartService;

    public CartController(AuthService authService, CartService cartService) {
        this.authService = authService;
        this.cartService = cartService;
    }

    @GetMapping
    public ApiPageResponse<CartItemResponse> list(
            @RequestHeader(value = "X-Session-Token", required = false) String token) {
        List<CartItemResponse> items = cartService.list(authService.requireUser(token));
        return new ApiPageResponse<>(items.size(), items);
    }

    @PostMapping("/items")
    public CartItemResponse add(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                @Valid @RequestBody CartItemRequest request) {
        return cartService.put(authService.requireUser(token), request);
    }

    @PutMapping("/items/{productId}")
    public CartItemResponse update(@RequestHeader(value = "X-Session-Token", required = false) String token,
                                   @PathVariable long productId,
                                   @Valid @RequestBody CartQuantityRequest request) {
        return cartService.put(authService.requireUser(token), new CartItemRequest(productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "X-Session-Token", required = false) String token,
                       @PathVariable long productId) {
        cartService.remove(authService.requireUser(token), productId);
    }
}
