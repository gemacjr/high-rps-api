package com.example.highrps.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunables in one place so they can be changed per environment (local / LocalStack / AWS)
 * without code changes. Measure first, then tune these values.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Aws aws, Cache cache, Http http, Orders orders, Seed seed) {

    public record Aws(
            @DefaultValue("us-east-1") String region,
            /* Only set for LocalStack; null means "use the real AWS endpoint". */
            URI endpoint,
            @DefaultValue("products") String productsTable,
            @DefaultValue("orders") String ordersTable,
            @DefaultValue("order-events") String orderEventsQueue,
            /* Per-instance cap. Total load on AWS = instances x this value, so keep it bounded. */
            @DefaultValue("200") int maxConnections,
            @DefaultValue("2s") Duration apiCallTimeout,
            @DefaultValue("500ms") Duration apiCallAttemptTimeout) {
    }

    public record Cache(
            @DefaultValue("100000") long localMaxSize,
            /* Short: other instances' local caches can be stale for at most this long after a write. */
            @DefaultValue("10s") Duration localTtl,
            /* How long an L1 entry may still be served when refreshing it fails (DynamoDB/Redis trouble). */
            @DefaultValue("5m") Duration localMaxStale,
            @DefaultValue("5m") Duration redisTtl,
            /* Fraction of redisTtl applied as random +/- jitter (0.15 = 4m15s..5m45s) to avoid synchronized expiry. */
            @DefaultValue("0.15") double redisTtlJitter,
            /* Negative caching stops floods of lookups for non-existent ids from reaching DynamoDB. */
            @DefaultValue("5s") Duration notFoundTtl,
            /* After a failed load, requests for that id fail fast for this long instead of retrying DynamoDB. */
            @DefaultValue("1s") Duration failureTtl) {
    }

    public record Http(@DefaultValue("30s") Duration productMaxAge) {
    }

    public record Orders(
            @DefaultValue("true") boolean consumerEnabled,
            @DefaultValue("2") int consumerPollers,
            @DefaultValue("10") int waitTimeSeconds) {
    }

    public record Seed(@DefaultValue("false") boolean enabled, @DefaultValue("1000") int products) {
    }
}
