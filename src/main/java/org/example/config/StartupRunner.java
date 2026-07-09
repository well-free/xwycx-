package org.example.config;

import org.example.service.ProductService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements ApplicationRunner {
    private final ProductService productService;

    public StartupRunner(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public void run(ApplicationArguments args) {
        productService.seedDemoProducts();
    }
}
