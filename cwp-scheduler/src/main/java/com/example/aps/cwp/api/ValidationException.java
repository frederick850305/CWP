package com.example.aps.cwp.api;

import java.util.Collections;
import java.util.List;

public class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super("Invalid CWP scheduling input");
        this.errors = Collections.unmodifiableList(errors);
    }

    public List<String> getErrors() { return errors; }
}
