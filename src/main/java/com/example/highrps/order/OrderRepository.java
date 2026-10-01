package com.example.highrps.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.example.highrps.config.AppProperties;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class OrderRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public OrderRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.aws().ordersTable();
    }

    /** @return true if created, false if an order with this id already exists (idempotent replay). */
    public boolean createIfAbsent(Order order) {
        try {
            dynamo.putItem(r -> r.tableName(table)
                    .item(toItem(order))
                    .conditionExpression("attribute_not_exists(id)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public Optional<Order> findById(String id) {
        var item = dynamo.getItem(r -> r.tableName(table).key(Map.of("id", AttributeValue.fromS(id)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    /** Conditional transition makes the consumer idempotent under SQS at-least-once delivery. */
    public boolean confirm(String id) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of("id", AttributeValue.fromS(id)))
                    .updateExpression("SET #s = :confirmed")
                    .conditionExpression("#s = :pending")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":confirmed", AttributeValue.fromS(OrderStatus.CONFIRMED.name()),
                            ":pending", AttributeValue.fromS(OrderStatus.PENDING.name()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private static Map<String, AttributeValue> toItem(Order o) {
        List<AttributeValue> lines = o.lines().stream()
                .map(l -> AttributeValue.fromM(Map.of(
                        "productId", AttributeValue.fromS(l.productId()),
                        "quantity", AttributeValue.fromN(Integer.toString(l.quantity())),
                        "unitPriceCents", AttributeValue.fromN(Long.toString(l.unitPriceCents())))))
                .toList();
        return Map.of(
                "id", AttributeValue.fromS(o.id()),
                "customerId", AttributeValue.fromS(o.customerId()),
                "lines", AttributeValue.fromL(lines),
                "totalCents", AttributeValue.fromN(Long.toString(o.totalCents())),
                "currency", AttributeValue.fromS(o.currency()),
                "status", AttributeValue.fromS(o.status().name()),
                "createdAt", AttributeValue.fromS(o.createdAt().toString()));
    }

    private static Order fromItem(Map<String, AttributeValue> item) {
        List<Order.Line> lines = item.get("lines").l().stream()
                .map(AttributeValue::m)
                .map(m -> new Order.Line(m.get("productId").s(), Integer.parseInt(m.get("quantity").n()),
                        Long.parseLong(m.get("unitPriceCents").n())))
                .toList();
        return new Order(item.get("id").s(), item.get("customerId").s(), lines,
                Long.parseLong(item.get("totalCents").n()), item.get("currency").s(),
                OrderStatus.valueOf(item.get("status").s()), Instant.parse(item.get("createdAt").s()));
    }
}
