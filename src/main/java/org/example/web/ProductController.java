package org.example.web;

import org.example.service.ProductService;
import org.example.web.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return productService.getProduct(id);
    }

    @PostMapping("/{id}/hot-score")
    public ProductResponse refreshHotScore(@PathVariable long id, @RequestParam int score) {
        return productService.updateHotScore(id, score);
    }
}
