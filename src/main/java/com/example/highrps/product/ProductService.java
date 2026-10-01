package com.example.highrps.product;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.example.highrps.config.AppProperties;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read path: L1 Caffeine (per instance, ~ns) -> L2 Redis (shared, ~sub-ms) -> DynamoDB (source of truth).
 * <p>
 * The L1 cache is an {@link AsyncLoadingCache}: concurrent misses for the same id share one in-flight load,
 * so a hot key expiring never causes a thundering herd against Redis/DynamoDB from this instance.
 * <p>
 * Two guards stop a slow or failing DynamoDB from being amplified by retries:
 * <ul>
 *   <li><b>Stale on error:</b> L1 entries are fresh for {@code localTtl}, then refreshed synchronously. If the
 *       refresh fails, the previous value keeps being served (up to {@code localMaxStale}) instead of an error.</li>
 *   <li><b>Failure caching:</b> a failed load is remembered for {@code failureTtl}; requests for that id in the
 *       meantime fail fast (503) without touching DynamoDB, capping retries at one per id per window.</li>
 * </ul>
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final String KEY_PREFIX = "product:";

    private final ProductRepository repository;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final AppProperties.Cache cacheProps;
    private final long freshNanos;
    private final Counter redisHits;
    private final Counter redisMisses;
    private final Counter redisErrors;
    private final Counter loadFailures;
    private final Counter failedFast;
    private final Counter staleServed;
    private final Cache<String, RuntimeException> recentFailures;
    private final AsyncLoadingCache<String, Entry> local;

    /** A cached lookup result plus when it was loaded, so freshness is independent of hard expiry. */
    private record Entry(Optional<Product> product, long loadedAtNanos) {
    }

    public ProductService(ProductRepository repository, StringRedisTemplate redis, JsonMapper json,
                          AppProperties props, MeterRegistry meters) {
        this.repository = repository;
        this.redis = redis;
        this.json = json;
        this.cacheProps = props.cache();
        this.freshNanos = cacheProps.localTtl().toNanos();
        this.redisHits = meters.counter("cache.l2.requests", "result", "hit");
        this.redisMisses = meters.counter("cache.l2.requests", "result", "miss");
        this.redisErrors = meters.counter("cache.l2.requests", "result", "error");
        this.loadFailures = meters.counter("products.load", "result", "failed");
        this.failedFast = meters.counter("products.load", "result", "failed_fast");
        this.staleServed = meters.counter("products.load", "result", "stale_served");
        this.recentFailures = Caffeine.newBuilder()
                .maximumSize(cacheProps.localMaxSize())
                .expireAfterWrite(cacheProps.failureTtl())
                .build();
        this.local = Caffeine.newBuilder()
                .maximumSize(cacheProps.localMaxSize())
                .expireAfter(new FoundVsNotFoundExpiry(cacheProps.localMaxStale(), cacheProps.notFoundTtl()))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .recordStats()
                .buildAsync(this::loadFromRemote);
        CaffeineCacheMetrics.monitor(meters, local.synchronous(), "products.l1");
    }

    public Optional<Product> get(String id) {
        Entry entry = join(local.get(id));
        if (System.nanoTime() - entry.loadedAtNanos() < freshNanos) {
            return entry.product();
        }
        try {
            // Concurrent callers share one in-flight refresh; on failure Caffeine keeps the old entry.
            return join(local.synchronous().refresh(id)).product();
        } catch (RuntimeException e) {
            staleServed.increment();
            return entry.product();
        }
    }

    public Product upsert(String id, UpsertProductRequest request) {
        Product saved = repository.upsert(id, request);
        // Invalidate rather than write-through: avoids racing writers leaving an older version in Redis.
        try {
            redis.delete(KEY_PREFIX + id);
        } catch (RuntimeException e) {
            redisErrors.increment();
            log.warn("Redis invalidation failed for {}; entry will expire after {}", id, cacheProps.redisTtl());
        }
        local.synchronous().invalidate(id);
        recentFailures.invalidate(id);
        return saved;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            // Unwrap so the web layer maps the real cause (e.g. DynamoDB unavailable -> 503).
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    private Entry loadFromRemote(String id) {
        Product cached = readFromRedis(id);
        if (cached != null) {
            return new Entry(Optional.of(cached), System.nanoTime());
        }
        RuntimeException recent = recentFailures.getIfPresent(id);
        if (recent != null) {
            failedFast.increment();
            throw recent;
        }
        Optional<Product> fromDb;
        try {
            fromDb = repository.findById(id);
        } catch (RuntimeException e) {
            loadFailures.increment();
            recentFailures.put(id, e);
            // Logged once per id per failureTtl window, without a stack trace: an outage must not become a log storm.
            log.warn("Loading product {} failed; failing fast for {}: {}", id, cacheProps.failureTtl(), e.toString());
            throw e;
        }
        fromDb.ifPresent(this::writeToRedis);
        return new Entry(fromDb, System.nanoTime());
    }

    private Product readFromRedis(String id) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + id);
            if (value == null) {
                redisMisses.increment();
                return null;
            }
            redisHits.increment();
            return json.readValue(value, Product.class);
        } catch (RuntimeException e) {
            // Degrade, don't fail: Redis is an optimisation, DynamoDB is the source of truth. Catch everything,
            // not just DataAccessException: e.g. a Lettuce command cancelled by a connection reset, or bad JSON.
            redisErrors.increment();
            log.debug("Redis read failed for {}: {}", id, e.toString());
            return null;
        }
    }

    private void writeToRedis(Product product) {
        try {
            redis.opsForValue().set(KEY_PREFIX + product.id(), json.writeValueAsString(product), redisTtl());
        } catch (RuntimeException e) {
            redisErrors.increment();
            log.debug("Redis write failed for {}: {}", product.id(), e.toString());
        }
    }

    /**
     * TTL for entries written to Redis.
     * <p>
     * Products seeded or loaded together get written together, so with a fixed TTL they also expire
     * together, and every instance hits DynamoDB for all of them in the same second. Random jitter of
     * +/- {@code redisTtlJitter} spreads those expiries uniformly across a window instead.
     */
    Duration redisTtl() {
        long baseMillis = cacheProps.redisTtl().toMillis();
        double jitter = cacheProps.redisTtlJitter();
        if (jitter <= 0) {
            return cacheProps.redisTtl();
        }
        // ThreadLocalRandom: no contention between the many virtual threads writing to Redis concurrently.
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Duration.ofMillis(Math.max(1, Math.round(baseMillis * factor)));
    }

    /**
     * Found products are kept for localMaxStale (the serve-stale-on-error limit; freshness is checked in get()).
     * "Not found" answers are cached briefly to absorb floods of lookups for bad ids.
     */
    private record FoundVsNotFoundExpiry(Duration found, Duration notFound) implements Expiry<String, Entry> {
        @Override
        public long expireAfterCreate(String key, Entry value, long currentTime) {
            return (value.product().isPresent() ? found : notFound).toNanos();
        }

        @Override
        public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
            return expireAfterCreate(key, value, currentTime);
        }

        @Override
        public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
