package com.example.highrps.order;

import java.time.Instant;
import java.util.List;

public record Order(String id, String customerId, List<Line> lines, long totalCents, String currency,
                    OrderStatus status, Instant createdAt) {

    public record Line(String productId, int quantity, long unitPriceCents) {
    }
}
