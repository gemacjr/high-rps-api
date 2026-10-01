package com.example.highrps.product;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.highrps.config.AppProperties;
import com.example.highrps.web.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final ProductService products;
    private final CacheControl cacheControl;

    public ProductController(ProductService products, AppProperties props) {
        this.products = products;
        // Public + max-age lets a CDN / browser serve repeats without touching this service at all:
        // the cheapest request is the one that never arrives.
        this.cacheControl = CacheControl.maxAge(props.http().productMaxAge()).cachePublic();
    }

    /** Spring answers 304 automatically when If-None-Match matches the ETag below. */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> get(@PathVariable @Pattern(regexp = ID_PATTERN) String id) {
        Product product = products.get(id).orElseThrow(() -> new NotFoundException("product", id));
        return ResponseEntity.ok()
                .eTag(product.id() + "-" + product.version())
                .cacheControl(cacheControl)
                .body(ProductResponse.from(product));
    }

    @PutMapping("/{id}")
    public ProductResponse upsert(@PathVariable @Pattern(regexp = ID_PATTERN) String id,
                                  @Valid @RequestBody UpsertProductRequest request) {
        return ProductResponse.from(products.upsert(id, request));
    }
}
