package com.example.aps.cwp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.aps.cwp.job.JobStatus;
import com.example.aps.cwp.job.ScheduleJob;
import com.example.aps.cwp.service.ScheduleJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ScheduleApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ScheduleJobService service;

    @Test
    void createsAndReturnsAsynchronousResult() throws Exception {
        String response = mvc.perform(post("/api/v1/cwp-schedule-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(TestInputs.singleCapacityCwp()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = mapper.readTree(response).path("jobId").asText();
        ScheduleJob job = service.get(jobId);
        for (int i = 0; i < 100 && job.getStatus() != JobStatus.COMPLETED; i++) Thread.sleep(10L);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        mvc.perform(get("/api/v1/cwp-schedule-jobs/{jobId}/result", jobId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cwp-schedule-jobs/{jobId}/result/download", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString(jobId)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scheduleSummary").exists());
    }

    @Test
    void listsAlgorithmsAndRoutesByCode() throws Exception {
        // 算法清单接口：应返回 3 个已注册算法（成本最优已移除，成本不作为排程条件）。
        mvc.perform(get("/api/v1/cwp-schedule-jobs/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("default"))
                .andExpect(jsonPath("$[?(@.code == 'priority-first')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'earliest-start')]").exists());

        // 方案 B：用 { algorithm, input } 包裹提交，结果应回显所用算法。
        String body = "{\"algorithm\":\"priority-first\",\"input\":" + TestInputs.singleCapacityCwp() + "}";
        String response = mvc.perform(post("/api/v1/cwp-schedule-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.algorithm").value("priority-first"))
                .andReturn().getResponse().getContentAsString();
        String jobId = mapper.readTree(response).path("jobId").asText();
        ScheduleJob job = service.get(jobId);
        for (int i = 0; i < 100 && job.getStatus() != JobStatus.COMPLETED; i++) Thread.sleep(10L);
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        mvc.perform(get("/api/v1/cwp-schedule-jobs/{jobId}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("priority-first"))
                .andExpect(jsonPath("$.algorithmDisplayName").exists());

        // 未知算法应回退到默认算法，仍可正常完成。
        String fallback = "{\"algorithm\":\"unknown-algo\",\"input\":" + TestInputs.singleCapacityCwp() + "}";
        String fallbackResponse = mvc.perform(post("/api/v1/cwp-schedule-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(fallback))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.algorithm").value("default"))
                .andReturn().getResponse().getContentAsString();
        String fallbackJobId = mapper.readTree(fallbackResponse).path("jobId").asText();
        ScheduleJob fallbackJob = service.get(fallbackJobId);
        for (int i = 0; i < 100 && fallbackJob.getStatus() != JobStatus.COMPLETED; i++) Thread.sleep(10L);
        assertThat(fallbackJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void returns404And400() throws Exception {
        mvc.perform(get("/api/v1/cwp-schedule-jobs")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cwp-schedule-jobs/missing")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/cwp-schedule-jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewsAndAppliesNaturalLanguageSolverRules() throws Exception {
        String preview = mvc.perform(post("/api/v1/solver-rules/interpret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"产能利用率上限设为90%，禁止使用替代资源\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.proposedRules.capacitySafetyFactor").value(0.9))
                .andReturn().getResponse().getContentAsString();
        String proposalId = mapper.readTree(preview).path("proposalId").asText();

        mvc.perform(post("/api/v1/solver-rules/proposals/{proposalId}/apply", proposalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.version").value(2))
                .andExpect(jsonPath("$.rules.allowResourceSubstitution").value(false));
        mvc.perform(get("/api/v1/solver-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1));

        String response = mvc.perform(post("/api/v1/cwp-schedule-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(TestInputs.singleCapacityCwp()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = mapper.readTree(response).path("jobId").asText();
        ScheduleJob job = service.get(jobId);
        for (int i = 0; i < 100 && job.getStatus() != JobStatus.COMPLETED; i++) Thread.sleep(10L);
        mvc.perform(get("/api/v1/cwp-schedule-jobs/{jobId}/result", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solverRuleSnapshot.version").value(2))
                .andExpect(jsonPath("$.solverRuleSnapshot.capacitySafetyFactor").value(0.9));
    }
}
