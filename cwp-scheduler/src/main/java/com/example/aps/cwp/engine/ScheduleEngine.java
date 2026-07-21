package com.example.aps.cwp.engine;

import com.example.aps.cwp.SchedulerProperties;
import com.example.aps.cwp.engine.Domain.AssemblyUnit;
import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Dependency;
import com.example.aps.cwp.engine.Domain.GridPlacement;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.OccupancyPlacement;
import com.example.aps.cwp.engine.Domain.Operation;
import com.example.aps.cwp.engine.Domain.Phase;
import com.example.aps.cwp.engine.Domain.Project;
import com.example.aps.cwp.engine.Domain.Region;
import com.example.aps.cwp.engine.Domain.ResourceGroup;
import com.example.aps.cwp.engine.Domain.ResourceRate;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.springframework.stereotype.Component;

/**
 * CWP 排程求解器。
 *
 * <p>这里执行的不是示例数据回放或随机结果生成，而是一套确定性的启发式排程算法：
 * 先按依赖关系和业务优先级确定任务顺序，再为每个任务搜索可行日期，并在资源台账中
 * 校验月产能、每日人力、占用区域和总装网格。若严格约束下没有可行解，仍会输出一个
 * 带冲突代码的兜底方案，供前端诊断。</p>
 */
@Component
public class ScheduleEngine {
    private final ObjectMapper mapper;
    private final SchedulerProperties properties;

    public ScheduleEngine(ObjectMapper mapper, SchedulerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public JsonNode solve(JsonNode input, List<String> warnings) {
        return solve(input, warnings, new SolverRules());
    }

    public JsonNode solve(JsonNode input, List<String> warnings, SolverRules rules) {
        long started = System.currentTimeMillis();
        ZoneId zone = ZoneId.of(properties.getZoneId());

        // 1. 将接口 JSON 转换成排程领域模型，并建立 CWP 编码索引。
        Model model = Domain.parse(input, zone);
        Map<String, Cwp> cwpByCode = new LinkedHashMap<String, Cwp>();
        for (Cwp c : model.cwps) cwpByCode.put(c.code, c);
        Set<String> criticalPlanned = plannedCriticalTasks(model, cwpByCode);
        Set<String> effectiveLocked = rules.isLockCriticalPredecessors()
                ? expandLockedPredecessors(model.cwps, cwpByCode, criticalPlanned)
                : explicitlyLocked(model.cwps);

        // Ledger 是本次求解的资源占用台账；所有候选方案都必须先通过台账校验。
        Ledger ledger = new Ledger(model, rules);
        Map<String, ScheduledTask> scheduled = new LinkedHashMap<String, ScheduledTask>();

        // 2. 保证前置任务优先，同时考虑锁定、项目优先级和计划开始时间。
        List<Cwp> ordered = topologicalOrder(model.cwps, cwpByCode, effectiveLocked, rules);
        long deadline = started + properties.getTimeLimitSeconds() * 1000L;
        for (Cwp cwp : ordered) {
            ScheduledTask task;
            if (effectiveLocked.contains(cwp.code)) {
                // 锁定任务固定在原计划日期；资源冲突时保留日期并记录冲突原因。
                task = candidate(cwp, cwp.plannedStart, model);
                if (!place(task, model, ledger, true, rules)) {
                    if (!rules.isDiagnosticFallback())
                        throw new IllegalStateException("Locked task has no feasible resource: " + cwp.code);
                    task = candidate(cwp, cwp.plannedStart, model);
                    markForced(task, "LOCKED_RESOURCE_CONFLICT");
                    place(task, model, ledger, false, rules);
                }
            } else {
                // 非锁定任务在允许的日期窗口中搜索增量成本最低的可行位置。
                task = findBestCandidate(cwp, model, ledger, scheduled, cwpByCode, deadline, rules);
                if (task == null) {
                    if (!rules.isDiagnosticFallback())
                        throw new IllegalStateException("No feasible schedule window for task: " + cwp.code);
                    // 无严格可行窗口时输出可解释的兜底结果，而不是静默丢弃任务。
                    LocalDate fallback = dependencyAdjustedStart(cwp, cwp.plannedStart, scheduled, cwpByCode);
                    task = candidate(cwp, fallback, model);
                    markForced(task, "NO_FEASIBLE_WINDOW");
                    place(task, model, ledger, false, rules);
                }
            }
            scheduled.put(cwp.code, task);
        }

        // 3. CWP 日期确定后，再为单体分配总装阶段、工位和网格区域。
        allocateAssemblyUnits(model, scheduled, ledger);

        // 4. 复核工期与依赖约束，并组装接口返回的排程结果和统计指标。
        applyTemporalViolations(model, scheduled, cwpByCode);
        ObjectNode output = new OutputBuilder(mapper, zone).build(model, scheduled, ledger, warnings,
                System.currentTimeMillis() - started);
        output.set("solverRuleSnapshot", mapper.valueToTree(rules.toMap()));
        return output;
    }

    private ScheduledTask findBestCandidate(Cwp cwp, Model model, Ledger ledger,
                                            Map<String, ScheduledTask> scheduled,
                                            Map<String, Cwp> cwpByCode, long deadlineMillis, final SolverRules rules) {
        Project project = model.projects.get(cwp.projectCode);
        LocalDate latest = project.plannedEnd.minusDays(cwp.duration - 1L);
        LocalDate earliest = model.horizonStart;
        LocalDate dependencyStart = dependencyAdjustedStart(cwp, earliest, scheduled, cwpByCode);
        if (dependencyStart.isAfter(earliest)) earliest = dependencyStart;
        if (latest.isBefore(earliest)) return null;

        // 候选日期优先按“偏离原计划的天数”排序，相同偏差时优先较早日期。
        List<LocalDate> candidates = new ArrayList<LocalDate>();
        for (LocalDate d = earliest; !d.isAfter(latest); d = d.plusDays(1)) candidates.add(d);
        Collections.sort(candidates, new Comparator<LocalDate>() {
            public int compare(LocalDate a, LocalDate b) {
                long da = Math.abs(ChronoUnit.DAYS.between(cwp.plannedStart, a));
                long db = Math.abs(ChronoUnit.DAYS.between(cwp.plannedStart, b));
                int byDistance = Long.compare(da, db);
                if (byDistance != 0) return byDistance;
                return rules.isPreferEarlierOnTie() ? a.compareTo(b) : b.compareTo(a);
            }
        });
        ScheduledTask best = null;
        BigDecimal bestCost = null;
        List<BigDecimal> bestVec = null;
        boolean multiObjective = model.objectivesEnabled && !model.objectives.isEmpty();
        for (LocalDate start : candidates) {
            if (System.currentTimeMillis() >= deadlineMillis) break;
            ScheduledTask candidate = candidate(cwp, start, model);

            // 在台账副本上试排，避免失败候选污染已经确认的资源占用。
            Ledger trial = ledger.copy();
            if (!place(candidate, model, trial, true, rules)) continue;
            if (multiObjective) {
                // 按 optimizationObjectives 定义的词典序逐目标比较候选（高优先级目标先决胜负）。
                List<BigDecimal> vec = objectiveVector(trial, candidate, model);
                if (best == null || lexicographicBetter(vec, bestVec) > 0) {
                    best = candidate; bestVec = vec;
                }
            } else {
                BigDecimal cost = trial.incrementalCost(candidate);
                if (best == null || cost.compareTo(bestCost) < 0) {
                    best = candidate; bestCost = cost;
                    if (start.equals(cwp.plannedStart) && cost.compareTo(BigDecimal.ZERO) == 0) break;
                }
            }
        }

        // 只有最优候选最终写入正式资源台账。
        if (best != null) place(best, model, ledger, true, rules);
        return best;
    }

    private List<BigDecimal> objectiveVector(Ledger trial, ScheduledTask cand, Model model) {
        // 将候选在试排台账上的表现映射为目标向量；MINIMIZE 方向取相反数，使“越大越好”统一。
        List<BigDecimal> vec = new ArrayList<BigDecimal>();
        for (Domain.Objective o : model.objectives) {
            BigDecimal v;
            if ("projectPriorityScore".equals(o.metric)) v = BigDecimal.valueOf(trial.priorityContribution(cand, model));
            else if ("avgWorkshopCapacityUtilization".equals(o.metric)) v = trial.avgUtilization();
            else if ("totalScheduleCost".equals(o.metric)) v = trial.costOf(cand, model);
            else v = BigDecimal.ZERO;
            if (o.direction != null && o.direction.trim().equalsIgnoreCase("minimize")) v = v.negate();
            vec.add(v);
        }
        return vec;
    }

    private static int lexicographicBetter(List<BigDecimal> a, List<BigDecimal> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) { int c = a.get(i).compareTo(b.get(i)); if (c != 0) return c; }
        return Integer.compare(a.size(), b.size());
    }

    /** 构造一个以 start 为起点的候选任务（含工序到资源组的绑定）。 */
    private ScheduledTask candidate(Cwp cwp, LocalDate start, Model model) {
        ScheduledTask task = new ScheduledTask(); task.cwp = cwp; task.start = start;
        task.end = start.plusDays(cwp.duration - 1L);
        for (Operation op : cwp.operations) task.operationResource.put(op.code, op.resourceGroupId);
        return task;
    }

    /** 将候选任务实际落位到台账：选资源(含替代)、校验人力与占用，并登记占用。strict=false 时仅记录冲突不拒绝。 */
    private boolean place(ScheduledTask task, Model model, Ledger ledger, boolean strict, SolverRules rules) {
        Map<String, String> chosen = new LinkedHashMap<String, String>();
        for (Operation op : task.cwp.operations) {
            ResourceGroup primary = model.groups.get(op.resourceGroupId);

            // 先尝试主资源；主资源不足时，按配置顺序尝试模式和单位兼容的替代资源。
            List<ResourceGroup> choices = new ArrayList<ResourceGroup>(); choices.add(primary);
            if (rules.isAllowResourceSubstitution()) {
                for (String sid : primary.substitutes) {
                    ResourceGroup substitute = model.groups.get(sid);
                    if (substitute != null && compatible(primary, substitute)) choices.add(substitute);
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
        for (ScheduledTask task : scheduled.values()) if (task.cwp.unit != null) {
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
                ResourceGroup candidate = model.groups.get(unitTasks.get(0).operationResource.get(op.code));
                if (candidate != null && "GRID_BLOCK".equals(candidate.mode)) { group = candidate; break; }
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
            if ("FS".equals(d.relation)) required = pred.end.plusDays(d.lag + 1L);
            else if ("SS".equals(d.relation)) required = pred.start.plusDays(d.lag);
            else if ("FF".equals(d.relation)) required = pred.end.plusDays(d.lag).minusDays(cwp.duration - 1L);
            else required = pred.start.plusDays(d.lag).minusDays(cwp.duration - 1L);
            if (required.isAfter(result)) result = required;
        }
        return result;
    }

    static boolean dependencySatisfied(ScheduledTask pred, ScheduledTask succ, Dependency d) {
        if ("FS".equals(d.relation)) return !succ.start.isBefore(pred.end.plusDays(d.lag + 1L));
        if ("SS".equals(d.relation)) return !succ.start.isBefore(pred.start.plusDays(d.lag));
        if ("FF".equals(d.relation)) return !succ.end.isBefore(pred.end.plusDays(d.lag));
        return !succ.end.isBefore(pred.start.plusDays(d.lag));
    }

    /** 计算任务排程顺序：依赖层级优先，再按锁定/优先级/计划日排序。 */
    private List<Cwp> topologicalOrder(List<Cwp> all, Map<String, Cwp> byCode,
                                       final Set<String> locked, final SolverRules rules) {
        // depth 表示依赖层级，确保前置 CWP 在其后继任务之前被排入资源台账。
        final Map<String, Integer> depth = new HashMap<String, Integer>();
        for (Cwp c : all) computeDepth(c, byCode, depth);
        List<Cwp> result = new ArrayList<Cwp>(all);
        Collections.sort(result, new Comparator<Cwp>() {
            public int compare(Cwp a, Cwp b) {
                int d = Integer.compare(depth.get(a.code), depth.get(b.code));
                if (d != 0) return d;
                int l = Boolean.compare(locked.contains(b.code), locked.contains(a.code));
                if (l != 0) return l;
                if (SolverRules.PLANNED_START_FIRST.equals(rules.getOrderingMode())) {
                    int s = a.plannedStart.compareTo(b.plannedStart);
                    if (s != 0) return s;
                    int p = Integer.compare(b.priority, a.priority);
                    return p != 0 ? p : a.code.compareTo(b.code);
                }
                int p = Integer.compare(b.priority, a.priority);
                if (p != 0) return p;
                int s = a.plannedStart.compareTo(b.plannedStart);
                return s != 0 ? s : a.code.compareTo(b.code);
            }
        });
        return result;
    }

    /** 递归计算 CWP 的依赖深度（最长前置链长度），带缓存避免重复计算。 */
    private int computeDepth(Cwp c, Map<String, Cwp> byCode, Map<String, Integer> cache) {
        Integer existing = cache.get(c.code); if (existing != null) return existing;
        int depth = 0;
        for (Dependency d : c.dependencies) if (byCode.containsKey(d.predecessor))
            depth = Math.max(depth, 1 + computeDepth(byCode.get(d.predecessor), byCode, cache));
        cache.put(c.code, depth); return depth;
    }

    /** 基于计划日期识别零浮时(计划关键)任务，用于锁定传播。 */
    private Set<String> plannedCriticalTasks(Model model, Map<String, Cwp> byCode) {
        // 从项目计划结束日反向推算每个 CWP 的最晚完成时间；零浮时任务视为计划关键任务。
        Map<String, LocalDate> latestEnd = new HashMap<String, LocalDate>();
        for (Cwp c : model.cwps) latestEnd.put(c.code, model.projects.get(c.projectCode).plannedEnd);
        List<Cwp> reverse = topologicalOrder(model.cwps, byCode, Collections.<String>emptySet(), new SolverRules());
        Collections.reverse(reverse);
        for (int iteration = 0; iteration < reverse.size(); iteration++) for (Cwp succ : reverse) {
            LocalDate succEnd = latestEnd.get(succ.code);
            LocalDate succStart = succEnd.minusDays(succ.duration - 1L);
            for (Dependency d : succ.dependencies) {
                Cwp pred = byCode.get(d.predecessor); if (pred == null) continue;
                LocalDate bound;
                if ("FS".equals(d.relation)) bound = succStart.minusDays(d.lag + 1L);
                else if ("SS".equals(d.relation)) bound = succStart.minusDays(d.lag).plusDays(pred.duration - 1L);
                else if ("FF".equals(d.relation)) bound = succEnd.minusDays(d.lag);
                else bound = succEnd.minusDays(d.lag).plusDays(pred.duration - 1L);
                if (bound.isBefore(latestEnd.get(pred.code))) latestEnd.put(pred.code, bound);
            }
        }
        Set<String> critical = new HashSet<String>();
        for (Cwp c : model.cwps) {
            LocalDate latestStart = latestEnd.get(c.code).minusDays(c.duration - 1L);
            if (latestStart.equals(c.plannedStart)) critical.add(c.code);
        }
        return critical;
    }

    /** 若关键任务被锁定，则把其全部前置链一并锁定以保稳定。 */
    private Set<String> expandLockedPredecessors(List<Cwp> cwps, Map<String, Cwp> byCode, Set<String> critical) {
        // 若关键任务被锁定，其全部前置链也要一起保持稳定，避免锁定任务因依赖而失效。
        Set<String> result = new LinkedHashSet<String>();
        for (Cwp c : cwps) if (c.locked) {
            result.add(c.code);
            if (critical.contains(c.code)) addPredecessors(c, byCode, result);
        }
        return result;
    }

    /** 直接以 isLocked 标记的任务集合作为锁定集合。 */
    private Set<String> explicitlyLocked(List<Cwp> cwps) {
        Set<String> result = new LinkedHashSet<String>();
        for (Cwp cwp : cwps) if (cwp.locked) result.add(cwp.code);
        return result;
    }

    /** 递归收集 CWP 的全部前驱（深度优先）。 */
    private void addPredecessors(Cwp c, Map<String, Cwp> byCode, Set<String> result) {
        for (Dependency d : c.dependencies) {
            Cwp pred = byCode.get(d.predecessor);
            if (pred != null && result.add(pred.code)) addPredecessors(pred, byCode, result);
        }
    }

    /** 判断两个资源组是否可互相替代：模式相同且单位兼容。 */
    private boolean compatible(ResourceGroup a, ResourceGroup b) {
        return a.mode.equals(b.mode) && (a.unit.length() == 0 || a.unit.equals(b.unit));
    }
    /** 将任务标记为带冲突，并记录具体的冲突代码。 */
    private void markForced(ScheduledTask task, String code) {
        task.withConflict = true;
        if (!task.violationCodes.contains(code)) task.violationCodes.add(code);
    }

    /**
     * 资源占用台账。
     *
     * <p>记录四类资源（月产能、每日人力、每日区域占用、总装网格）的已用情况，并提供
     * 候选可行性校验与确认登记。求解过程中每个候选都先在台账副本上试排，只有被选中的
     * 最优候选才会写入正式台账，从而避免失败候选污染已确认的资源占用。</p>
     */
    static final class Ledger {
        final Model model;
        final SolverRules rules;

        // 四类资源分别按“资源-月份”“地点-日期”“区域-日期”“阶段-日期网格”记账。
        final Map<String, BigDecimal> capacityByMonth = new HashMap<String, BigDecimal>();
        final Map<String, Integer> laborByDay = new HashMap<String, Integer>();
        final Map<String, BigDecimal> occupancyByDay = new HashMap<String, BigDecimal>();
        final Map<String, boolean[][]> gridByDay = new HashMap<String, boolean[][]>();
        final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();
        // 利用率滚动聚合：sumUtil 为各(资源组,月)利用率之和，utilCount 为计数，用于增量计算平均利用率。
        BigDecimal sumUtil = BigDecimal.ZERO;
        int utilCount = 0;

        Ledger(Model model, SolverRules rules) { this.model = model; this.rules = rules; }
        Ledger copy() {
            Ledger c = new Ledger(model, rules); c.capacityByMonth.putAll(capacityByMonth);
            c.laborByDay.putAll(laborByDay); c.occupancyByDay.putAll(occupancyByDay);
            for (Map.Entry<String, boolean[][]> e : gridByDay.entrySet()) c.gridByDay.put(e.getKey(), cloneGrid(e.getValue()));
            c.tasks.addAll(tasks); c.sumUtil = sumUtil; c.utilCount = utilCount; return c;
        }

        boolean canUseOperation(ScheduledTask task, Operation op, ResourceGroup group, boolean strict) {
            // CAPACITY 类型按任务持续天数将工作量分摊到各自然月，再校验月度上限。
            if (!"CAPACITY".equals(group.mode) || !strict) return true;
            Map<YearMonth, BigDecimal> additions = monthlyWork(op.workload, task.start, task.end);
            for (Map.Entry<YearMonth, BigDecimal> e : additions.entrySet()) {
                BigDecimal used = capacityByMonth.get(key(group.id, e.getKey()));
                if (used == null) used = BigDecimal.ZERO;
                BigDecimal allowed = group.maximum.multiply(rules.getCapacitySafetyFactor());
                if (used.add(e.getValue()).compareTo(allowed) > 0) return false;
            }
            return true;
        }

        boolean canUseLabor(ScheduledTask task, boolean strict) {
            // 人力需求由工序日均工作量和人均日能力向上取整，并逐日校验地点上限。
            if (!strict) return true;
            Map<String, Integer> addedByLocation = laborPerLocation(task);
            for (Map.Entry<String, Integer> e : addedByLocation.entrySet()) {
                Domain.LaborLimit limit = model.laborLimits.get(e.getKey()); if (limit == null) continue;
                for (LocalDate d = task.start; !d.isAfter(task.end); d = d.plusDays(1)) {
                    Integer used = laborByDay.get(key(e.getKey(), d)); if (used == null) used = 0;
                    int allowed = BigDecimal.valueOf(limit.maxPerDay).multiply(rules.getLaborSafetyFactor())
                            .setScale(0, RoundingMode.FLOOR).intValue();
                    if (used + e.getValue() > allowed) return false;
                }
            }
            return true;
        }

        OccupancyPlacement findOccupancy(ScheduledTask task, ResourceGroup group, boolean strict) {
            // 一个区域必须在任务的每一天都有足够剩余占用比例，才算连续可用。
            for (Region region : group.regions) {
                boolean fits = true;
                for (LocalDate d = task.start; !d.isAfter(task.end); d = d.plusDays(1)) {
                    BigDecimal used = occupancyByDay.get(key(group.id, region.id, d));
                    if (used == null) used = BigDecimal.ZERO;
                    if (used.add(task.cwp.occupancyRatio).compareTo(BigDecimal.ONE) > 0) { fits = false; break; }
                }
                if (fits) {
                    OccupancyPlacement p = new OccupancyPlacement(); p.resourceGroupId = group.id;
                    p.stationId = region.id; p.stationName = region.name; return p;
                }
            }
            return null;
        }

        void reserveTask(ScheduledTask task) {
            // 候选确认后，一次性登记产能、人力、区域占用，供后续任务避让。
            for (Operation op : task.cwp.operations) {
                ResourceGroup g = model.groups.get(task.operationResource.get(op.code));
                if ("CAPACITY".equals(g.mode)) for (Map.Entry<YearMonth, BigDecimal> e : monthlyWork(op.workload, task.start, task.end).entrySet()) {
                    String k = key(g.id, e.getKey());
                    BigDecimal old = capacityByMonth.get(k);
                    if (g.baseline.compareTo(BigDecimal.ZERO) > 0) {
                        // 利用率增量 = 本次新增工作量 / baseline，与是否已有占用无关；仅首次占用该(资源,月)时计数+1。
                        BigDecimal rate = e.getValue().divide(g.baseline, 4, RoundingMode.HALF_UP);
                        if (old == null) utilCount++;
                        sumUtil = sumUtil.add(rate);
                    }
                    add(capacityByMonth, k, e.getValue());
                }
            }
            for (Map.Entry<String, Integer> e : laborPerLocation(task).entrySet())
                for (LocalDate d = task.start; !d.isAfter(task.end); d = d.plusDays(1)) add(laborByDay, key(e.getKey(), d), e.getValue());
            if (task.occupancy != null) for (LocalDate d = task.start; !d.isAfter(task.end); d = d.plusDays(1))
                add(occupancyByDay, key(task.occupancy.resourceGroupId, task.occupancy.stationId, d), task.cwp.occupancyRatio);
            tasks.add(task);
        }

        GridPlacement findAndReserveGrid(ResourceGroup group, AssemblyUnit unit, LocalDate start, LocalDate end) {
            // 网格尺寸来自单体 rows/cols；是否允许跨工位由资源组 allowCrossStation 控制。
            for (Phase phase : group.phases) {
                int totalColumns = phase.stations.size() * group.gridCols;
                for (int row = 0; row + unit.rows <= group.gridRows; row++) {
                    for (int col = 0; col + unit.cols <= totalColumns; col++) {
                        if (!group.allowCrossStation && col / group.gridCols != (col + unit.cols - 1) / group.gridCols) continue;
                        if (gridFits(group, phase, start, end, row, col, unit.rows, unit.cols, totalColumns)) {
                            GridPlacement p = new GridPlacement(); p.resourceGroupId = group.id; p.phaseId = phase.id;
                            p.phaseName = phase.name; p.startRow = row; p.startColumn = col; p.rows = unit.rows; p.cols = unit.cols;
                            int firstStation = col / group.gridCols, lastStation = (col + unit.cols - 1) / group.gridCols;
                            for (int s = firstStation; s <= lastStation; s++) {
                                p.stationIds.add(phase.stations.get(s).id); p.stationNames.add(phase.stations.get(s).name);
                            }
                            reserveGrid(group, phase, start, end, row, col, unit.rows, unit.cols, totalColumns); return p;
                        }
                    }
                }
            }
            return null;
        }

        private boolean gridFits(ResourceGroup group, Phase phase, LocalDate start, LocalDate end,
                                 int row, int col, int rows, int cols, int totalColumns) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                boolean[][] grid = gridByDay.get(key(group.id, phase.id, d));
                if (grid == null) continue;
                for (int r = row; r < row + rows; r++) for (int c = col; c < col + cols; c++) if (grid[r][c]) return false;
            }
            return true;
        }
        private void reserveGrid(ResourceGroup group, Phase phase, LocalDate start, LocalDate end,
                                 int row, int col, int rows, int cols, int totalColumns) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                String k = key(group.id, phase.id, d); boolean[][] grid = gridByDay.get(k);
                if (grid == null) { grid = new boolean[group.gridRows][totalColumns]; gridByDay.put(k, grid); }
                for (int r = row; r < row + rows; r++) for (int c = col; c < col + cols; c++) grid[r][c] = true;
            }
        }

        Map<String, Integer> laborPerLocation(ScheduledTask task) {
            Map<String, Integer> result = new HashMap<String, Integer>();
            for (Operation op : task.cwp.operations) {
                ResourceGroup g = model.groups.get(task.operationResource.get(op.code));
                BigDecimal daily = op.workload.divide(BigDecimal.valueOf(task.cwp.duration), 12, RoundingMode.HALF_UP);
                int persons = daily.divide(op.workloadPerPersonDay, 0, RoundingMode.CEILING).intValue();
                Integer old = result.get(g.locationCode); result.put(g.locationCode, (old == null ? 0 : old) + persons);
            }
            return result;
        }

        BigDecimal incrementalCost(ScheduledTask task) {
            // 当前目标函数为计划日期偏差成本；资源硬约束已在候选过滤阶段处理。
            long deviation = Math.abs(ChronoUnit.DAYS.between(task.cwp.plannedStart, task.start));
            return model.cost.deviationPerDay.multiply(BigDecimal.valueOf(deviation));
        }

        BigDecimal avgUtilization() {
            if (utilCount == 0) return BigDecimal.ZERO;
            return sumUtil.divide(BigDecimal.valueOf(utilCount), 4, RoundingMode.HALF_UP);
        }

        ScheduledTask findTask(String code) {
            for (ScheduledTask t : tasks) if (t.cwp.code.equals(code)) return t;
            return null;
        }

        int priorityContribution(ScheduledTask task, Model model) {
            // 该候选若能在项目完工约束内、且满足已排前置依赖，则贡献其项目优先级；
            // 否则视为冲突（贡献为 0），让“项目优先级最高”目标优先选择无冲突位置。
            Project project = model.projects.get(task.cwp.projectCode);
            if (project != null && project.finishHardConstraint && task.end.isAfter(project.plannedEnd)) return 0;
            for (Dependency d : task.cwp.dependencies) {
                ScheduledTask pred = findTask(d.predecessor);
                if (pred == null) continue;
                if (!depSatisfied(pred, task, d)) return 0;
            }
            return task.cwp.priority;
        }

        BigDecimal costOf(ScheduledTask task, Model model) {
            // 该候选的完整增量成本：日期偏差 + 人力 + 产能加班 + 占用/网格 + 锁定期违反。
            BigDecimal total = model.cost.deviationPerDay.multiply(BigDecimal.valueOf(Math.abs(ChronoUnit.DAYS.between(task.cwp.plannedStart, task.start))));
            for (Map.Entry<String, Integer> e : laborPerLocation(task).entrySet()) {
                BigDecimal rate = model.laborRates.get(e.getKey());
                if (rate != null) total = total.add(rate.multiply(BigDecimal.valueOf(e.getValue())));
            }
            for (Operation op : task.cwp.operations) {
                ResourceGroup g = model.groups.get(task.operationResource.get(op.code));
                if (!"CAPACITY".equals(g.mode)) continue;
                ResourceRate r = model.resourceRates.get(g.id);
                if (r == null) continue;
                for (Map.Entry<YearMonth, BigDecimal> e : monthlyWork(op.workload, task.start, task.end).entrySet()) {
                    BigDecimal normal = e.getValue().min(g.baseline);
                    BigDecimal overtime = e.getValue().subtract(g.baseline).max(BigDecimal.ZERO);
                    total = total.add(normal.multiply(r.baselineUnitCost)).add(overtime.multiply(r.overtimeUnitCost));
                }
            }
            long days = task.cwp.duration;
            if (task.occupancy != null) {
                ResourceRate r = model.resourceRates.get(task.occupancy.resourceGroupId);
                if (r != null) total = total.add(task.cwp.occupancyRatio.multiply(BigDecimal.valueOf(days)).multiply(r.occupancyUnitCostPerDay));
            }
            if (task.grid != null) {
                ResourceRate r = model.resourceRates.get(task.grid.resourceGroupId);
                if (r != null) total = total.add(BigDecimal.valueOf(task.cwp.unit.blockCount).multiply(BigDecimal.valueOf(days)).multiply(r.blockUnitCostPerDay));
            }
            if (task.cwp.locked && (!task.start.equals(task.cwp.plannedStart) || !task.end.equals(task.cwp.plannedEnd)))
                total = total.add(model.cost.lockViolationPerDay);
            return total.setScale(2, RoundingMode.HALF_UP);
        }

        private static boolean depSatisfied(ScheduledTask pred, ScheduledTask succ, Dependency d) {
            if ("FS".equals(d.relation)) return !succ.start.isBefore(pred.end.plusDays(d.lag + 1L));
            if ("SS".equals(d.relation)) return !succ.start.isBefore(pred.start.plusDays(d.lag));
            if ("FF".equals(d.relation)) return !succ.end.isBefore(pred.end.plusDays(d.lag));
            return !succ.end.isBefore(pred.start.plusDays(d.lag));
        }

        static Map<YearMonth, BigDecimal> monthlyWork(BigDecimal workload, LocalDate start, LocalDate end) {
            // 按每月覆盖天数同比例拆分总工作量，最后一个月吸收舍入差额以保证总量守恒。
            Map<YearMonth, Integer> days = new LinkedHashMap<YearMonth, Integer>(); int total = 0;
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                YearMonth ym = YearMonth.from(d); Integer count = days.get(ym); days.put(ym, count == null ? 1 : count + 1); total++;
            }
            Map<YearMonth, BigDecimal> result = new LinkedHashMap<YearMonth, BigDecimal>(); BigDecimal assigned = BigDecimal.ZERO;
            int i = 0;
            for (Map.Entry<YearMonth, Integer> e : days.entrySet()) {
                i++; BigDecimal amount = i == days.size() ? workload.subtract(assigned)
                        : workload.multiply(BigDecimal.valueOf(e.getValue())).divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP);
                result.put(e.getKey(), amount); assigned = assigned.add(amount);
            }
            return result;
        }

        private static void add(Map<String, BigDecimal> map, String key, BigDecimal amount) {
            BigDecimal old = map.get(key); map.put(key, (old == null ? BigDecimal.ZERO : old).add(amount));
        }
        private static void add(Map<String, Integer> map, String key, int amount) {
            Integer old = map.get(key); map.put(key, (old == null ? 0 : old) + amount);
        }
        /** 用 '|' 拼接复合键（资源组、月份/日期等）。 */
        static String key(Object... parts) { StringBuilder b = new StringBuilder(); for (Object p : parts) { if (b.length() > 0) b.append('|'); b.append(p); } return b.toString(); }
        /** 深拷贝二维布尔网格（避免副本间共享同一数组）。 */
        private static boolean[][] cloneGrid(boolean[][] source) { boolean[][] copy = new boolean[source.length][]; for (int i=0;i<source.length;i++) copy[i]=source[i].clone(); return copy; }
    }
}
