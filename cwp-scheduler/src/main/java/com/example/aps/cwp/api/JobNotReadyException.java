package com.example.aps.cwp.api;

public class JobNotReadyException extends RuntimeException {
    public JobNotReadyException(String jobId, String status) {
        super("Schedule job " + jobId + " is not complete; current status is " + status);
    }
}
