package com.example.highrps.config;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

/**
 * Synchronous AWS SDK clients. Blocking calls are fine here because every request runs on a
 * virtual thread (spring.threads.virtual.enabled=true), so no carrier thread is held while waiting.
 * Each client gets its own bounded connection pool so SQS long-polling can never starve DynamoDB.
 */
@Configuration(proxyBeanMethods = false)
public class AwsConfig {

    private final AppProperties.Aws aws;

    public AwsConfig(AppProperties props) {
        this.aws = props.aws();
    }

    @Bean
    DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder();
        configure(builder, aws.maxConnections());
        return builder.build();
    }

    @Bean
    SqsClient sqsClient(AppProperties props) {
        var builder = SqsClient.builder();
        // Long polls hold a connection each, so size for pollers plus publishers.
        configure(builder, Math.max(50, props.orders().consumerPollers() * 2 + 25));
        return builder.build();
    }

    private <B extends AwsClientBuilder<B, ?> & AwsSyncClientBuilder<B, ?>> void configure(B builder, int maxConnections) {
        builder.region(Region.of(aws.region()))
                .credentialsProvider(credentials())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(maxConnections)
                        .connectionTimeout(Duration.ofSeconds(1))
                        .connectionAcquisitionTimeout(Duration.ofMillis(500))
                        .tcpKeepAlive(true))
                .overrideConfiguration((Consumer<ClientOverrideConfiguration.Builder>) c -> c
                        .apiCallTimeout(aws.apiCallTimeout())
                        .apiCallAttemptTimeout(aws.apiCallAttemptTimeout()));
        if (aws.endpoint() != null) {
            builder.endpointOverride(aws.endpoint());
        }
    }

    private AwsCredentialsProvider credentials() {
        // LocalStack accepts any credentials; in AWS the ECS task role is picked up by the default chain.
        return aws.endpoint() != null
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                : DefaultCredentialsProvider.builder().build();
    }
}
