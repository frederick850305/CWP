package com.example.aps.cwp;

final class TestInputs {
    private TestInputs() { }

    /**
     * 单产能资源组 CWP 样例。字段 workloadPerPersonDay 已重新定义为"每单位工作量每天所需人数"
     * （人/单位工作量/天）；此处 0.01818 ≈ 1/55，等价于原"1 人 1 天干 55 平方米"的产能，使人力需求仍约为 2 人。
     */
    static String singleCapacityCwp() {
        return "{" +
                "\"optimizationObjectives\":{\"enabled\":true,\"objectives\":[]}," +
                "\"costModel\":{\"enabled\":true,\"scheduleDeviationCostPerDay\":1,\"lockViolationCostPerDay\":100," +
                "\"laborUnitCosts\":[{\"locationCode\":\"LOC1\",\"amountPerPersonDay\":10}]," +
                "\"resourceCostRates\":[{\"resourceGroupId\":\"RG1\",\"baselineUnitCost\":1,\"overtimeUnitCost\":2,\"occupancyUnitCostPerDay\":0,\"blockUnitCostPerDay\":0}]}," +
                "\"locationLaborConstraints\":{\"enabled\":true,\"locations\":[{\"locationCode\":\"LOC1\",\"locationName\":\"甲板片\",\"maxLaborPerDay\":10}]}," +
                "\"projects\":[{\"projectCode\":\"P1\",\"projectName\":\"项目\",\"plannedEnd\":\"2025-06-22T00:00:00+08:00\",\"finishHardConstraint\":true}]," +
                "\"resourceBindingPolicy\":{\"resourceGroups\":[{\"resourceGroupId\":\"RG1\",\"resourceGroupName\":\"甲板片资源\",\"consumptionMode\":\"CAPACITY\",\"substituteResourceGroupIds\":[],\"locationCode\":\"LOC1\",\"locationName\":\"甲板片\",\"capacity\":{\"baselineAmount\":2400,\"maxAmount\":2800,\"unit\":\"平方米\",\"timeUnit\":\"month\"}}]}," +
                "\"cwps\":[{\"cwpCode\":\"SB3100\",\"cwpName\":\"甲板片喷砂\",\"projectCode\":\"P1\",\"projectName\":\"项目\",\"projectPriority\":3," +
                "\"plannedStart\":\"2025-06-10T00:00:00+08:00\",\"plannedEnd\":\"2025-06-22T00:00:00+08:00\",\"isLocked\":false," +
                "\"workload\":{\"totalAmount\":1450,\"progress\":0.30,\"unit\":\"平方米\"},\"dependencies\":[]," +
                "\"processRoute\":{\"routeCode\":\"R1\",\"operations\":[{\"opCode\":\"OP1\",\"sequence\":10," +
                "\"laborNorm\":{\"workloadPerPersonDay\":0.01818,\"workloadUnit\":\"平方米\",\"laborUnit\":\"工日\"},\"resourceGroup\":{\"resourceGroupId\":\"RG1\"}}]}}]}";
    }
}
