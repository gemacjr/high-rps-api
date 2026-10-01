package com.example.highrps.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpsertProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @PositiveOrZero long priceCents,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
}
