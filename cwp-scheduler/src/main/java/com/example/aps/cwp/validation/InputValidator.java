package com.example.aps.cwp.validation;

import com.example.aps.cwp.api.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InputValidator {
    public ValidationResult validate(JsonNode root) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        requireObject(root, "input", errors);
        requireArray(root.path("projects"), "projects", errors);
        requireArray(root.path("cwps"), "cwps", errors);
        requireObject(root.path("resourceBindingPolicy"), "resourceBindingPolicy", errors);
        requireArray(root.path("resourceBindingPolicy").path("resourceGroups"),
                "resourceBindingPolicy.resourceGroups", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Map<String, JsonNode> groups = index(root.path("resourceBindingPolicy").path("resourceGroups"),
                "resourceGroupId", "resource group", errors);
        Map<String, JsonNode> cwps = indexComposite(root.path("cwps"), "projectCode", "cwpCode", "CWP", errors);
        // 跨项目唯一 cwpCode 的前驱解析：仅当某 cwpCode 全局只出现一次时，才允许在异项目间解析前驱。
        Map<String, String> uniqueCwpCode = new HashMap<String, String>();
        Map<String, Integer> cwpCodeCount = new HashMap<String, Integer>();
        for (JsonNode n : root.path("cwps")) {
            String code = text(n, "cwpCode");
            cwpCodeCount.put(code, cwpCodeCount.getOrDefault(code, 0) + 1);
            uniqueCwpCode.put(code, compositeKey(text(n, "projectCode"), code));
        }
        for (Map.Entry<String, Integer> e : cwpCodeCount.entrySet())
            if (e.getValue() > 1) uniqueCwpCode.remove(e.getKey());
        Set<String> projectCodes = values(root.path("projects"), "projectCode");
        validateGroups(groups, errors, warnings);
        validateCwps(root.path("cwps"), cwps, groups, projectCodes, uniqueCwpCode, errors, warnings);
        validateCosts(root.path("costModel"), groups, root.path("locationLaborConstraints"), errors);
        validateDependencyCycles(cwps, uniqueCwpCode, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new ValidationResult(warnings);
    }

    private void validateGroups(Map<String, JsonNode> groups, List<String> errors, List<String> warnings) {
        for (Map.Entry<String, JsonNode> entry : groups.entrySet()) {
            String id = entry.getKey();
            JsonNode group = entry.getValue();
            String mode = text(group, "consumptionMode");
            if (!"CAPACITY".equals(mode) && !"GRID_BLOCK".equals(mode)
                    && !"OCCUPANCY_RATIO".equals(mode)) {
                errors.add("Resource group " + id + " has unsupported consumptionMode: " + mode);
            }
            if ("CAPACITY".equals(mode)) {
                positive(group.path("capacity"), "baselineAmount", id + ".capacity", errors);
                positive(group.path("capacity"), "maxAmount", id + ".capacity", errors);
                if (decimal(group.path("capacity"), "baselineAmount").compareTo(
                        decimal(group.path("capacity"), "maxAmount")) > 0) {
                    errors.add(id + " baselineAmount must not exceed maxAmount");
                }
            } else if ("OCCUPANCY_RATIO".equals(mode)) {
                if (!group.path("regions").isArray() || group.path("regions").size() == 0) {
                    errors.add(id + " requires at least one region");
                }
            } else if ("GRID_BLOCK".equals(mode)) {
                JsonNode layout = group.path("assemblyLayout");
                int perStation = layout.path("blockDefinition").path("blockCountPerStation").asInt(0);
                int stations = 0;
                for (JsonNode phase : layout.path("phases")) stations += phase.path("stations").size();
                int calculated = perStation * stations;
                int declared = group.path("capacity").path("amount").asInt(-1);
                if (perStation <= 0 || stations <= 0 || calculated != declared) {
                    errors.add(id + " grid capacity mismatch: layout=" + calculated + ", capacity.amount=" + declared);
                }
            }
            for (JsonNode substituteId : group.path("substituteResourceGroupIds")) {
                // 替代关系严格以 JSON 声明的 substituteResourceGroupIds 为准，不再以
                // consumptionMode / unit 是否兼容来忽略声明（即不再做“同单位即可跨模式替代”的判定）。
                JsonNode substitute = groups.get(substituteId.asText());
                if (substitute == null) {
                    errors.add(id + " references unknown substitute resource group " + substituteId.asText());
                }
            }
        }
    }

    private void validateCwps(JsonNode array, Map<String, JsonNode> cwps, Map<String, JsonNode> groups,
                              Set<String> projectCodes, Map<String, String> uniqueCwpCode, List<String> errors, List<String> warnings) {
        for (JsonNode cwp : array) {
            String code = text(cwp, "cwpCode");
            if (!projectCodes.contains(text(cwp, "projectCode")))
                errors.add(code + " references unknown project " + text(cwp, "projectCode"));
            try {
                java.time.OffsetDateTime start = java.time.OffsetDateTime.parse(text(cwp, "plannedStart"));
                java.time.OffsetDateTime end = java.time.OffsetDateTime.parse(text(cwp, "plannedEnd"));
                if (end.isBefore(start)) errors.add(code + " plannedEnd is before plannedStart");
            } catch (RuntimeException ex) { errors.add(code + " has invalid ISO-8601 planned dates"); }
            BigDecimal progress = decimal(cwp.path("workload"), "progress");
            if (progress.compareTo(BigDecimal.ZERO) < 0 || progress.compareTo(BigDecimal.ONE) > 0)
                errors.add(code + " workload.progress must be between 0 and 1");
            positive(cwp.path("workload"), "totalAmount", code + ".workload", errors);

            JsonNode ops = cwp.path("processRoute").path("operations");
            if (!ops.isArray() || ops.size() == 0) {
                errors.add(code + " requires at least one operation");
                continue;
            }
            BigDecimal ratioSum = BigDecimal.ZERO;
            for (JsonNode op : ops) {
                String opCode = text(op, "opCode");
                if (opCode.length() == 0) errors.add(code + " operation requires opCode");
                String rgId = op.path("resourceGroup").path("resourceGroupId").asText("");
                JsonNode group = groups.get(rgId);
                if (group == null) errors.add(code + "/" + opCode + " references unknown resource group " + rgId);
                BigDecimal ratio;
                if (ops.size() == 1 && !op.has("workloadRatio")) ratio = BigDecimal.ONE;
                else if (!op.has("workloadRatio")) {
                    errors.add(code + "/" + opCode + " requires workloadRatio for a multi-operation CWP");
                    ratio = BigDecimal.ZERO;
                } else ratio = op.path("workloadRatio").decimalValue();
                if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0)
                    errors.add(code + "/" + opCode + " workloadRatio must be in (0,1]");
                ratioSum = ratioSum.add(ratio);
                positive(op.path("laborNorm"), "workloadPerPersonDay", code + "/" + opCode + ".laborNorm", errors);
                if (group != null && !text(cwp.path("workload"), "unit").equals(
                        text(op.path("laborNorm"), "workloadUnit"))) {
                    errors.add(code + "/" + opCode + " labor workload unit does not match CWP workload unit");
                }
            }
            if (ratioSum.compareTo(BigDecimal.ONE) != 0)
                errors.add(code + " operation workloadRatio sum must equal 1, actual=" + ratioSum);

            for (JsonNode dep : cwp.path("dependencies")) {
                String predecessor = text(dep, "predecessorCwpCode");
                String composite = compositeKey(text(cwp, "projectCode"), predecessor);
                boolean external = !cwps.containsKey(composite) && !uniqueCwpCode.containsKey(predecessor);
                if (external)
                    warnings.add(code + " predecessor " + predecessor + " is external and treated as completed");
                String relation = text(dep, "relation");
                if (!"FS".equals(relation) && !"SS".equals(relation)
                        && !"FF".equals(relation) && !"SF".equals(relation))
                    errors.add(code + " has unsupported dependency relation " + relation);
            }
            validateSpecialResourceFields(cwp, code, ops, groups, errors);
        }
    }

    private void validateSpecialResourceFields(JsonNode cwp, String code, JsonNode ops,
                                                Map<String, JsonNode> groups, List<String> errors) {
        for (JsonNode op : ops) {
            JsonNode group = groups.get(op.path("resourceGroup").path("resourceGroupId").asText());
            if (group == null) continue;
            String mode = text(group, "consumptionMode");
            if ("OCCUPANCY_RATIO".equals(mode)) {
                BigDecimal ratio = cwp.path("ratioOccupation").path("occupancyRatio").isNumber()
                        ? cwp.path("ratioOccupation").path("occupancyRatio").decimalValue() : BigDecimal.ONE;
                if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0)
                    errors.add(code + " occupancyRatio must be in (0,1]");
            }
            if ("GRID_BLOCK".equals(mode)) {
                JsonNode blocks = cwp.path("assemblyUnit").path("requiredBlocks");
                int rows = blocks.path("rows").asInt(0);
                int cols = blocks.path("cols").asInt(0);
                int count = blocks.path("blockCount").asInt(-1);
                if (rows <= 0 || cols <= 0 || rows * cols != count)
                    errors.add(code + " assemblyUnit.requiredBlocks must satisfy rows*cols=blockCount");
            }
        }
    }

    private void validateCosts(JsonNode costModel, Map<String, JsonNode> groups, JsonNode laborConstraints,
                               List<String> errors) {
        if (!costModel.path("enabled").asBoolean(false)) return;
        // 主数据模式：单价由主数据提供，JSON 中不必填成本表，跳过“缺失”与单价相关校验。
        boolean masterData = "masterData".equals(text(costModel, "sourceMode"));
        if (masterData) return;
        Set<String> laborRates = values(costModel.path("laborUnitCosts"), "locationCode");
        Set<String> resourceRates = values(costModel.path("resourceCostRates"), "resourceGroupId");
        Set<String> requiredLaborLocations = new HashSet<String>();
        for (JsonNode location : laborConstraints.path("locations")) {
            requiredLaborLocations.add(text(location, "locationCode"));
        }
        for (JsonNode group : groups.values()) requiredLaborLocations.add(text(group, "locationCode"));
        for (String locationCode : requiredLaborLocations) if (!laborRates.contains(locationCode))
            errors.add("Missing labor unit cost for " + locationCode);
        for (String id : groups.keySet()) {
            if (!resourceRates.contains(id)) errors.add("Missing resource cost rate for " + id);
        }
        for (JsonNode rate : costModel.path("laborUnitCosts"))
            nonNegative(rate, "amountPerPersonDay", "costModel.laborUnitCosts[" + text(rate, "locationCode") + "]", errors);
        for (JsonNode rate : costModel.path("resourceCostRates")) {
            String path = "costModel.resourceCostRates[" + text(rate, "resourceGroupId") + "]";
            nonNegative(rate, "baselineUnitCost", path, errors);
            nonNegative(rate, "overtimeUnitCost", path, errors);
            nonNegative(rate, "occupancyUnitCostPerDay", path, errors);
            nonNegative(rate, "blockUnitCostPerDay", path, errors);
        }
        nonNegative(costModel, "scheduleDeviationCostPerDay", "costModel", errors);
        nonNegative(costModel, "lockViolationCostPerDay", "costModel", errors);
    }

    private void validateDependencyCycles(Map<String, JsonNode> cwps, Map<String, String> uniqueCwpCode, List<String> errors) {
        // 构建复合键邻接表：前驱默认在本项目内解析（projectCode|cwpCode），
        // 仅当 cwpCode 全局唯一时回退跨项目解析，其余视为外部（不参与成环）。
        Map<String, List<String>> adjacency = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, JsonNode> entry : cwps.entrySet()) {
            String key = entry.getKey();
            JsonNode cwp = entry.getValue();
            String project = text(cwp, "projectCode");
            List<String> predecessors = new ArrayList<String>();
            for (JsonNode dep : cwp.path("dependencies")) {
                String pc = text(dep, "predecessorCwpCode");
                String composite = compositeKey(project, pc);
                String resolved = cwps.containsKey(composite) ? composite : uniqueCwpCode.get(pc);
                if (resolved != null) predecessors.add(resolved);
            }
            adjacency.put(key, predecessors);
        }
        Map<String, Integer> state = new HashMap<String, Integer>();
        for (String key : adjacency.keySet()) if (visit(key, adjacency, state)) {
            errors.add("Dependency graph contains a cycle involving " + key);
            return;
        }
    }

    private boolean visit(String key, Map<String, List<String>> adjacency, Map<String, Integer> state) {
        Integer s = state.get(key);
        if (s != null) return s == 1;
        state.put(key, 1);
        for (String predecessor : adjacency.get(key)) if (visit(predecessor, adjacency, state)) return true;
        state.put(key, 2);
        return false;
    }

    private Map<String, JsonNode> index(JsonNode array, String field, String label, List<String> errors) {
        Map<String, JsonNode> result = new LinkedHashMap<String, JsonNode>();
        if (!array.isArray()) return result;
        for (JsonNode item : array) {
            String key = text(item, field);
            if (key.length() == 0) errors.add("Missing " + field + " in " + label);
            else if (result.put(key, item) != null) errors.add("Duplicate " + label + " id: " + key);
        }
        return result;
    }

    /** 复合键索引：以 projectCode|cwpCode 作为唯一键，允许不同项目下存在相同的 cwpCode。 */
    private Map<String, JsonNode> indexComposite(JsonNode array, String projectField, String codeField, String label, List<String> errors) {
        Map<String, JsonNode> result = new LinkedHashMap<String, JsonNode>();
        if (!array.isArray()) return result;
        for (JsonNode item : array) {
            String key = text(item, projectField) + "|" + text(item, codeField);
            if (text(item, codeField).length() == 0) errors.add("Missing " + codeField + " in " + label);
            else if (result.put(key, item) != null) errors.add("Duplicate " + label + " id: " + key);
        }
        return result;
    }

    private Set<String> values(JsonNode array, String field) {
        Set<String> result = new HashSet<String>();
        if (array.isArray()) for (JsonNode item : array) result.add(text(item, field));
        return result;
    }

    private void requireObject(JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isObject()) errors.add(path + " must be an object");
    }
    private void requireArray(JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isArray() || node.size() == 0) errors.add(path + " must be a non-empty array");
    }
    private void positive(JsonNode node, String field, String path, List<String> errors) {
        if (!node.path(field).isNumber() || decimal(node, field).compareTo(BigDecimal.ZERO) <= 0)
            errors.add(path + "." + field + " must be positive");
    }
    private void nonNegative(JsonNode node, String field, String path, List<String> errors) {
        if (!node.path(field).isNumber() || decimal(node, field).compareTo(BigDecimal.ZERO) < 0)
            errors.add(path + "." + field + " must be non-negative");
    }
    private BigDecimal decimal(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).decimalValue() : BigDecimal.ZERO;
    }
    private String text(JsonNode node, String field) { return node.path(field).asText(""); }

    /** 复合键：项目编码 + CWP 编码，多项目排程下唯一标识一个调度 CWP。 */
    private String compositeKey(String projectCode, String cwpCode) { return projectCode + "|" + cwpCode; }

    public static class ValidationResult {
        private final List<String> warnings;
        ValidationResult(List<String> warnings) { this.warnings = warnings; }
        public List<String> getWarnings() { return warnings; }
    }
}
