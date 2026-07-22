package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Set;

/**
 * 成本最优策略：以综合成本最低为目标，覆盖日期偏差、人力、产能加班、占用/网格与锁定期违反。
 *
 * <p>与默认策略的差异主要体现在评分维度：用 {@link Ledger#costOf} 的全量成本代替单纯的计划偏差成本，
 * 因此会主动选择低成本资源组合与日期，即便偏离原计划也在所不惜。</p>
 */
public class CostMinimizeStrategy implements ScheduleStrategy {

    public Comparator<Cwp> orderComparator(Set<String> locked, SolverRules rules) {
        // 成本最优同样让高优先级任务先排，使其更容易落到低成本的可行位置。
        return Comparator.comparingInt((Cwp c) -> -c.priority)
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
        // 评分 = 候选落在试排台账上的完整增量成本，越低越好。
        return trialCandidate.costOf(candidate, model).compareTo(trialBest.costOf(best, model));
    }
}
