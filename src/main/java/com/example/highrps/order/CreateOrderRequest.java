package com.example.highrps.order;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Bounded collection and field sizes keep a single request from costing unbounded CPU or DynamoDB item size. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotEmpty @Size(max = 50) List<@Valid Item> items) {

    public record Item(@NotBlank @Size(max = 64) String productId, @Min(1) @Max(1000) int quantity) {
    }
}
