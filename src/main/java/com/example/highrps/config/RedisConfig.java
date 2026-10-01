package com.example.highrps.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Lettuce multiplexes all commands over a single connection per instance, so Redis connection
 * count grows with instances, not with request concurrency.
 * <p>
 * REJECT_COMMANDS makes calls fail immediately while Redis is unreachable instead of queueing until
 * timeout, so a cache outage degrades to "read from DynamoDB" rather than "every request is slow".
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown(DataRedisProperties redis) {
        Duration connectTimeout = redis.getConnectTimeout() != null ? redis.getConnectTimeout() : Duration.ofMillis(500);
        Duration commandTimeout = redis.getTimeout() != null ? redis.getTimeout() : Duration.ofMillis(250);
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).keepAlive(true).build())
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .build());
    }
}
