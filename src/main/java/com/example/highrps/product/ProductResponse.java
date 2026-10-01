package com.example.highrps.product;

/**
 * Deliberately small wire format: at 1M RPS every byte is paid for in serialization CPU,
 * allocation rate (GC), and network bandwidth.
 */
public record ProductResponse(String id, String name, String description, long priceCents, String currency) {

    static ProductResponse from(Product p) {
        return new ProductResponse(p.id(), p.name(), p.description(), p.priceCents(), p.currency());
    }
}
