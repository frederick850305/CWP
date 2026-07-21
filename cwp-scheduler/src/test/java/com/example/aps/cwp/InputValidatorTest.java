package com.example.aps.cwp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aps.cwp.api.ValidationException;
import com.example.aps.cwp.validation.InputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class InputValidatorTest {
    @Test
    void rejectsMultiOperationCwpWithoutRatios() throws Exception {
        String json = TestInputs.singleCapacityCwp().replace(
                "\"operations\":[{",
                "\"operations\":[{\"opCode\":\"OP-X\",\"sequence\":5,\"laborNorm\":{\"workloadPerPersonDay\":55,\"workloadUnit\":\"平方米\"},\"resourceGroup\":{\"resourceGroupId\":\"RG1\"}},{");
        assertThatThrownBy(() -> new InputValidator().validate(new ObjectMapper().readTree(json)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid CWP");
    }
}
