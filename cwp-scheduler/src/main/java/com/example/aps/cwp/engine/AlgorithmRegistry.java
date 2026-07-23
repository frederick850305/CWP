package com.example.aps.cwp.engine;

import com.example.aps.cwp.SchedulerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 排程算法注册表：集中创建并维护所有可用算法，供前端下拉选择与服务端路由。
 *
 * <p>新增算法时，只需在此处追加一行 {@code new HeuristicScheduleEngine(...)} 并指定
 * {@code code / displayName / description / ScheduleStrategy} 即可，无需改动调用链。</p>
 */
@Component
public class AlgorithmRegistry {

    public static final String DEFAULT_CODE = "default";

    private final List<ScheduleAlgorithm> algorithms = new ArrayList<ScheduleAlgorithm>();
    private final Map<String, ScheduleAlgorithm> byCode = new LinkedHashMap<String, ScheduleAlgorithm>();

    public AlgorithmRegistry(ObjectMapper mapper, SchedulerProperties properties) {
        register(new HeuristicScheduleEngine(mapper, properties, new DefaultHeuristicStrategy(),
                "default", "默认启发式",
                "复刻原排程逻辑：尊重运行期排序规则，按计划偏差最小择优。"));
        register(new HeuristicScheduleEngine(mapper, properties, new PriorityFirstStrategy(),
                "priority-first", "优先级优先",
                "强制按项目优先级排序，并对超出项目完工日的候选施加重罚，优先保障高优先级项目交付。"));
        register(new HeuristicScheduleEngine(mapper, properties, new EarliestStartStrategy(),
                "earliest-start", "尽早开工",
                "在每个任务的可行窗口内尽早开始，以缩短整体工期为目标。"));
    }

    private void register(ScheduleAlgorithm algorithm) {
        algorithms.add(algorithm);
        byCode.put(algorithm.code(), algorithm);
    }

    /** 全部可用算法（保持注册顺序，便于前端稳定渲染）。 */
    public List<ScheduleAlgorithm> list() { return algorithms; }

    /** 按编码取算法；编码为空或未知时回退到默认算法，保证请求永远可执行。 */
    public ScheduleAlgorithm getOrDefault(String code) {
        if (code == null || code.isEmpty()) return byCode.get(DEFAULT_CODE);
        return byCode.getOrDefault(code, byCode.get(DEFAULT_CODE));
    }

    public boolean exists(String code) {
        return code != null && byCode.containsKey(code);
    }
}
