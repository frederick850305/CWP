package com.example.aps.cwp.api;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) { super("Schedule job not found: " + jobId); }
}
