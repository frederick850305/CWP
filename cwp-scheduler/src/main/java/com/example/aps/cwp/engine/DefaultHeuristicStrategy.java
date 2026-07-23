package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.Objective;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 默认启发式策略：完全复刻改造前的排程行为，作为算法框架的基线。
 *
 * <ul>
 *   <li>排序：尊重运行期规则 {@code orderingMode}（计划日期优先或项目优先级优先）。</li>
 *   <li>候选搜索：按“偏离原计划天数”升序，相同偏差按规则决定偏早/偏晚。</li>
 *   <li>评分：若输入启用了多目标优化，则按 {@code optimizationObjectives} 词典序比较；否则比较计划偏差成本。</li>
 * </ul>
 */
public class DefaultHeuristicStrategy implements ScheduleStrategy {

    public Comparator<Cwp> orderComparator(Set<String> locked, SolverRules rules) {
        if (SolverRules.PLANNED_START_FIRST.equals(rules.getOrderingMode())) {
            return Comparator.comparing((Cwp c) -> c.plannedStart)
                    .thenComparingInt((Cwp c) -> -c.priority)
                    .thenComparing(c -> c.code);
        }
        return Comparator.comparingInt((Cwp c) -> -c.priority)
                .thenComparing(c -> c.plannedStart)
                .thenComparing(c -> c.code);
    }

    public Comparator<LocalDate> candidateDateComparator(Cwp cwp, SolverRules rules) {
        return new Comparator<LocalDate>() {
            public int compare(LocalDate a, LocalDate b) {
                long da = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(cwp.plannedStart, a));
                long db = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(cwp.plannedStart, b));
                int byDistance = Long.compare(da, db);
                if (byDistance != 0) return byDistance;
                return rules.isPreferEarlierOnTie() ? a.compareTo(b) : b.compareTo(a);
            }
        };
    }

    public int compareCandidates(ScheduledTask candidate, ScheduledTask best,
                                 Ledger trialCandidate, Ledger trialBest, Model model) {
        boolean multiObjective = model.objectivesEnabled && !model.objectives.isEmpty();
        if (multiObjective) {
            // 多目标：按 optimizationObjectives 定义的词典序逐目标比较，高优先级目标先决胜负。
            List<BigDecimal> vc = objectiveVector(trialCandidate, candidate, model);
            List<BigDecimal> vb = objectiveVector(trialBest, best, model);
            int cmp = lexicographicBetter(vc, vb); // >0 表示 candidate 更优
            return -cmp; // 转成 Comparator 约定：<0 表示 candidate 更优
        }
        // 单目标：计划日期偏差天数，越低越好（成本不作为排程条件，不乘成本单价）。
        long cd = Math.abs(ChronoUnit.DAYS.between(candidate.cwp.plannedStart, candidate.start));
        long bd = Math.abs(ChronoUnit.DAYS.between(best.cwp.plannedStart, best.start));
        return Long.compare(cd, bd);
    }

    @Override
    public boolean earlyStop(ScheduledTask candidate, Ledger trial, Model model) {
        // 仅在单目标（未启用多目标优化）时早停：候选正好落在原计划日（偏差 0 天）即视为最优。
        // 多目标下不早停，需枚举全部候选日期才能选出词典序全局最优（否则会像旧版一样提前 break）。
        boolean multiObjective = model.objectivesEnabled && !model.objectives.isEmpty();
        if (multiObjective) return false;
        return candidate.start.equals(candidate.cwp.plannedStart);
    }

    /** 将候选在试排台账上的表现映射为目标向量；MINIMIZE 方向取相反数，使“越大越好”统一。 */
    private static List<BigDecimal> objectiveVector(Ledger trial, ScheduledTask cand, Model model) {
        List<BigDecimal> vec = new java.util.ArrayList<BigDecimal>();
        for (Objective o : model.objectives) {
            BigDecimal v;
            if ("projectPriorityScore".equals(o.metric)) v = BigDecimal.valueOf(trial.priorityContribution(cand, model));
            else if ("avgWorkshopCapacityUtilization".equals(o.metric)) v = trial.avgUtilization();
            else if ("totalScheduleCost".equals(o.metric)) v = BigDecimal.ZERO; // 成本仅为输出指标，不参与排程排序
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
}
