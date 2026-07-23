package com.example.aps.cwp.engine;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源占用台账（从原 ScheduleEngine 抽取的独立类）。
 *
 * <p>记录四类资源（月产能、每日人力、每日区域占用、总装网格）的已用情况，并提供
 * 候选可行性校验与确认登记。求解过程中每个候选都先在台账副本上试排，只有被选中的
 * 最优候选才会写入正式台账，从而避免失败候选污染已确认的资源占用。</p>
 */
class Ledger {
        final Model model;
        final SolverRules rules;

        // 四类资源分别按“资源-月份”“地点-日期”“区域-日期”“阶段-日期网格”记账。
        final Map<String, BigDecimal> capacityByMonth = new HashMap<String, BigDecimal>();
        final Map<String, Integer> laborByDay = new HashMap<String, Integer>();
        final Map<String, BigDecimal> occupancyByDay = new HashMap<String, BigDecimal>();
        final Map<String, boolean[][]> gridByDay = new HashMap<String, boolean[][]>();
        // 月度小块产能：GRID_BLOCK 资源组按自然月累计已占用的“块·月”，用于总装月吞吐上限(group.maximum)校验。
        final Map<String, BigDecimal> gridCapacityByMonth = new HashMap<String, BigDecimal>();
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
            // 人力需求 = 工序日均工作量 × 每单位工作量每天所需人数（向上取整），并逐日校验地点上限。
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
            // 月度小块产能校验：单体跨越的各自然月累计“块·月”不得超 group.maximum（无上限时跳过）。
            if (group.maximum.compareTo(BigDecimal.ZERO) > 0) {
                for (Map.Entry<YearMonth, BigDecimal> e : gridMonthlyWork(unit.blockCount, start, end).entrySet()) {
                    BigDecimal used = gridCapacityByMonth.get(key(group.id, e.getKey()));
                    if (used == null) used = BigDecimal.ZERO;
                    if (used.add(e.getValue()).compareTo(group.maximum) > 0) return null;
                }
            }
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
                            reserveGrid(group, phase, start, end, row, col, unit.rows, unit.cols, totalColumns, unit.blockCount); return p;
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
                                 int row, int col, int rows, int cols, int totalColumns, int blockCount) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                String k = key(group.id, phase.id, d); boolean[][] grid = gridByDay.get(k);
                if (grid == null) { grid = new boolean[group.gridRows][totalColumns]; gridByDay.put(k, grid); }
                for (int r = row; r < row + rows; r++) for (int c = col; c < col + cols; c++) grid[r][c] = true;
            }
            // 累计月度小块产能占用（块·月/月），供后续单体校验月吞吐上限。
            if (group.maximum.compareTo(BigDecimal.ZERO) > 0)
                for (Map.Entry<YearMonth, BigDecimal> e : gridMonthlyWork(blockCount, start, end).entrySet())
                    add(gridCapacityByMonth, key(group.id, e.getKey()), e.getValue());
        }

        /** 将单体在网格上的占用折算为各自然月的“块·月”：按当月实际重叠天数占比分摊 blockCount。 */
        private Map<YearMonth, BigDecimal> gridMonthlyWork(int blockCount, LocalDate start, LocalDate end) {
            Map<YearMonth, Long> days = new LinkedHashMap<YearMonth, Long>();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                YearMonth ym = YearMonth.from(d);
                Long c = days.get(ym); days.put(ym, (c == null ? 0L : c) + 1L);
            }
            Map<YearMonth, BigDecimal> result = new LinkedHashMap<YearMonth, BigDecimal>();
            for (Map.Entry<YearMonth, Long> e : days.entrySet()) {
                BigDecimal consumed = BigDecimal.valueOf(blockCount)
                        .multiply(BigDecimal.valueOf(e.getValue()))
                        .divide(BigDecimal.valueOf(e.getKey().lengthOfMonth()), 4, RoundingMode.HALF_UP);
                result.put(e.getKey(), consumed);
            }
            return result;
        }

        Map<String, Integer> laborPerLocation(ScheduledTask task) {
            Map<String, Integer> result = new HashMap<String, Integer>();
            for (Operation op : task.cwp.operations) {
                ResourceGroup g = model.groups.get(task.operationResource.get(op.code));
                BigDecimal daily = op.workload.divide(BigDecimal.valueOf(task.cwp.duration), 12, RoundingMode.HALF_UP);
                int persons = daily.multiply(op.workloadPerPersonDay).setScale(0, RoundingMode.CEILING).intValue();
                Integer old = result.get(g.locationCode); result.put(g.locationCode, (old == null ? 0 : old) + persons);
            }
            return result;
        }

        BigDecimal avgUtilization() {
            if (utilCount == 0) return BigDecimal.ZERO;
            return sumUtil.divide(BigDecimal.valueOf(utilCount), 4, RoundingMode.HALF_UP);
        }

        ScheduledTask findTask(String code) {
            for (ScheduledTask t : tasks) if (t.cwp.key.equals(code)) return t;
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