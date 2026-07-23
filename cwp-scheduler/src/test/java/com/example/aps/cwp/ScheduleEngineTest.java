package com.example.aps.cwp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aps.cwp.engine.DefaultHeuristicStrategy;
import com.example.aps.cwp.engine.HeuristicScheduleEngine;
import com.example.aps.cwp.validation.InputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ScheduleEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void calculatesRemainingWorkloadAndBaselineUtilization() throws Exception {
        JsonNode input = mapper.readTree(TestInputs.singleCapacityCwp());
        new InputValidator().validate(input);
        SchedulerProperties properties = new SchedulerProperties();
        HeuristicScheduleEngine engine = new HeuristicScheduleEngine(mapper, properties,
                new DefaultHeuristicStrategy(), "default", "默认启发式", "");
        JsonNode result = engine.solve(input, Collections.<String>emptyList());

        JsonNode row = result.path("monthlyWorkshopUtilization").get(0);
        assertThat(row.path("usedAmount").decimalValue()).isEqualByComparingTo("1015");
        assertThat(row.path("utilizationRate").decimalValue()).isEqualByComparingTo("0.4229");
        assertThat(result.path("cwpGanttOutput").path("tasks").size()).isEqualTo(1);
        assertThat(result.path("monthlyLaborDemandCurve").get(0).path("byLocation").get(0)
                .path("demand").decimalValue()).isEqualByComparingTo("2");
    }

    @Test
    void zeroWorkloadCwpIsAZeroDurationNonConsumingFsNode() throws Exception {
        ObjectNode input = (ObjectNode) mapper.readTree(TestInputs.singleCapacityCwp());
        ArrayNode cwps = mapper.createArrayNode();
        ObjectNode predecessor = task(input.path("cwps").get(0), "PRE", "前置作业",
                "2025-06-10T00:00:00+08:00", "2025-06-11T00:00:00+08:00", 10);
        ObjectNode zero = task(input.path("cwps").get(0), "ZERO", "零工程量交接点",
                "2025-06-12T00:00:00+08:00", "2025-06-15T00:00:00+08:00", 0);
        zero.set("dependencies", fsDependency("PRE"));
        ObjectNode successor = task(input.path("cwps").get(0), "NEXT", "后续作业",
                "2025-06-12T00:00:00+08:00", "2025-06-13T00:00:00+08:00", 10);
        successor.set("dependencies", fsDependency("ZERO"));
        cwps.add(predecessor); cwps.add(zero); cwps.add(successor); input.set("cwps", cwps);

        new InputValidator().validate(input);
        SchedulerProperties properties = new SchedulerProperties();
        HeuristicScheduleEngine engine = new HeuristicScheduleEngine(mapper, properties,
                new DefaultHeuristicStrategy(), "default", "默认启发式", "");
        JsonNode result = engine.solve(input, Collections.<String>emptyList());

        JsonNode zeroTask = ganttTask(result, "ZERO");
        JsonNode nextTask = ganttTask(result, "NEXT");
        assertThat(zeroTask.path("zeroWorkload").asBoolean()).isTrue();
        assertThat(zeroTask.path("taskType").asText()).isEqualTo("ZERO_WORKLOAD");
        assertThat(zeroTask.path("consumesResources").asBoolean()).isFalse();
        assertThat(zeroTask.path("durationDays").asInt()).isZero();
        assertThat(zeroTask.path("allocatedResourceGroupId").asText()).isEmpty();
        assertThat(zeroTask.path("scheduledStart").asText().substring(0, 10)).isEqualTo("2025-06-12");
        assertThat(zeroTask.path("scheduledEnd").asText()).isEqualTo(zeroTask.path("scheduledStart").asText());
        assertThat(nextTask.path("scheduledStart").asText()).isEqualTo(zeroTask.path("scheduledStart").asText());
        assertThat(result.path("monthlyWorkshopUtilization").get(0).path("usedAmount").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(result.path("cwpGanttOutput").path("dependencyLinks").size()).isEqualTo(2);
        for (JsonNode link : result.path("cwpGanttOutput").path("dependencyLinks"))
            assertThat(link.path("involvesZeroWorkload").asBoolean()).isTrue();
    }

    private ObjectNode task(JsonNode source, String code, String name, String start, String end, int workload) {
        ObjectNode task = source.deepCopy();
        task.put("cwpCode", code); task.put("cwpName", name);
        task.put("plannedStart", start); task.put("plannedEnd", end);
        task.with("workload").put("totalAmount", workload).put("progress", 0);
        task.set("dependencies", mapper.createArrayNode());
        return task;
    }

    private ArrayNode fsDependency(String predecessor) {
        ArrayNode dependencies = mapper.createArrayNode();
        ObjectNode dependency = dependencies.addObject();
        dependency.put("predecessorCwpCode", predecessor);
        dependency.put("relation", "FS"); dependency.put("lag", 0); dependency.put("lagUnit", "day");
        return dependencies;
    }

    private JsonNode ganttTask(JsonNode result, String code) {
        for (JsonNode task : result.path("cwpGanttOutput").path("tasks"))
            if (code.equals(task.path("cwpCode").asText())) return task;
        throw new AssertionError("Missing Gantt task " + code);
    }
}
