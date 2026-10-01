package com.example.highrps.order;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.example.highrps.config.AppProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Background work pulled off the request path: long-polls SQS, handles each batch of up to 10 messages
 * in parallel on virtual threads, and deletes only the ones that succeeded. Failures become visible again
 * after the queue's visibility timeout and land in the DLQ after maxReceiveCount attempts.
 * <p>
 * In production this could run as a separate ECS service so API and worker scale independently.
 */
@Component
@ConditionalOnBooleanProperty(name = "app.orders.consumer-enabled", matchIfMissing = true)
class OrderEventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final SqsClient sqs;
    private final OrderEventPublisher publisher;
    private final OrderRepository orders;
    private final JsonMapper json;
    private final AppProperties.Orders props;
    private final Duration longPollTimeout;
    private final Counter processed;
    private final Counter failed;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Thread> pollers = new ArrayList<>();
    private volatile boolean running;

    OrderEventConsumer(SqsClient sqs, OrderEventPublisher publisher, OrderRepository orders, JsonMapper json,
                       AppProperties props, MeterRegistry meters) {
        this.sqs = sqs;
        this.publisher = publisher;
        this.orders = orders;
        this.json = json;
        this.props = props.orders();
        this.longPollTimeout = Duration.ofSeconds(this.props.waitTimeSeconds() + 5L);
        this.processed = meters.counter("orders.events", "result", "processed");
        this.failed = meters.counter("orders.events", "result", "failed");
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < props.consumerPollers(); i++) {
            pollers.add(Thread.ofVirtual().name("sqs-poller-" + i).start(this::pollLoop));
        }
    }

    @Override
    public void stop() {
        running = false;
        pollers.forEach(Thread::interrupt);
        workers.close(); // waits for in-flight messages to finish
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                String queueUrl = publisher.queueUrl();
                List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(props.waitTimeSeconds())
                        // Long polls legitimately outlive the client's fast-fail timeouts meant for API calls.
                        .overrideConfiguration(o -> o.apiCallTimeout(longPollTimeout).apiCallAttemptTimeout(longPollTimeout)))
                        .messages();
                if (!messages.isEmpty()) {
                    handleBatch(queueUrl, messages);
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("SQS poll failed, backing off: {}", e.toString());
                sleepQuietly(1000);
            }
        }
    }

    private void handleBatch(String queueUrl, List<Message> messages) throws InterruptedException {
        List<Future<Boolean>> results = new ArrayList<>(messages.size());
        for (Message m : messages) {
            results.add(workers.submit(() -> handle(m)));
        }
        List<DeleteMessageBatchRequestEntry> done = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            try {
                if (results.get(i).get()) {
                    done.add(DeleteMessageBatchRequestEntry.builder()
                            .id(Integer.toString(i)).receiptHandle(messages.get(i).receiptHandle()).build());
                }
            } catch (java.util.concurrent.ExecutionException e) {
                failed.increment();
                log.warn("Order event {} failed and will be retried: {}", messages.get(i).messageId(), e.getCause().toString());
            }
        }
        if (!done.isEmpty()) {
            sqs.deleteMessageBatch(r -> r.queueUrl(queueUrl).entries(done));
        }
    }

    private boolean handle(Message message) {
        OrderPlacedEvent event = json.readValue(message.body(), OrderPlacedEvent.class);
        // Stand-in for the slow, non-critical work the article says to move off the request path:
        // invoice generation, confirmation email, analytics, notifying other services.
        if (orders.confirm(event.orderId())) {
            log.debug("Order {} confirmed", event.orderId());
        }
        processed.increment();
        return true;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
