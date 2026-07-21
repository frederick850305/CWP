package com.example.aps.cwp.api;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(ValidationException ex) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getErrors());
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(JobNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", Arrays.asList(ex.getMessage()));
    }

    @ExceptionHandler(JobNotReadyException.class)
    public ResponseEntity<Map<String, Object>> notReady(JobNotReadyException ex) {
        return response(HttpStatus.CONFLICT, "JOB_NOT_READY", Arrays.asList(ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                Arrays.asList("Unexpected server error"));
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, Object errors) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("errors", errors);
        return ResponseEntity.status(status).body(body);
    }
}
