package com.example.aps.cwp.engine;

import com.example.aps.cwp.SchedulerProperties;
import com.example.aps.cwp.engine.Domain.AssemblyUnit;
import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Dependency;
import com.example.aps.cwp.engine.Domain.GridPlacement;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.OccupancyPlacement;
import com.example.aps.cwp.engine.Domain.Operation;
import com.example.aps.cwp.engine.Domain.Project;
import com.example.aps.cwp.engine.Domain.Region;
import com.example.aps.cwp.engine.Domain.ResourceGroup;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启发式排程引擎。
 *
 * <p>这里执行的不是示例数据回放或随机结果生成，而是一套确定性的启发式排程算法：
 * 先按依赖关系和业务优先级确定任务顺序，再为每个任务搜索可行日期，并在资源台账中
 * 校验月产能、每日人力、占用区域和总装网格。若严格约束下没有可行解，仍会输出一个
 * 带冲突代码的兜底方案，供前端诊断。</p>
 *
 * <p>引擎只负责“不变的骨架”（解析、依赖层级、资源落位、网格分配、结果组装）；真正因算法而异的
 * 排序与评分逻辑委托给 {@link ScheduleStrategy}。本类实现 {@link ScheduleAlgorithm}，
 * 由 {@link AlgorithmRegistry} 以“策略 + 算法元信息”组合注册，供前端按 {@code code} 选择。</p>
 */
public class HeuristicScheduleEngine implements ScheduleAlgorithm {
    private final ObjectMapper mapper;
    private final SchedulerProperties properties;
    private final ScheduleStrategy strategy;
    private final String code;
    private final String displayName;
    private final String description;

    public HeuristicScheduleEngine(ObjectMapper mapper, SchedulerProperties properties,
                                   ScheduleStrategy strategy, String code, String displayName, String description) {
        this.mapper = mapper;
        this.properties = properties;
        this.strategy = strategy;
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    @Override
    public String code() { return code; }

    @Override
    public String displayName() { return displayName; }

    @Override
    public String description() { return description; }

    public JsonNode solve(JsonNode input, List<String> warnings) {
        return solve(input, warnings, new SolverRules());
    }

    public JsonNode solve(JsonNode input, List<String> warnings, SolverRules rules) {
        long started = System.currentTimeMillis();
        ZoneId zone = ZoneId.of(properties.getZoneId());

        // 1. 将接口 JSON 转换成排程领域模型，并建立 CWP 编码索引。
        Model model = Domain.parse(input, zone);
        Map<String, Cwp> cwpByCode = new LinkedHashMap<String, Cwp>();
        for (Cwp c : model.cwps) cwpByCode.put(c.key, c);
        Set<String> criticalPlanned = plannedCriticalTasks(model, cwpByCode);
        Set<String> effectiveLocked = rules.isLockCriticalPredecessors()
                ? expandLockedPredecessors(model.cwps, cwpByCode, criticalPlanned)
                : explicitlyLocked(model.cwps);

        // Ledger 是本次求解的资源占用台账；所有候选方案都必须先通过台账校验。
        Ledger ledger = new Ledger(model, rules);
        Map<String, ScheduledTask> scheduled = new LinkedHashMap<String, ScheduledTask>();

        // 2. 保证前置任务优先，同时考虑锁定、项目优先级和计划开始时间（顺序细节由策略决定）。
        List<Cwp> ordered = topologicalOrder(model.cwps, cwpByCode, effectiveLocked, rules, strategy);
        long deadline = started + properties.getTimeLimitSeconds() * 1000L;
        for (Cwp cwp : ordered) {
            ScheduledTask task;
            if (effectiveLocked.contains(cwp.key)) {
                // 锁定任务固定在原计划日期；资源冲突时保留日期并记录冲突原因。
                task = candidate(cwp, cwp.plannedStart, model);
                if (!place(task, model, ledger, true, rules)) {
                    if (!rules.isDiagnosticFallback())
                        throw new IllegalStateException("Locked task has no feasible resource: " + cwp.key);
                    task = candidate(cwp, cwp.plannedStart, model);
                    markForced(task, "LOCKED_RESOURCE_CONFLICT");
                    place(task, model, ledger, false, rules);
                }
            } else {
                // 非锁定任务在允许的日期窗口中搜索最优（策略决定“最优”）的可行位置。
                task = findBestCandidate(cwp, model, ledger, scheduled, cwpByCode, deadline, rules);
                if (task == null) {
                    if (!rules.isDiagnosticFallback())
                        throw new IllegalStateException("No feasible schedule window for task: " + cwp.key);
                    // 无严格可行窗口时输出可解释的兜底结果，而不是静默丢弃任务。
                    LocalDate fallback = dependencyAdjustedStart(cwp, cwp.plannedStart, scheduled, cwpByCode);
                    task = candidate(cwp, fallback, model);
                    markForced(task, "NO_FEASIBLE_WINDOW");
                    place(task, model, ledger, false, rules);
                }
            }
            scheduled.put(cwp.key, task);
        }

        // 3. CWP 日期确定后，再为单体分配总装阶段、工位和网格区域。
        allocateAssemblyUnits(model, scheduled, ledger);

        // 4. 复核工期与依赖约束，并组装接口返回的排程结果和统计指标。
        applyTemporalViolations(model, scheduled, cwpByCode);
        ObjectNode output = new OutputBuilder(mapper, zone).build(model, scheduled, ledger, warnings,
                System.currentTimeMillis() - started);
        output.set("solverRuleSnapshot", mapper.valueToTree(rules.toMap()));
        output.put("algorithm", code);
        output.put("algorithmDisplayName", displayName);
        return output;
    }

    private ScheduledTask findBestCandidate(Cwp cwp, Model model, Ledger ledger,
                                            Map<String, ScheduledTask> scheduled,
                                            Map<String, Cwp> cwpByCode, long deadlineMillis, final SolverRules rules) {
        Project project = model.projects.get(cwp.projectCode);
        LocalDate latest = project.plannedEnd.minusDays(schedulingSpanDays(cwp));
        LocalDate earliest = model.horizonStart;
        LocalDate dependencyStart = dependencyAdjustedStart(cwp, earliest, scheduled, cwpByCode);
        if (dependencyStart.isAfter(earliest)) earliest = dependencyStart;
        if (latest.isBefore(earliest)) return null;

        // 候选日期按策略定义的顺序搜索（如：偏离原计划天数 / 尽早开工）。
        List<LocalDate> candidates = new ArrayList<LocalDate>();
        for (LocalDate d = earliest; !d.isAfter(latest); d = d.plusDays(1)) candidates.add(d);
        Collections.sort(candidates, strategy.candidateDateComparator(cwp, rules));

        ScheduledTask best = null;
        Ledger bestTrial = null;
        for (LocalDate start : candidates) {
            if (System.currentTimeMillis() >= deadlineMillis) break;
            ScheduledTask candidateTask = candidate(cwp, start, model);

            // 在台账副本上试排，避免失败候选污染已经确认的资源占用。
            Ledger trial = ledger.copy();
            if (!place(candidateTask, model, trial, true, rules)) continue;
            if (best == null || strategy.compareCandidates(candidateTask, best, trial, bestTrial, model) < 0) {
                best = candidateTask; bestTrial = trial;
                if (strategy.earlyStop(candidateTask, trial, model)) break;
            }
        }

        // 只有最优候选最终写入正式资源台账。
        if (best != null) place(best, model, ledger, true, rules);
        return best;
    }

    /** 构造一个以 start 为起点的候选任务（含工序到资源组的绑定）。 */
    private ScheduledTask candidate(Cwp cwp, LocalDate start, Model model) {
        ScheduledTask task = new ScheduledTask(); task.cwp = cwp; task.start = start;
        // 零工程量 CWP 是零工期节点：用同一天的起止日期承载甘特图锚点，
        // 但依赖计算不会把它当作一个占用日。
        task.end = cwp.zeroWorkload ? start : start.plusDays(cwp.duration - 1L);
        for (Operation op : cwp.operations) task.operationResource.put(op.code, op.resourceGroupId);
        return task;
    }

    /** 将候选任务实际落位到台账：选资源(含替代)、校验人力与占用，并登记占用。strict=false 时仅记录冲突不拒绝。 */
    private boolean place(ScheduledTask task, Model model, Ledger ledger, boolean strict, SolverRules rules) {
        if (task.cwp.zeroWorkload) {
            // 零工程量节点只参与日期与依赖计算，不选择或占用任何资源。
            task.operationResource.clear();
            ledger.reserveTask(task);
            return true;
        }
        Map<String, String> chosen = new LinkedHashMap<String, String>();
        for (Operation op : task.cwp.operations) {
            ResourceGroup primary = model.groups.get(op.resourceGroupId);

            // 严格按 JSON 中声明的 substituteResourceGroupIds 作为替代资源候选（按声明顺序尝试）。
            // 不再使用“同单位即可跨模式替代”的通用判定：只有显式配置为替代组的资源才被允许替代。
            List<ResourceGroup> choices = new ArrayList<ResourceGroup>(); choices.add(primary);
            if (rules.isAllowResourceSubstitution()) {
                for (String sid : primary.substitutes) {
                    ResourceGroup substitute = model.groups.get(sid);
                    if (substitute != null) choices.add(substitute);
                }
            }
            ResourceGroup selected = null;
            for (ResourceGroup choice : choices) {
                if (ledger.canUseOperation(task, op, choice, strict)) { selected = choice; break; }
            }
            if (selected == null) {
                if (strict) return false;
                selected = primary; markForced(task, "RESOURCE_LIMIT");
            }
            chosen.put(op.code, selected.id);
        }
        task.operationResource.clear(); task.operationResource.putAll(chosen);
        if (!ledger.canUseLabor(task, strict)) {
            if (strict) return false;
            markForced(task, "LABOR_LIMIT");
        }

        // OCCUPANCY_RATIO 资源还需要在某个具体区域内连续占用整个任务周期。
        for (Operation op : task.cwp.operations) {
            ResourceGroup g = model.groups.get(chosen.get(op.code));
            if ("OCCUPANCY_RATIO".equals(g.mode) && task.occupancy == null) {
                OccupancyPlacement placement = ledger.findOccupancy(task, g, strict);
                if (placement == null && strict) return false;
                if (placement == null) markForced(task, "OCCUPANCY_LIMIT");
                task.occupancy = placement;
            }
        }
        ledger.reserveTask(task);
        return true;
    }

    /** 为总装单体分配阶段、工位与网格区域（CWP 日期确定后执行）。 */
    private void allocateAssemblyUnits(Model model, Map<String, ScheduledTask> scheduled, Ledger ledger) {
        // 同一单体下的多个 CWP 共用一块总装网格，占用周期取这些 CWP 的最早开始至最晚结束。
        Map<String, List<ScheduledTask>> units = new LinkedHashMap<String, List<ScheduledTask>>();
        for (ScheduledTask task : scheduled.values()) if (!task.cwp.zeroWorkload && task.cwp.unit != null) {
            List<ScheduledTask> list = units.get(task.cwp.unit.code);
            if (list == null) { list = new ArrayList<ScheduledTask>(); units.put(task.cwp.unit.code, list); }
            list.add(task);
        }
        for (List<ScheduledTask> unitTasks : units.values()) {
            LocalDate start = unitTasks.get(0).start, end = unitTasks.get(0).end;
            for (ScheduledTask t : unitTasks) { if (t.start.isBefore(start)) start = t.start; if (t.end.isAfter(end)) end = t.end; }
            Cwp representative = unitTasks.get(0).cwp;
            ResourceGroup group = null;
            for (Operation op : representative.operations) {
                ResourceGroup candidateGroup = model.groups.get(unitTasks.get(0).operationResource.get(op.code));
                if (candidateGroup != null && "GRID_BLOCK".equals(candidateGroup.mode)) { group = candidateGroup; break; }
            }
            if (group == null) continue;

            // 采用 first-fit：依次扫描阶段、行、列，找到整个周期内均未被占用的矩形区域。
            GridPlacement placement = ledger.findAndReserveGrid(group, representative.unit, start, end);
            for (ScheduledTask task : unitTasks) {
                task.grid = placement;
                if (placement == null) markForced(task, "GRID_LIMIT");
            }
        }
    }

    /** 复核工期与依赖约束，对不满足者标记冲突代码。 */
    private void applyTemporalViolations(Model model, Map<String, ScheduledTask> scheduled, Map<String, Cwp> cwps) {
        for (ScheduledTask task : scheduled.values()) {
            Project project = model.projects.get(task.cwp.projectCode);
            if (project.finishHardConstraint && task.end.isAfter(project.plannedEnd)) markForced(task, "PROJECT_DEADLINE");
            for (Dependency d : task.cwp.dependencies) {
                ScheduledTask pred = scheduled.get(d.predecessor);
                if (pred != null && !dependencySatisfied(pred, task, d)) markForced(task, "DEPENDENCY_" + d.relation);
            }
        }
    }

    /** 根据前置任务的排程结果，将初始开始日顺延到满足所有依赖约束的最早日期。 */
    private LocalDate dependencyAdjustedStart(Cwp cwp, LocalDate initial,
                                              Map<String, ScheduledTask> scheduled, Map<String, Cwp> cwpByCode) {
        LocalDate result = initial;
        for (Dependency d : cwp.dependencies) {
            ScheduledTask pred = scheduled.get(d.predecessor);
            if (pred == null) continue;
            LocalDate required;
            // 支持 FS、SS、FF、SF 四种依赖关系以及正负时滞。
            if ("FS".equals(d.relation)) required = pred.end.plusDays(d.lag + fsBoundaryDays(pred.cwp));
            else if ("SS".equals(d.relation)) required = pred.start.plusDays(d.lag);
            else if ("FF".equals(d.relation)) required = pred.end.plusDays(d.lag).minusDays(schedulingSpanDays(cwp));
            else required = pred.start.plusDays(d.lag).minusDays(schedulingSpanDays(cwp));
            if (required.isAfter(result)) result = required;
        }
        return result;
    }

    static boolean dependencySatisfied(ScheduledTask pred, ScheduledTask succ, Dependency d) {
        if ("FS".equals(d.relation)) return !succ.start.isBefore(pred.end.plusDays(d.lag + fsBoundaryDays(pred.cwp)));
        if ("SS".equals(d.relation)) return !succ.start.isBefore(pred.start.plusDays(d.lag));
        if ("FF".equals(d.relation)) return !succ.end.isBefore(pred.end.plusDays(d.lag));
        return !succ.end.isBefore(pred.start.plusDays(d.lag));
    }

    /** 普通任务按自然日闭区间排程，FS 后继从下一天开始；零工期节点不额外占一天。 */
    private static long fsBoundaryDays(Cwp predecessor) {
        return predecessor.zeroWorkload ? 0L : 1L;
    }

    /** 任务起止为闭区间；零工程量节点跨度为 0，其余任务跨度为 duration-1。 */
    private static long schedulingSpanDays(Cwp cwp) {
        return cwp.zeroWorkload ? 0L : cwp.duration - 1L;
    }

    /**
     * 计算任务排程顺序：依赖层级优先，再按锁定状态，最后由策略决定同层级内的业务次序。
     */
    private List<Cwp> topologicalOrder(List<Cwp> all, Map<String, Cwp> byCode,
                                       final Set<String> locked, final SolverRules rules, final ScheduleStrategy strategy) {
        // depth 表示依赖层级，确保前置 CWP 在其后继任务之前被排入资源台账。
        final Map<String, Integer> depth = new HashMap<String, Integer>();
        for (Cwp c : all) computeDepth(c, byCode, depth);
        List<Cwp> result = new ArrayList<Cwp>(all);
        final Comparator<Cwp> business = strategy.orderComparator(locked, rules);
        Collections.sort(result, new Comparator<Cwp>() {
            public int compare(Cwp a, Cwp b) {
                int d = Integer.compare(depth.get(a.key), depth.get(b.key));
                if (d != 0) return d;
                int l = Boolean.compare(locked.contains(b.key), locked.contains(a.key));
                if (l != 0) return l;
                return business.compare(a, b);
            }
        });
        return result;
    }

    /** 递归计算 CWP 的依赖深度（最长前置链长度），带缓存避免重复计算。 */
    private int computeDepth(Cwp c, Map<String, Cwp> byCode, Map<String, Integer> cache) {
        Integer existing = cache.get(c.key); if (existing != null) return existing;
        int depth = 0;
        for (Dependency d : c.dependencies) if (byCode.containsKey(d.predecessor))
            depth = Math.max(depth, 1 + computeDepth(byCode.get(d.predecessor), byCode, cache));
        cache.put(c.key, depth); return depth;
    }

    /** 基于计划日期识别零浮时(计划关键)任务，用于锁定传播。内部排序用默认策略，避免受所选算法影响。 */
    private Set<String> plannedCriticalTasks(Model model, Map<String, Cwp> byCode) {
        // 从项目计划结束日反向推算每个 CWP 的最晚完成时间；零浮时任务视为计划关键任务。
        Map<String, LocalDate> latestEnd = new HashMap<String, LocalDate>();
        for (Cwp c : model.cwps) latestEnd.put(c.key, model.projects.get(c.projectCode).plannedEnd);
        List<Cwp> reverse = topologicalOrder(model.cwps, byCode, Collections.<String>emptySet(), new SolverRules(), new DefaultHeuristicStrategy());
        Collections.reverse(reverse);
        for (int iteration = 0; iteration < reverse.size(); iteration++) for (Cwp succ : reverse) {
            LocalDate succEnd = latestEnd.get(succ.key);
            LocalDate succStart = succEnd.minusDays(schedulingSpanDays(succ));
            for (Dependency d : succ.dependencies) {
                Cwp pred = byCode.get(d.predecessor); if (pred == null) continue;
                LocalDate bound;
                if ("FS".equals(d.relation)) bound = succStart.minusDays(d.lag + fsBoundaryDays(pred));
                else if ("SS".equals(d.relation)) bound = succStart.minusDays(d.lag).plusDays(schedulingSpanDays(pred));
                else if ("FF".equals(d.relation)) bound = succEnd.minusDays(d.lag);
                else bound = succEnd.minusDays(d.lag).plusDays(schedulingSpanDays(pred));
                if (bound.isBefore(latestEnd.get(pred.key))) latestEnd.put(pred.key, bound);
            }
        }
        Set<String> critical = new HashSet<String>();
        for (Cwp c : model.cwps) {
            LocalDate latestStart = latestEnd.get(c.key).minusDays(schedulingSpanDays(c));
            if (latestStart.equals(c.plannedStart)) critical.add(c.code);
        }
        return critical;
    }

    /** 若关键任务被锁定，则把其全部前置链一并锁定以保稳定。 */
    private Set<String> expandLockedPredecessors(List<Cwp> cwps, Map<String, Cwp> byCode, Set<String> critical) {
        // 若关键任务被锁定，其全部前置链也要一起保持稳定，避免锁定任务因依赖而失效。
        Set<String> result = new LinkedHashSet<String>();
        for (Cwp c : cwps) if (c.locked) {
            result.add(c.key);
            if (critical.contains(c.key)) addPredecessors(c, byCode, result);
        }
        return result;
    }

    /** 直接以 isLocked 标记的任务集合作为锁定集合。 */
    private Set<String> explicitlyLocked(List<Cwp> cwps) {
        Set<String> result = new LinkedHashSet<String>();
        for (Cwp cwp : cwps) if (cwp.locked) result.add(cwp.key);
        return result;
    }

    /** 递归收集 CWP 的全部前驱（深度优先）。 */
    private void addPredecessors(Cwp c, Map<String, Cwp> byCode, Set<String> result) {
        for (Dependency d : c.dependencies) {
            Cwp pred = byCode.get(d.predecessor);
            if (pred != null && result.add(pred.key)) addPredecessors(pred, byCode, result);
        }
    }

    /** 将任务标记为带冲突，并记录具体的冲突代码。 */
    private void markForced(ScheduledTask task, String code) {
        task.withConflict = true;
        if (!task.violationCodes.contains(code)) task.violationCodes.add(code);
    }
}
