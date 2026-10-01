package com.example.highrps.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.example.highrps.config.AppProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.json.JsonMapper;

class ProductServiceResilienceTest {

    private static final Product PRODUCT = new Product("p-1", "One", "", 100, "USD", 1);

    private final ProductRepository repository = mock(ProductRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void redisIsEmpty() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    private ProductService service(Duration localTtl, Duration failureTtl) {
        var cache = new AppProperties.Cache(1000, localTtl, Duration.ofMinutes(5), Duration.ofMinutes(5), 0.15,
                Duration.ofSeconds(5), failureTtl);
        return new ProductService(repository, redis, JsonMapper.builder().build(),
                new AppProperties(null, cache, null, null, null), meters);
    }

    private double count(String name, String result) {
        return meters.counter(name, "result", result).count();
    }

    @Test
    void redisOutageDegradesToDynamoDbAndLocalCacheAbsorbsRepeats() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(repository.findById("p-1")).thenReturn(Optional.of(PRODUCT));
        var service = service(Duration.ofMinutes(1), Duration.ofSeconds(1));

        for (int i = 0; i < 100; i++) {
            assertThat(service.get("p-1")).contains(PRODUCT);
        }
        verify(repository, times(1)).findById("p-1");
        assertThat(count("cache.l2.requests", "error")).isEqualTo(2.0);
    }

    @Test
    void anyRedisFailureIncludingCancelledCommandsFallsThroughToDynamoDb() {
        // e.g. Lettuce cancelling in-flight commands when its connection resets under load
        when(ops.get(anyString())).thenThrow(new java.util.concurrent.CancellationException("connection reset"));
        when(repository.findById("p-1")).thenReturn(Optional.of(PRODUCT));
        var service = service(Duration.ofMinutes(1), Duration.ofSeconds(1));

        assertThat(service.get("p-1")).contains(PRODUCT);
        assertThat(count("cache.l2.requests", "error")).isEqualTo(1.0);
    }

    @Test
    void servesStaleValueWhenRefreshFails() throws InterruptedException {
        when(repository.findById("p-1"))
                .thenReturn(Optional.of(PRODUCT))
                .thenThrow(SdkClientException.create("DynamoDB timed out"));
        var service = service(Duration.ofMillis(50), Duration.ofSeconds(1));

        assertThat(service.get("p-1")).contains(PRODUCT);
        Thread.sleep(80); // entry is now older than localTtl -> next read refreshes, and the refresh fails

        assertThat(service.get("p-1")).contains(PRODUCT);
        assertThat(service.get("p-1")).contains(PRODUCT);
        assertThat(count("products.load", "stale_served")).isEqualTo(2.0);
        // The second stale read was short-circuited by failure caching: DynamoDB saw just one failed refresh.
        verify(repository, times(2)).findById("p-1");
    }

    @Test
    void failedLoadIsRememberedSoRetriesDoNotHitDynamoDb() throws InterruptedException {
        when(repository.findById("p-1")).thenThrow(SdkClientException.create("DynamoDB timed out"));
        var service = service(Duration.ofSeconds(10), Duration.ofSeconds(5));

        assertThatThrownBy(() -> service.get("p-1")).isInstanceOf(SdkException.class);
        // Caffeine drops the failed future asynchronously; once it is gone the next call starts a new load,
        // which must be answered by the failure cache rather than DynamoDB.
        Thread.sleep(100);
        assertThatThrownBy(() -> service.get("p-1")).isInstanceOf(SdkException.class);
        assertThat(count("products.load", "failed_fast")).isEqualTo(1.0);

        for (int i = 0; i < 48; i++) {
            assertThatThrownBy(() -> service.get("p-1")).isInstanceOf(SdkException.class);
        }
        verify(repository, times(1)).findById("p-1");
        assertThat(count("products.load", "failed")).isEqualTo(1.0);
    }

    @Test
    void redisTtlIsJitteredWithinBoundsSoEntriesDoNotExpireTogether() {
        var service = service(Duration.ofMinutes(1), Duration.ofSeconds(1));

        var ttls = IntStream.range(0, 1000).mapToObj(i -> service.redisTtl()).toList();

        assertThat(ttls).allSatisfy(ttl -> assertThat(ttl).isBetween(Duration.ofSeconds(255), Duration.ofSeconds(345)));
        // Spread out, not clustered: 1000 samples over a 90s window should land in many distinct seconds.
        assertThat(ttls.stream().map(Duration::toSeconds).distinct().count()).isGreaterThan(60);
    }
}
