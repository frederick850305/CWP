package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Set;

/**
 * 尽早开工策略：以缩短总工期（makespan）为目标，让每个任务在其可行窗口内尽早开始。
 *
 * <p>与默认策略的差异体现在两个维度：
 * <ul>
 *   <li>排序：按原计划日开始（贴近计划顺序），再按优先级；</li>
 *   <li>评分：候选的开始日越早越好（以距排程起点天数衡量），而非最小化计划偏差。</li>
 * </ul>
 */
public class EarliestStartStrategy implements ScheduleStrategy {

    public Comparator<Cwp> orderComparator(Set<String> locked, SolverRules rules) {
        return Comparator.comparing((Cwp c) -> c.plannedStart)
                .thenComparingInt((Cwp c) -> -c.priority)
                .thenComparing(c -> c.code);
    }

    public Comparator<LocalDate> candidateDateComparator(Cwp cwp, SolverRules rules) {
        // 从最早可行日开始搜索，越早的候选越先被评估。
        return Comparator.naturalOrder();
    }

    public int compareCandidates(ScheduledTask candidate, ScheduledTask best,
                                 Ledger trialCandidate, Ledger trialBest, Model model) {
        // 评分 = 距排程起点(horizonStart)的天数，越小表示开工越早、越优。
        long daysCandidate = ChronoUnit.DAYS.between(model.horizonStart, candidate.start);
        long daysBest = ChronoUnit.DAYS.between(model.horizonStart, best.start);
        return Long.compare(daysCandidate, daysBest);
    }
}
