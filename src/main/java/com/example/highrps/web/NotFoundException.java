package com.example.highrps.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered as an RFC 9457 problem+json body by Spring MVC. */
public class NotFoundException extends ErrorResponseException {

    public NotFoundException(String type, String id) {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, type + " '" + id + "' not found"), null);
    }
}
