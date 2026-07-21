package com.example.aps.cwp.api;

import com.example.aps.cwp.job.ScheduleJob;
import com.example.aps.cwp.service.ScheduleJobService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cwp-schedule-jobs")
public class ScheduleJobController {
    private final ScheduleJobService service;

    public ScheduleJobController(ScheduleJobService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode input) {
        ScheduleJob job = service.create(input);
        Map<String, Object> body = summary(job);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/cwp-schedule-jobs/" + job.getJobId()))
                .body(body);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> body = new ArrayList<Map<String, Object>>();
        for (ScheduleJob job : service.list()) body.add(summary(job));
        return body;
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> status(@PathVariable String jobId) {
        return summary(service.get(jobId));
    }

    @GetMapping("/{jobId}/result")
    public JsonNode result(@PathVariable String jobId) { return service.result(jobId); }

    @GetMapping(value = "/{jobId}/result/download", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> downloadResult(@PathVariable String jobId) {
        JsonNode result = service.result(jobId);
        String filename = "cwp-schedule-" + jobId + ".json";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }

    private Map<String, Object> summary(ScheduleJob job) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("jobId", job.getJobId());
        body.put("status", job.getStatus());
        body.put("progress", job.getProgress());
        body.put("createdAt", job.getCreatedAt());
        body.put("startedAt", job.getStartedAt());
        body.put("completedAt", job.getCompletedAt());
        body.put("warnings", job.getWarnings());
        body.put("failureReason", job.getFailureReason());
        return body;
    }
}
