package com.example.highrps.order;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.highrps.web.NotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * 202 Accepted: the order is durably stored, downstream processing continues asynchronously.
     * Clients should always send an Idempotency-Key so retries (timeouts, 503s) never create duplicates.
     */
    @PostMapping
    public ResponseEntity<Order> place(
            @RequestHeader(name = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderService.Placement placement = orders.place(request, idempotencyKey);
        return ResponseEntity.status(placement.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/orders/" + placement.order().id()))
                .body(placement.order());
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id) {
        return orders.find(id).orElseThrow(() -> new NotFoundException("order", id));
    }
}
