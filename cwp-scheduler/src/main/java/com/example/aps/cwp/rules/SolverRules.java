package com.example.aps.cwp.rules;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可在运行期调整的求解策略。该对象会在排程任务开始时复制，保证一次求解使用同一版本规则。
 */
public final class SolverRules {
    public static final String PRIORITY_FIRST = "PRIORITY_FIRST";
    public static final String PLANNED_START_FIRST = "PLANNED_START_FIRST";

    private long version;
    private OffsetDateTime updatedAt;
    private String orderingMode;
    private boolean allowResourceSubstitution;
    private boolean lockCriticalPredecessors;
    private boolean preferEarlierOnTie;
    private BigDecimal capacitySafetyFactor;
    private BigDecimal laborSafetyFactor;
    private boolean diagnosticFallback;

    public SolverRules() {
        version = 1L;
        updatedAt = OffsetDateTime.now();
        orderingMode = PRIORITY_FIRST;
        allowResourceSubstitution = true;
        lockCriticalPredecessors = true;
        preferEarlierOnTie = true;
        capacitySafetyFactor = BigDecimal.ONE;
        laborSafetyFactor = BigDecimal.ONE;
        diagnosticFallback = true;
    }

    public SolverRules copy() {
        SolverRules copy = new SolverRules();
        copy.version = version;
        copy.updatedAt = updatedAt;
        copy.orderingMode = orderingMode;
        copy.allowResourceSubstitution = allowResourceSubstitution;
        copy.lockCriticalPredecessors = lockCriticalPredecessors;
        copy.preferEarlierOnTie = preferEarlierOnTie;
        copy.capacitySafetyFactor = capacitySafetyFactor;
        copy.laborSafetyFactor = laborSafetyFactor;
        copy.diagnosticFallback = diagnosticFallback;
        return copy;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("version", version);
        map.put("updatedAt", updatedAt.toString());
        map.put("orderingMode", orderingMode);
        map.put("allowResourceSubstitution", allowResourceSubstitution);
        map.put("lockCriticalPredecessors", lockCriticalPredecessors);
        map.put("preferEarlierOnTie", preferEarlierOnTie);
        map.put("capacitySafetyFactor", capacitySafetyFactor);
        map.put("laborSafetyFactor", laborSafetyFactor);
        map.put("diagnosticFallback", diagnosticFallback);
        return map;
    }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getOrderingMode() { return orderingMode; }
    public void setOrderingMode(String orderingMode) { this.orderingMode = orderingMode; }
    public boolean isAllowResourceSubstitution() { return allowResourceSubstitution; }
    public void setAllowResourceSubstitution(boolean value) { this.allowResourceSubstitution = value; }
    public boolean isLockCriticalPredecessors() { return lockCriticalPredecessors; }
    public void setLockCriticalPredecessors(boolean value) { this.lockCriticalPredecessors = value; }
    public boolean isPreferEarlierOnTie() { return preferEarlierOnTie; }
    public void setPreferEarlierOnTie(boolean value) { this.preferEarlierOnTie = value; }
    public BigDecimal getCapacitySafetyFactor() { return capacitySafetyFactor; }
    public void setCapacitySafetyFactor(BigDecimal value) { this.capacitySafetyFactor = value; }
    public BigDecimal getLaborSafetyFactor() { return laborSafetyFactor; }
    public void setLaborSafetyFactor(BigDecimal value) { this.laborSafetyFactor = value; }
    public boolean isDiagnosticFallback() { return diagnosticFallback; }
    public void setDiagnosticFallback(boolean value) { this.diagnosticFallback = value; }
}
