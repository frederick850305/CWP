package com.example.aps.cwp.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ScheduleJob {
    private final String jobId;
    private volatile JobStatus status;
    private volatile int progress;
    private final OffsetDateTime createdAt;
    private volatile OffsetDateTime startedAt;
    private volatile OffsetDateTime completedAt;
    private volatile JsonNode result;
    private volatile String failureReason;
    private final List<String> warnings;

    public ScheduleJob(String jobId, List<String> warnings) {
        this.jobId = jobId;
        this.status = JobStatus.QUEUED;
        this.progress = 0;
        this.createdAt = OffsetDateTime.now();
        this.warnings = Collections.synchronizedList(new ArrayList<String>(warnings));
    }

    public String getJobId() { return jobId; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public JsonNode getResult() { return result; }
    public void setResult(JsonNode result) { this.result = result; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public List<String> getWarnings() { return warnings; }
}
