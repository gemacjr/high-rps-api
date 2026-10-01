package com.example.highrps.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import com.example.highrps.product.Product;
import com.example.highrps.product.ProductService;

/**
 * Write path. Only the work the client must wait for (validate, price, persist) happens inline;
 * confirmation/invoicing/notifications run asynchronously off an SQS queue.
 * <p>
 * Dual-write safety: the order is persisted first, then the event is published. If publishing fails the
 * client gets a 503 and retries with the same Idempotency-Key; the replay finds the PENDING order and
 * publishes again. The consumer's conditional PENDING->CONFIRMED update makes duplicates harmless.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductService products;
    private final OrderEventPublisher events;

    public OrderService(OrderRepository orders, ProductService products, OrderEventPublisher events) {
        this.orders = orders;
        this.products = products;
        this.events = events;
    }

    public record Placement(Order order, boolean created) {
    }

    public Placement place(CreateOrderRequest request, String idempotencyKey) {
        String orderId = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        Order candidate = price(orderId, request);

        boolean created = orders.createIfAbsent(candidate);
        Order order = created ? candidate : orders.findById(orderId).orElseThrow();
        if (!created && !order.customerId().equals(request.customerId())) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Idempotency-Key already used for a different order"), null);
        }
        if (order.status() == OrderStatus.PENDING) {
            events.publish(new OrderPlacedEvent(order.id()));
        }
        return new Placement(order, created);
    }

    public Optional<Order> find(String id) {
        return orders.findById(id);
    }

    private Order price(String orderId, CreateOrderRequest request) {
        List<Order.Line> lines = new ArrayList<>(request.items().size());
        List<String> unknown = new ArrayList<>();
        String currency = null;
        long total = 0;
        for (CreateOrderRequest.Item item : request.items()) {
            Optional<Product> product = products.get(item.productId()); // served from L1/L2 cache almost always
            if (product.isEmpty()) {
                unknown.add(item.productId());
                continue;
            }
            Product p = product.get();
            if (currency != null && !currency.equals(p.currency())) {
                throw unprocessable("Mixed currencies in one order are not supported");
            }
            currency = p.currency();
            lines.add(new Order.Line(p.id(), item.quantity(), p.priceCents()));
            total = Math.addExact(total, Math.multiplyExact(p.priceCents(), item.quantity()));
        }
        if (!unknown.isEmpty()) {
            throw unprocessable("Unknown products: " + unknown);
        }
        return new Order(orderId, request.customerId(), lines, total, currency, OrderStatus.PENDING, Instant.now());
    }

    private static ErrorResponseException unprocessable(String detail) {
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail), null);
    }
}
