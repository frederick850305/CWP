package com.example.aps.cwp.service;

import com.example.aps.cwp.api.ValidationException;
import com.example.aps.cwp.rules.SolverRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 将受支持的中文规则指令解析为结构化配置。解析与应用分成两步，避免歧义指令直接影响生产排程。
 */
@Service
public class SolverRuleService {
    private static final Pattern CAPACITY_PERCENT = Pattern.compile("(?:产能|资源利用率)[^0-9]{0,12}([0-9]{1,3}(?:\\.[0-9]+)?)\\s*[%％]");
    private static final Pattern LABOR_PERCENT = Pattern.compile("(?:人力|人员)[^0-9]{0,12}([0-9]{1,3}(?:\\.[0-9]+)?)\\s*[%％]");

    private final Map<String, Proposal> proposals = new ConcurrentHashMap<String, Proposal>();
    private final List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
    private SolverRules active = new SolverRules();

    public synchronized SolverRules snapshot() { return active.copy(); }

    public synchronized Map<String, Object> currentState() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("rules", active.toMap());
        response.put("supportedCommands", Arrays.asList(
                "高优先级项目先排 / 按计划开始时间先排",
                "允许使用替代资源 / 禁止使用替代资源",
                "锁定关键任务的前置链 / 只锁定明确指定的任务",
                "同成本时优先较早日期 / 同成本时优先较晚日期",
                "产能利用率上限设为 90%",
                "人力上限按 85% 使用",
                "允许生成冲突诊断方案 / 严格模式，无解时失败",
                "恢复默认规则"));
        response.put("history", new ArrayList<Map<String, Object>>(history));
        return response;
    }

    public synchronized Map<String, Object> interpret(String message) {
        if (message == null || message.trim().length() == 0)
            throw new ValidationException(Arrays.asList("规则指令不能为空"));

        SolverRules proposed = active.copy();
        List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
        String text = message.trim();
        String compact = text.replaceAll("\\s+", "");

        if (containsAny(compact, "恢复默认", "重置规则", "恢复缺省")) {
            proposed = new SolverRules();
            addChange(changes, "all", "全部规则", "当前配置", "默认配置");
        } else {
            if (containsAny(compact, "按计划开始时间先排", "计划开始时间优先", "计划日期优先")) {
                changeOrdering(proposed, changes, SolverRules.PLANNED_START_FIRST);
            } else if (containsAny(compact, "高优先级项目先排", "项目优先级优先", "优先级高的先排", "高优先级先排")) {
                changeOrdering(proposed, changes, SolverRules.PRIORITY_FIRST);
            }

            if (mentions(compact, "替代资源")) {
                boolean value = !containsAny(compact, "禁止使用替代资源", "不允许使用替代资源", "关闭替代资源", "禁用替代资源");
                if (value || containsAny(compact, "禁止", "不允许", "关闭", "禁用"))
                    changeBoolean(changes, "allowResourceSubstitution", "允许替代资源",
                            proposed.isAllowResourceSubstitution(), value, proposed::setAllowResourceSubstitution);
            }

            if (containsAny(compact, "只锁定明确指定的任务", "不扩展锁定前置", "取消前置链锁定")) {
                changeBoolean(changes, "lockCriticalPredecessors", "锁定关键任务前置链",
                        proposed.isLockCriticalPredecessors(), false, proposed::setLockCriticalPredecessors);
            } else if (containsAny(compact, "锁定关键任务的前置链", "保护关键任务前置链", "扩展锁定前置")) {
                changeBoolean(changes, "lockCriticalPredecessors", "锁定关键任务前置链",
                        proposed.isLockCriticalPredecessors(), true, proposed::setLockCriticalPredecessors);
            }

            if (containsAny(compact, "同成本时优先较晚", "相同成本优先较晚", "同等成本靠后")) {
                changeBoolean(changes, "preferEarlierOnTie", "同成本优先较早日期",
                        proposed.isPreferEarlierOnTie(), false, proposed::setPreferEarlierOnTie);
            } else if (containsAny(compact, "同成本时优先较早", "相同成本优先较早", "同等成本靠前")) {
                changeBoolean(changes, "preferEarlierOnTie", "同成本优先较早日期",
                        proposed.isPreferEarlierOnTie(), true, proposed::setPreferEarlierOnTie);
            }

            Matcher capacity = CAPACITY_PERCENT.matcher(text);
            if (capacity.find()) changeFactor(changes, proposed, true, capacity.group(1));
            Matcher labor = LABOR_PERCENT.matcher(text);
            if (labor.find()) changeFactor(changes, proposed, false, labor.group(1));

            if (containsAny(compact, "严格模式", "无解时失败", "禁止生成冲突方案", "不允许冲突方案", "关闭诊断兜底")) {
                changeBoolean(changes, "diagnosticFallback", "无解时生成诊断方案",
                        proposed.isDiagnosticFallback(), false, proposed::setDiagnosticFallback);
            } else if (containsAny(compact, "允许生成冲突诊断方案", "允许冲突方案", "开启诊断兜底", "无解时给出诊断")) {
                changeBoolean(changes, "diagnosticFallback", "无解时生成诊断方案",
                        proposed.isDiagnosticFallback(), true, proposed::setDiagnosticFallback);
            }
        }

        if (changes.isEmpty()) throw new ValidationException(Arrays.asList(
                "未识别出可执行的规则修改。请参考页面中的示例指令。"));

        String id = UUID.randomUUID().toString();
        proposals.put(id, new Proposal(id, text, active.getVersion(), proposed, changes));
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("proposalId", id);
        response.put("message", "已解析规则变更，请确认后应用。未确认前不会影响排程。");
        response.put("changes", changes);
        response.put("proposedRules", proposed.toMap());
        return response;
    }

    public synchronized Map<String, Object> apply(String proposalId) {
        Proposal proposal = proposals.remove(proposalId);
        if (proposal == null) throw new ValidationException(Arrays.asList("规则变更预览不存在或已经应用"));
        if (proposal.baseVersion != active.getVersion())
            throw new ValidationException(Arrays.asList("规则已被其他变更更新，请重新发送指令生成预览"));
        SolverRules next = proposal.rules.copy();
        next.setVersion(active.getVersion() + 1L);
        next.setUpdatedAt(OffsetDateTime.now());
        active = next;

        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("version", active.getVersion());
        entry.put("appliedAt", active.getUpdatedAt().toString());
        entry.put("message", proposal.message);
        entry.put("changes", proposal.changes);
        history.add(0, entry);
        if (history.size() > 20) history.remove(history.size() - 1);

        Map<String, Object> response = currentState();
        response.put("message", "规则已应用，之后新建的排程任务将使用版本 v" + active.getVersion());
        return response;
    }

    private void changeOrdering(SolverRules rules, List<Map<String, Object>> changes, String value) {
        if (value.equals(rules.getOrderingMode())) return;
        addChange(changes, "orderingMode", "同层任务排序", rules.getOrderingMode(), value);
        rules.setOrderingMode(value);
    }

    private void changeFactor(List<Map<String, Object>> changes, SolverRules rules, boolean capacity, String percentText) {
        BigDecimal percent = new BigDecimal(percentText);
        if (percent.compareTo(BigDecimal.valueOf(10)) < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new ValidationException(Arrays.asList((capacity ? "产能" : "人力") + "安全上限必须在 10% 到 100% 之间"));
        BigDecimal factor = percent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).stripTrailingZeros();
        BigDecimal old = capacity ? rules.getCapacitySafetyFactor() : rules.getLaborSafetyFactor();
        if (old.compareTo(factor) == 0) return;
        addChange(changes, capacity ? "capacitySafetyFactor" : "laborSafetyFactor",
                capacity ? "产能安全上限" : "人力安全上限", old, factor);
        if (capacity) rules.setCapacitySafetyFactor(factor); else rules.setLaborSafetyFactor(factor);
    }

    private interface BooleanSetter { void set(boolean value); }
    private void changeBoolean(List<Map<String, Object>> changes, String field, String label,
                               boolean oldValue, boolean newValue, BooleanSetter setter) {
        if (oldValue == newValue) return;
        addChange(changes, field, label, oldValue, newValue);
        setter.set(newValue);
    }

    private void addChange(List<Map<String, Object>> changes, String field, String label, Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("field", field); change.put("label", label); change.put("from", from); change.put("to", to);
        changes.add(change);
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
    private boolean mentions(String text, String phrase) { return text.contains(phrase); }

    private static final class Proposal {
        final String id; final String message; final long baseVersion; final SolverRules rules;
        final List<Map<String, Object>> changes;
        Proposal(String id, String message, long baseVersion, SolverRules rules, List<Map<String, Object>> changes) {
            this.id = id; this.message = message; this.baseVersion = baseVersion; this.rules = rules; this.changes = changes;
        }
    }
}
