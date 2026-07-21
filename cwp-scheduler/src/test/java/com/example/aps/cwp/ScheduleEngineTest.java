package com.example.aps.cwp;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aps.cwp.engine.ScheduleEngine;
import com.example.aps.cwp.validation.InputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ScheduleEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void calculatesRemainingWorkloadAndBaselineUtilization() throws Exception {
        JsonNode input = mapper.readTree(TestInputs.singleCapacityCwp());
        new InputValidator().validate(input);
        SchedulerProperties properties = new SchedulerProperties();
        JsonNode result = new ScheduleEngine(mapper, properties).solve(input, Collections.<String>emptyList());

        JsonNode row = result.path("monthlyWorkshopUtilization").get(0);
        assertThat(row.path("usedAmount").decimalValue()).isEqualByComparingTo("1015");
        assertThat(row.path("utilizationRate").decimalValue()).isEqualByComparingTo("0.4229");
        assertThat(result.path("cwpGanttOutput").path("tasks").size()).isEqualTo(1);
        assertThat(result.path("monthlyLaborDemandCurve").get(0).path("byLocation").get(0)
                .path("demand").decimalValue()).isEqualByComparingTo("2");
    }
}
