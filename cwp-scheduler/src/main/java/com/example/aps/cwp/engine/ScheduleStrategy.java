package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.rules.SolverRules;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Set;

/**
 * 排程策略：把一个具体算法在排序与评分两个维度上的差异抽象出来。
 *
 * <p>引擎（{@link HeuristicScheduleEngine}）负责不变的骨架——解析领域模型、依赖层级排序、
 * 资源落位、网格分配与结果组装；而“先排谁”“候选日期怎么搜”“哪个候选更优”则由本策略决定。
 * 这样新增算法只需实现接口，无需重写整张排程流程。</p>
 */
public interface ScheduleStrategy {

    /**
     * 任务的业务排序比较器（依赖层级与锁定状态由引擎在外层保证，这里只决定同层级内的次序）。
     * 返回 <0 表示 {@code a} 应排在 {@code b} 之前。
     */
    Comparator<Cwp> orderComparator(Set<String> locked, SolverRules rules);

    /**
     * 候选日期的搜索顺序比较器（控制 {@code findBestCandidate} 的遍历先后，越早被搜到的可行候选越优先成为最优）。
     */
    Comparator<LocalDate> candidateDateComparator(Cwp cwp, SolverRules rules);

    /**
     * 比较两个已落位到试排台账的候选任务。符号约定同 {@link Comparator}：
     * 返回 <0 表示 {@code candidate} 优于 {@code best}；>0 表示 {@code best} 更优；0 表示相当。
     *
     * @param candidate    当前候选（已试排在 {@code trialCandidate} 上）
     * @param best         迄今最优候选（已试排在 {@code trialBest} 上）
     * @param trialCandidate {@code candidate} 所在的试排台账副本
     * @param trialBest       {@code best} 所在的试排台账副本
     */
    int compareCandidates(ScheduledTask candidate, ScheduledTask best,
                          Ledger trialCandidate, Ledger trialBest, Model model);

    /**
     * 提前终止条件：当某个候选已确定不可被超越时返回 {@code true}，引擎可立即停止搜索。
     * 绝大多数策略无须提前终止，返回 {@code false} 即可。
     */
    default boolean earlyStop(ScheduledTask candidate, Ledger trial, Model model) {
        return false;
    }
}
