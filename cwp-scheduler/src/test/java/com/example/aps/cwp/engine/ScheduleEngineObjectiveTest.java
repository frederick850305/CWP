package com.example.aps.cwp.engine;

import com.example.aps.cwp.SchedulerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleEngineObjectiveTest {

    private JsonNode load(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource);
            return new ObjectMapper().readTree(in);
        }
    }

    private ScheduleEngine engine() {
        SchedulerProperties props = new SchedulerProperties();
        props.setZoneId("Asia/Shanghai");
        props.setTimeLimitSeconds(60);
        return new ScheduleEngine(new ObjectMapper(), props);
    }

    private JsonNode solve(JsonNode input) {
        List<String> warnings = new ArrayList<String>();
        return engine().solve(input, warnings);
    }

    @Test
    void objectivesAreConsumedEndToEnd() throws Exception {
        JsonNode input = load("/sample-input.json");
        assertTrue(input.path("optimizationObjectives").path("enabled").asBoolean(false),
                "sample must enable optimizationObjectives");
        assertEquals(3, input.path("optimizationObjectives").path("objectives").size(),
                "sample should declare 3 objectives");

        JsonNode out = solve(input);
        JsonNode summary = out.path("scheduleSummary");
        assertFalse(summary.isMissingNode(), "scheduleSummary missing");

        // 目标达成快照必须按声明顺序出现，且每个目标都有数值。
        JsonNode objs = summary.path("optimizationObjectives");
        assertFalse(objs.isMissingNode(), "optimizationObjectives snapshot missing in summary");
        assertEquals(3, objs.size(), "should snapshot 3 objectives");
        for (int i = 0; i < objs.size(); i++) {
            JsonNode o = objs.get(i);
            assertEquals(i + 1, o.path("rank").asInt(), "rank should be 1-based in order");
            assertFalse(o.path("value").isMissingNode(), "objective " + o.path("objectiveCode") + " has no value");
        }

        JsonNode metrics = summary.path("objectiveMetrics");
        assertFalse(metrics.isMissingNode(), "objectiveMetrics missing");
        assertTrue(metrics.path("projectPriorityScore").isNumber());
        assertTrue(metrics.path("avgWorkshopCapacityUtilization").isNumber());
        assertTrue(metrics.path("totalScheduleCost").isNumber());

        dump("OBJECTIVES-ENABLED", summary);
    }

    @Test
    void fallbackWorksWhenObjectivesDisabled() throws Exception {
        JsonNode input = load("/sample-input.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("optimizationObjectives"))
                .put("enabled", false);
        JsonNode out = solve(input);
        JsonNode summary = out.path("scheduleSummary");
        assertFalse(summary.isMissingNode(), "scheduleSummary missing in fallback");
        dump("OBJECTIVES-DISABLED", summary);
    }

    @Test
    void multiObjectiveShiftsScheduleToImproveUtilization() throws Exception {
        // 两个任务共用同一产能资源 G1(baseline=100,max=200)。
        // 禁用目标时：B 停在计划月(2月)，平均利用率≈0.8。
        // 启用目标(利用率最大化)时：B 应聚到 1 月与 A 同月，平均利用率≈1.6（偏离计划但提升利用率）。
        JsonNode input = buildSynthetic();

        JsonNode outEnabled = solve(input);
        JsonNode outDisabled = solve(((com.fasterxml.jackson.databind.node.ObjectNode) input.deepCopy())
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("optimizationObjectives",
                        ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("optimizationObjectives")).put("enabled", false)));

        BigDecimal utilEnabled = outEnabled.path("scheduleSummary").path("objectiveMetrics")
                .path("avgWorkshopCapacityUtilization").decimalValue();
        BigDecimal utilDisabled = outDisabled.path("scheduleSummary").path("objectiveMetrics")
                .path("avgWorkshopCapacityUtilization").decimalValue();

        String bEnabledMonth = scheduledMonth(outEnabled, "B");
        String bDisabledMonth = scheduledMonth(outDisabled, "B");
        System.out.println("\n[synth] B enabled month=" + bEnabledMonth + " disabled month=" + bDisabledMonth
                + " utilEnabled=" + utilEnabled + " utilDisabled=" + utilDisabled);

        dump("SYNTHETIC-ENABLED", outEnabled.path("scheduleSummary"));
        dump("SYNTHETIC-DISABLED", outDisabled.path("scheduleSummary"));

        assertTrue(utilEnabled.compareTo(utilDisabled) > 0,
                "enabled should yield higher utilization than disabled: " + utilEnabled + " vs " + utilDisabled);
        assertEquals("02", bDisabledMonth, "disabled: B should stay in planned month (February)");
        assertEquals("01", bEnabledMonth, "enabled: B should be pulled into January to boost utilization");
    }

    private JsonNode buildSynthetic() throws Exception {
        String json = "{"
            + "\"projects\":[{\"projectCode\":\"P1\",\"projectName\":\"P1\",\"plannedEnd\":\"2026-12-31T00:00:00+08:00\",\"finishHardConstraint\":true}],"
            + "\"resourceBindingPolicy\":{\"resourceGroups\":["
            + "{\"resourceGroupId\":\"G1\",\"resourceGroupName\":\"G1\",\"consumptionMode\":\"CAPACITY\",\"locationCode\":\"LOC1\",\"locationName\":\"LOC1\","
            + "\"capacity\":{\"unit\":\"人天\",\"baselineAmount\":100,\"maxAmount\":200},\"substituteResourceGroupIds\":[],\"regions\":[]}]},"
            + "\"locationLaborConstraints\":{\"locations\":[]},"
            + "\"optimizationObjectives\":{\"enabled\":true,\"objectives\":["
            + "{\"objectiveCode\":\"PROJECT_PRIORITY\",\"objectiveName\":\"项目优先级最高\",\"direction\":\"maximize\",\"metric\":\"projectPriorityScore\"},"
            + "{\"objectiveCode\":\"RESOURCE_UTILIZATION\",\"objectiveName\":\"资源利用率最大化\",\"direction\":\"maximize\",\"metric\":\"avgWorkshopCapacityUtilization\"},"
            + "{\"objectiveCode\":\"TOTAL_COST_MINIMIZATION\",\"objectiveName\":\"总成本最小\",\"direction\":\"minimize\",\"metric\":\"totalScheduleCost\"}]},"
            + "\"costModel\":{\"enabled\":true,\"scheduleDeviationCostPerDay\":1000,\"lockViolationCostPerDay\":0,"
            + "\"laborUnitCosts\":[],\"resourceCostRates\":[{\"resourceGroupId\":\"G1\",\"baselineUnitCost\":0,\"overtimeUnitCost\":0,\"occupancyUnitCostPerDay\":0,\"blockUnitCostPerDay\":0}]},"
            + "\"cwps\":["
            + "{\"cwpCode\":\"A\",\"cwpName\":\"A\",\"projectCode\":\"P1\",\"projectName\":\"P1\",\"projectPriority\":1,"
            + "\"plannedStart\":\"2026-01-05T00:00:00+08:00\",\"plannedEnd\":\"2026-01-09T00:00:00+08:00\",\"isLocked\":false,"
            + "\"workload\":{\"totalAmount\":80,\"progress\":0,\"unit\":\"人天\"},\"dependencies\":[],"
            + "\"processRoute\":{\"operations\":[{\"opCode\":\"OP1\",\"opName\":\"OP1\",\"sequence\":1,\"workloadRatio\":1,\"laborNorm\":{\"workloadPerPersonDay\":1},\"resourceGroup\":{\"resourceGroupId\":\"G1\"}}]}},"
            + "{\"cwpCode\":\"B\",\"cwpName\":\"B\",\"projectCode\":\"P1\",\"projectName\":\"P1\",\"projectPriority\":1,"
            + "\"plannedStart\":\"2026-02-02T00:00:00+08:00\",\"plannedEnd\":\"2026-02-06T00:00:00+08:00\",\"isLocked\":false,"
            + "\"workload\":{\"totalAmount\":80,\"progress\":0,\"unit\":\"人天\"},\"dependencies\":[],"
            + "\"processRoute\":{\"operations\":[{\"opCode\":\"OP1\",\"opName\":\"OP1\",\"sequence\":1,\"workloadRatio\":1,\"laborNorm\":{\"workloadPerPersonDay\":1},\"resourceGroup\":{\"resourceGroupId\":\"G1\"}}]}}"
            + "]}";
        return new ObjectMapper().readTree(json);
    }

    private String scheduledMonth(JsonNode out, String cwpCode) {
        for (JsonNode t : out.path("cwpGanttOutput").path("tasks")) {
            if (cwpCode.equals(t.path("cwpCode").asText())) {
                String s = t.path("scheduledStart").asText();
                return s.substring(5, 7);
            }
        }
        return "";
    }

    private void dump(String tag, JsonNode summary) {
        StringBuilder sb = new StringBuilder("\n===== ").append(tag).append(" =====\n");
        sb.append("feasible=").append(summary.path("feasible").asBoolean())
          .append("  algorithmStatus=").append(summary.path("algorithmStatus").asText()).append("\n");
        JsonNode objs = summary.path("optimizationObjectives");
        if (!objs.isMissingNode()) for (JsonNode o : objs) {
            sb.append("  [").append(o.path("rank").asInt()).append("] ")
              .append(o.path("objectiveCode").asText()).append(" (")
              .append(o.path("direction").asText()).append(") = ")
              .append(o.path("value")).append("\n");
        }
        JsonNode m = summary.path("objectiveMetrics");
        sb.append("  metrics: priorityScore=").append(m.path("projectPriorityScore").asInt())
          .append("  avgUtilization=").append(m.path("avgWorkshopCapacityUtilization").asText())
          .append("  totalCost=").append(m.path("totalScheduleCost").asText()).append("\n");
        System.out.println(sb);
    }
}
