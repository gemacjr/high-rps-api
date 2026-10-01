package com.example.highrps.web;

import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * A downstream AWS call failed after the SDK's own retries. Answer fast with 503 + Retry-After
     * so clients and the load balancer back off instead of piling more requests onto a struggling dependency.
     * A cancelled async load (cache/client internals under stress) is the same situation, not a server bug.
     */
    @ExceptionHandler({SdkException.class, CancellationException.class})
    ResponseEntity<ProblemDetail> downstreamUnavailable(RuntimeException e) {
        log.warn("Downstream AWS call failed: {}", e.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "A downstream dependency is unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }
}
