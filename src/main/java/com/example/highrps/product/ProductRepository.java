package com.example.highrps.product;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.example.highrps.config.AppProperties;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/** Low-level DynamoDB mapping: no reflection, predictable allocation on the hot path. */
@Repository
public class ProductRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public ProductRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.aws().productsTable();
    }

    public Optional<Product> findById(String id) {
        var item = dynamo.getItem(r -> r.tableName(table).key(Map.of("id", s(id)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    /** Upsert that atomically bumps the version so caches and ETags change on every write. */
    public Product upsert(String id, UpsertProductRequest req) {
        var values = new HashMap<String, AttributeValue>();
        values.put(":n", s(req.name()));
        values.put(":d", s(req.description() == null ? "" : req.description()));
        values.put(":p", n(req.priceCents()));
        values.put(":c", s(req.currency()));
        values.put(":one", n(1));
        var updated = dynamo.updateItem(r -> r.tableName(table)
                .key(Map.of("id", s(id)))
                .updateExpression("SET #n = :n, description = :d, priceCents = :p, currency = :c ADD version :one")
                .expressionAttributeNames(Map.of("#n", "name"))
                .expressionAttributeValues(values)
                .returnValues(ReturnValue.ALL_NEW));
        return fromItem(updated.attributes());
    }

    public boolean isEmpty() {
        return dynamo.scan(r -> r.tableName(table).limit(1)).count() == 0;
    }

    public void saveAll(List<Product> products) {
        for (int i = 0; i < products.size(); i += 25) { // BatchWriteItem limit
            List<WriteRequest> writes = new ArrayList<>();
            for (Product p : products.subList(i, Math.min(i + 25, products.size()))) {
                writes.add(WriteRequest.builder().putRequest(PutRequest.builder().item(toItem(p)).build()).build());
            }
            Map<String, List<WriteRequest>> pending = Map.of(table, writes);
            while (!pending.isEmpty()) {
                final var batch = pending;
                BatchWriteItemResponse resp = dynamo.batchWriteItem(r -> r.requestItems(batch));
                pending = resp.hasUnprocessedItems() ? resp.unprocessedItems() : Map.of();
            }
        }
    }

    private static Map<String, AttributeValue> toItem(Product p) {
        return Map.of("id", s(p.id()), "name", s(p.name()), "description", s(p.description()),
                "priceCents", n(p.priceCents()), "currency", s(p.currency()), "version", n(p.version()));
    }

    private static Product fromItem(Map<String, AttributeValue> item) {
        return new Product(item.get("id").s(), item.get("name").s(),
                item.containsKey("description") ? item.get("description").s() : "",
                Long.parseLong(item.get("priceCents").n()), item.get("currency").s(),
                Long.parseLong(item.get("version").n()));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }
}
