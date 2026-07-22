package com.example.aps.cwp.engine;

import com.example.aps.cwp.rules.SolverRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 可插拔的排程算法。每个实现对应前端可选择的“算法”，由 {@link AlgorithmRegistry} 统一注册与路由。
 */
public interface ScheduleAlgorithm {

    /** 算法唯一编码（前端下拉与接口请求均使用该值）。 */
    String code();

    /** 算法中文展示名。 */
    String displayName();

    /** 算法一句话说明，用于在页面上解释差异。 */
    String description();

    /** 执行排程，返回完整的接口结果 JSON。 */
    JsonNode solve(JsonNode input, List<String> warnings, SolverRules rules);
}
