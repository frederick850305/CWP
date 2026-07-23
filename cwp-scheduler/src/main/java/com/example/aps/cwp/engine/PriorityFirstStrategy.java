package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.Project;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Set;

/**
 * 优先级优先策略：无论运行期规则如何设置，一律按“项目优先级”决定任务先后，并在评分中
 * 对“超出项目计划完工日”的候选施加重罚，从而优先保护高优先级项目的交付期限。
 *
 * <p>与默认策略的差异主要体现在两个维度：
 * <ul>
 *   <li>排序：强制优先级降序，不受 {@code orderingMode} 影响；</li>
 *   <li>评分：偏差成本 + 项目延期惩罚，使高优先级任务尽量落在计划窗口内。</li>
 * </ul>
 */
public class PriorityFirstStrategy implements ScheduleStrategy {

    /** 优先级高者先排；同级再按计划日开始、最后按编码稳定排序。 */
    public Comparator<Cwp> orderComparator(Set<String> locked, SolverRules rules) {
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
        // 评分 = 计划偏差天数 + 项目延期固定罚分（成本不作为排程条件，不做成本加权）。
        long scoreCandidate = deviationDays(candidate) + deadlinePenalty(candidate, model);
        long scoreBest = deviationDays(best) + deadlinePenalty(best, model);
        return Long.compare(scoreCandidate, scoreBest);
    }

    private static long deviationDays(ScheduledTask task) {
        return Math.abs(ChronoUnit.DAYS.between(task.cwp.plannedStart, task.start));
    }

    private static long deadlinePenalty(ScheduledTask task, Model model) {
        Project project = model.projects.get(task.cwp.projectCode);
        if (project != null && project.finishHardConstraint && task.end.isAfter(project.plannedEnd)) {
            // 固定重罚：晚于项目计划完工日的候选一律压后，与成本单价无关。
            return 1000L;
        }
        return 0L;
    }
}
