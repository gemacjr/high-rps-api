package com.example.highrps.product;

/** Stored in DynamoDB and serialized as-is into Redis. {@code version} drives HTTP ETags. */
public record Product(String id, String name, String description, long priceCents, String currency, long version) {
}
