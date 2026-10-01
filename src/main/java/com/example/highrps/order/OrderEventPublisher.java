package com.example.highrps.order;

import org.springframework.stereotype.Component;

import com.example.highrps.config.AppProperties;

import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderEventPublisher {

    private final SqsClient sqs;
    private final JsonMapper json;
    private final String queueName;
    private volatile String queueUrl;

    public OrderEventPublisher(SqsClient sqs, JsonMapper json, AppProperties props) {
        this.sqs = sqs;
        this.json = json;
        this.queueName = props.aws().orderEventsQueue();
    }

    public void publish(OrderPlacedEvent event) {
        sqs.sendMessage(r -> r.queueUrl(queueUrl()).messageBody(json.writeValueAsString(event)));
    }

    String queueUrl() {
        String url = queueUrl;
        if (url == null) {
            url = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            queueUrl = url;
        }
        return url;
    }
}
