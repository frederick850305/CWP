package com.example.aps.cwp.service;

import com.example.aps.cwp.api.JobNotFoundException;
import com.example.aps.cwp.api.JobNotReadyException;
import com.example.aps.cwp.engine.AlgorithmRegistry;
import com.example.aps.cwp.engine.ScheduleAlgorithm;
import com.example.aps.cwp.job.JobStatus;
import com.example.aps.cwp.job.ScheduleJob;
import com.example.aps.cwp.rules.SolverRules;
import com.example.aps.cwp.validation.InputValidator;
import com.example.aps.cwp.validation.InputValidator.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ScheduleJobService {
    private final Map<String, ScheduleJob> jobs = new ConcurrentHashMap<String, ScheduleJob>();
    private final InputValidator validator;
    private final AlgorithmRegistry registry;
    private final SolverRuleService ruleService;
    private final Executor executor;

    public ScheduleJobService(InputValidator validator, AlgorithmRegistry registry, SolverRuleService ruleService,
                              @Qualifier("scheduleExecutor") Executor executor) {
        this.validator = validator; this.registry = registry; this.ruleService = ruleService; this.executor = executor;
    }

    public ScheduleJob create(final JsonNode input, final String algorithm) {
        final ValidationResult validation = validator.validate(input);
        final ScheduleJob job = new ScheduleJob(UUID.randomUUID().toString(), validation.getWarnings(), algorithm);
        jobs.put(job.getJobId(), job);
        try {
            // HTTP 请求只创建任务；真正的排程在专用线程池中异步执行，避免阻塞接口线程。
            executor.execute(new Runnable() {
                public void run() { solve(job, input.deepCopy(), algorithm); }
            });
        } catch (RuntimeException ex) {
            jobs.remove(job.getJobId());
            throw ex;
        }
        return job;
    }

    public ScheduleJob get(String jobId) {
        ScheduleJob job = jobs.get(jobId);
        if (job == null) throw new JobNotFoundException(jobId);
        return job;
    }

    public List<ScheduleJob> list() {
        List<ScheduleJob> result = new ArrayList<ScheduleJob>(jobs.values());
        Collections.sort(result, new Comparator<ScheduleJob>() {
            public int compare(ScheduleJob left, ScheduleJob right) {
                return right.getCreatedAt().compareTo(left.getCreatedAt());
            }
        });
        return result;
    }

    public JsonNode result(String jobId) {
        ScheduleJob job = get(jobId);
        if (job.getStatus() != JobStatus.COMPLETED) throw new JobNotReadyException(jobId, job.getStatus().name());
        return job.getResult();
    }

    /** 列出所有可用算法（供前端渲染下拉）。 */
    public List<ScheduleAlgorithm> algorithms() {
        return registry.list();
    }

    private void solve(ScheduleJob job, JsonNode input, String algorithm) {
        job.setStatus(JobStatus.SOLVING); job.setProgress(10); job.setStartedAt(OffsetDateTime.now());
        try {
            // 真实算法入口：按算法编码从注册表取出对应算法并求解，而不是读取静态结果或模拟延时。
            // 每个任务只读取一次规则快照，交互修改规则不会干扰正在求解的任务。
            SolverRules rules = ruleService.snapshot();
            ScheduleAlgorithm selected = registry.getOrDefault(algorithm);
            JsonNode result = selected.solve(input, job.getWarnings(), rules); job.setProgress(100);
            job.setResult(result); job.setStatus(JobStatus.COMPLETED);
        } catch (Exception ex) {
            job.setFailureReason(ex.getClass().getSimpleName() + ": " + ex.getMessage()); job.setStatus(JobStatus.FAILED);
        } finally { job.setCompletedAt(OffsetDateTime.now()); }
    }
}
