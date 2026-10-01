package com.example.highrps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Runs the real app against LocalStack (DynamoDB + SQS) and Valkey, using the same init script as docker-compose. */
@SpringBootTest(properties = {"app.seed.enabled=true", "app.seed.products=50", "app.orders.wait-time-seconds=3"})
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14"))
            .withServices("dynamodb", "sqs")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("localstack/init/ready.d/01-create-resources.sh", 0755),
                    "/etc/localstack/init/ready.d/01-create-resources.sh")
            .waitingFor(Wait.forHttp("/_localstack/init/ready").forPort(4566)
                    .forResponsePredicate(body -> body.contains("\"completed\": true")));

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        registry.add("app.aws.endpoint", () -> localstack.getEndpoint().toString());
        registry.add("app.aws.region", localstack::getRegion);
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    void getProductReturnsCacheableResponseAnd304OnRevalidation() {
        MvcTestResult first = mvc.get().uri("/api/products/p-1").exchange();
        assertThat(first).hasStatusOk()
                .bodyJson().extractingPath("$.priceCents").isEqualTo(100);
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("public", "max-age=30");

        assertThat(mvc.get().uri("/api/products/p-1").header(HttpHeaders.IF_NONE_MATCH, etag))
                .hasStatus(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void unknownAndInvalidProductIdsReturnProblemDetails() {
        assertThat(mvc.get().uri("/api/products/does-not-exist")).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvc.get().uri("/api/products/bad!id")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateInvalidatesCachesAndChangesEtag() {
        String before = mvc.get().uri("/api/products/p-2").exchange().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(mvc.put().uri("/api/products/p-2").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed","priceCents":999,"currency":"USD"}"""))
                .hasStatusOk();

        MvcTestResult after = mvc.get().uri("/api/products/p-2").exchange();
        assertThat(after).bodyJson().extractingPath("$.priceCents").isEqualTo(999);
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(before);
    }

    @Test
    void orderIsAcceptedIdempotentAndConfirmedAsynchronously() {
        String key = "test-" + UUID.randomUUID();
        String body = """
                {"customerId":"c-1","items":[{"productId":"p-3","quantity":2},{"productId":"p-4","quantity":1}]}""";

        MvcTestResult placed = mvc.post().uri("/api/orders").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(placed).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.totalCents").isEqualTo(300 * 2 + 400);
        assertThat(placed.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/orders/" + key);

        // Client retry with the same key: no duplicate order.
        assertThat(mvc.post().uri("/api/orders").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatusOk()
                .bodyJson().extractingPath("$.id").isEqualTo(key);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/orders/" + key))
                        .bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED"));
    }

    @Test
    void orderWithUnknownProductIsRejected() {
        assertThat(mvc.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerId":"c-1","items":[{"productId":"nope","quantity":1}]}"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
