package com.example.aps.cwp.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排程领域模型。
 *
 * <p>负责把接口 JSON 解析成强类型的排程对象。所有金额、工作量均使用 {@link BigDecimal}
 * 以避免浮点误差；日期统一转换为本地时区的 {@link LocalDate}。该模型是只读的，
 * 真正的资源占用状态由 {@code Ledger} 维护。</p>
 */
final class Domain {
    private Domain() { }

    /** 一次排程求解的全部输入数据。 */
    static final class Model {
        final Map<String, Project> projects = new LinkedHashMap<String, Project>();
        final Map<String, ResourceGroup> groups = new LinkedHashMap<String, ResourceGroup>();
        final Map<String, LaborLimit> laborLimits = new LinkedHashMap<String, LaborLimit>();
        final Map<String, BigDecimal> laborRates = new HashMap<String, BigDecimal>();
        final Map<String, ResourceRate> resourceRates = new HashMap<String, ResourceRate>();
        final List<Cwp> cwps = new ArrayList<Cwp>();
        final CostModel cost = new CostModel();
        final List<Objective> objectives = new ArrayList<Objective>();
        boolean objectivesEnabled;
        LocalDate horizonStart;
        LocalDate horizonEnd;
    }

    /** 项目：决定排程的计划完工日，以及是否对完工施加硬约束。 */
    static final class Project {
        String code;
        String name;
        LocalDate plannedEnd;
        String plannedEndText;
        boolean finishHardConstraint;
    }

    /** 资源组：可消耗产能(CAPACITY)、占用比例(OCCUPANCY_RATIO)或总装网格(GRID_BLOCK)三种模式之一。 */
    static final class ResourceGroup {
        String id;
        String name;
        String mode;
        String locationCode;
        String locationName;
        String unit;
        BigDecimal baseline = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        List<String> substitutes = new ArrayList<String>();
        List<Region> regions = new ArrayList<Region>();
        List<Phase> phases = new ArrayList<Phase>();
        int gridRows;
        int gridCols;
        int blocksPerStation;
        boolean allowCrossStation;
    }

    /** 占用区域：OCCUPANCY_RATIO 模式下可被按比例占用的具体区域。 */
    static final class Region {
        String id;
        String name;
    }
    /** 总装阶段：包含若干工位，用于 GRID_BLOCK 模式下的网格排布。 */
    static final class Phase {
        String id;
        String name;
        List<Station> stations = new ArrayList<Station>();
    }
    /** 工位：总装阶段内的具体排布位置。 */
    static final class Station {
        String id;
        String name;
    }
    /** 地点人力上限：每个地点每天可投入的最大人数。 */
    static final class LaborLimit {
        String locationCode;
        String locationName;
        int maxPerDay;
    }
    /** 资源成本单价：产能的基础/加班单价、占用日单价、网格块日单价。 */
    static final class ResourceRate {
        BigDecimal baselineUnitCost = BigDecimal.ZERO;
        BigDecimal overtimeUnitCost = BigDecimal.ZERO;
        BigDecimal occupancyUnitCostPerDay = BigDecimal.ZERO;
        BigDecimal blockUnitCostPerDay = BigDecimal.ZERO;
    }
    /** 成本模型：是否启用、计划日期偏差单价、锁定违反单价。 */
    static final class CostModel {
        boolean enabled;
        BigDecimal deviationPerDay = BigDecimal.ZERO;
        BigDecimal lockViolationPerDay = BigDecimal.ZERO;
    }
    /** 优化目标：名称、方向(maximize/minimize)与度量指标(metric)。 */
    static final class Objective {
        String code;
        String name;
        String direction;
        String metric;
    }

    /** 工作包(CWP)：排程的基本单位，含计划窗口、工作量、依赖与工序。 */
    static final class Cwp {
        String code;
        String name;
        String projectCode;
        String projectName;
        int priority;
        LocalDate plannedStart;
        LocalDate plannedEnd;
        String plannedStartText;
        String plannedEndText;
        boolean locked;
        int duration;
        BigDecimal totalWorkload;
        BigDecimal progress;
        BigDecimal remaining;
        String workloadUnit;
        BigDecimal occupancyRatio = BigDecimal.ONE;
        AssemblyUnit unit;
        List<Dependency> dependencies = new ArrayList<Dependency>();
        List<Operation> operations = new ArrayList<Operation>();
    }
    /** 工序：CWP 工艺路线中的一道操作，绑定资源组并分摊工作量。 */
    static final class Operation {
        String code;
        String name;
        int sequence;
        BigDecimal ratio;
        BigDecimal workload;
        BigDecimal workloadPerPersonDay;
        String resourceGroupId;
    }
    /** 依赖：前驱 CWP 与本 CWP 的约束关系(FS/SS/FF/SF)及时滞。 */
    static final class Dependency {
        String predecessor;
        String relation;
        int lag;
    }
    /** 总装单体：由若干 CWP 共享，需在总装网格上分配矩形区域。 */
    static final class AssemblyUnit {
        String code;
        String name;
        int rows;
        int cols;
        int blockCount;
        String layout;
    }
    /** 已排程任务：CWP 在某日期窗口下的具体排布结果及冲突标记。 */
    static final class ScheduledTask {
        Cwp cwp;
        LocalDate start;
        LocalDate end;
        Map<String, String> operationResource = new LinkedHashMap<String, String>();
        OccupancyPlacement occupancy;
        GridPlacement grid;
        boolean withConflict;
        List<String> violationCodes = new ArrayList<String>();
    }
    /** 占用排布：OCCUPANCY_RATIO 模式下选中的区域(工位)。 */
    static final class OccupancyPlacement {
        String resourceGroupId;
        String stationId;
        String stationName;
    }
    /** 网格排布：GRID_BLOCK 模式下选中的阶段、起始行列与覆盖工位。 */
    static final class GridPlacement {
        String resourceGroupId;
        String phaseId;
        String phaseName;
        int startRow;
        int startColumn;
        int rows;
        int cols;
        List<String> stationIds = new ArrayList<String>();
        List<String> stationNames = new ArrayList<String>();
    }

    /** 将接口 JSON 解析为排程领域模型；同时计算排程时间范围(horizon)。 */
    static Model parse(JsonNode root, ZoneId zone) {
        Model model = new Model();
        for (JsonNode n : root.path("projects")) {
            Project p = new Project();
            p.code = text(n, "projectCode"); p.name = text(n, "projectName");
            p.plannedEndText = text(n, "plannedEnd"); p.plannedEnd = date(p.plannedEndText, zone);
            p.finishHardConstraint = n.path("finishHardConstraint").asBoolean(true);
            model.projects.put(p.code, p);
        }
        for (JsonNode n : root.path("resourceBindingPolicy").path("resourceGroups")) {
            ResourceGroup g = new ResourceGroup();
            g.id = text(n, "resourceGroupId"); g.name = text(n, "resourceGroupName");
            g.mode = text(n, "consumptionMode"); g.locationCode = text(n, "locationCode");
            g.locationName = text(n, "locationName"); g.unit = text(n.path("capacity"), "unit");
            g.baseline = decimal(n.path("capacity"), "baselineAmount");
            g.maximum = decimal(n.path("capacity"), "maxAmount");
            if ("GRID_BLOCK".equals(g.mode)) {
                g.maximum = decimal(n.path("capacity"), "amount"); g.unit = text(n.path("capacity"), "unit");
                JsonNode def = n.path("assemblyLayout").path("blockDefinition");
                g.gridRows = def.path("rows").asInt(); g.gridCols = def.path("cols").asInt();
                g.blocksPerStation = def.path("blockCountPerStation").asInt();
                g.allowCrossStation = n.path("assemblyLayout").path("allowCrossStation").asBoolean(false);
                for (JsonNode pn : n.path("assemblyLayout").path("phases")) {
                    Phase phase = new Phase(); phase.id = text(pn, "phaseCode"); phase.name = text(pn, "phaseName");
                    for (JsonNode sn : pn.path("stations")) {
                        Station st = new Station(); st.id = text(sn, "stationCode"); st.name = text(sn, "stationName");
                        phase.stations.add(st);
                    }
                    g.phases.add(phase);
                }
            }
            for (JsonNode s : n.path("substituteResourceGroupIds")) g.substitutes.add(s.asText());
            for (JsonNode rn : n.path("regions")) {
                Region region = new Region(); region.id = text(rn, "regionId"); region.name = text(rn, "regionName");
                g.regions.add(region);
            }
            model.groups.put(g.id, g);
        }
        for (JsonNode n : root.path("locationLaborConstraints").path("locations")) {
            LaborLimit l = new LaborLimit(); l.locationCode = text(n, "locationCode");
            l.locationName = text(n, "locationName"); l.maxPerDay = n.path("maxLaborPerDay").asInt();
            model.laborLimits.put(l.locationCode, l);
        }
        JsonNode oo = root.path("optimizationObjectives");
        model.objectivesEnabled = oo.path("enabled").asBoolean(false);
        for (JsonNode n : oo.path("objectives")) {
            Objective o = new Objective();
            o.code = text(n, "objectiveCode"); o.name = text(n, "objectiveName");
            o.direction = text(n, "direction"); o.metric = text(n, "metric");
            model.objectives.add(o);
        }
        JsonNode cost = root.path("costModel"); model.cost.enabled = cost.path("enabled").asBoolean(false);
        model.cost.deviationPerDay = decimal(cost, "scheduleDeviationCostPerDay");
        model.cost.lockViolationPerDay = decimal(cost, "lockViolationCostPerDay");
        for (JsonNode n : cost.path("laborUnitCosts"))
            model.laborRates.put(text(n, "locationCode"), decimal(n, "amountPerPersonDay"));
        for (JsonNode n : cost.path("resourceCostRates")) {
            ResourceRate rate = new ResourceRate();
            rate.baselineUnitCost = decimal(n, "baselineUnitCost");
            rate.overtimeUnitCost = decimal(n, "overtimeUnitCost");
            rate.occupancyUnitCostPerDay = decimal(n, "occupancyUnitCostPerDay");
            rate.blockUnitCostPerDay = decimal(n, "blockUnitCostPerDay");
            model.resourceRates.put(text(n, "resourceGroupId"), rate);
        }
        for (JsonNode n : root.path("cwps")) {
            Cwp c = new Cwp(); c.code = text(n, "cwpCode"); c.name = text(n, "cwpName");
            c.projectCode = text(n, "projectCode"); c.projectName = text(n, "projectName");
            c.priority = n.path("projectPriority").asInt(); c.plannedStartText = text(n, "plannedStart");
            c.plannedEndText = text(n, "plannedEnd"); c.plannedStart = date(c.plannedStartText, zone);
            c.plannedEnd = date(c.plannedEndText, zone); c.locked = n.path("isLocked").asBoolean(false);
            c.duration = (int) ChronoUnit.DAYS.between(c.plannedStart, c.plannedEnd) + 1;
            c.totalWorkload = decimal(n.path("workload"), "totalAmount");
            c.progress = decimal(n.path("workload"), "progress");
            c.remaining = c.totalWorkload.multiply(BigDecimal.ONE.subtract(c.progress));
            c.workloadUnit = text(n.path("workload"), "unit");
            if (n.path("ratioOccupation").path("occupancyRatio").isNumber())
                c.occupancyRatio = n.path("ratioOccupation").path("occupancyRatio").decimalValue();
            if (n.hasNonNull("assemblyUnit")) {
                JsonNode un = n.path("assemblyUnit"); JsonNode bn = un.path("requiredBlocks");
                c.unit = new AssemblyUnit(); c.unit.code = text(un, "unitCode"); c.unit.name = text(un, "unitName");
                c.unit.rows = bn.path("rows").asInt(); c.unit.cols = bn.path("cols").asInt();
                c.unit.blockCount = bn.path("blockCount").asInt(); c.unit.layout = text(bn, "layout");
            }
            for (JsonNode dn : n.path("dependencies")) {
                Dependency d = new Dependency(); d.predecessor = text(dn, "predecessorCwpCode");
                d.relation = text(dn, "relation"); d.lag = dn.path("lag").asInt(0); c.dependencies.add(d);
            }
            JsonNode ops = n.path("processRoute").path("operations");
            for (JsonNode on : ops) {
                Operation op = new Operation(); op.code = text(on, "opCode"); op.name = text(on, "opName");
                op.sequence = on.path("sequence").asInt();
                op.ratio = on.has("workloadRatio") ? on.path("workloadRatio").decimalValue() : BigDecimal.ONE;
                op.workload = c.remaining.multiply(op.ratio); op.workloadPerPersonDay = decimal(on.path("laborNorm"), "workloadPerPersonDay");
                op.resourceGroupId = on.path("resourceGroup").path("resourceGroupId").asText(); c.operations.add(op);
            }
            Collections.sort(c.operations, new Comparator<Operation>() {
                public int compare(Operation a, Operation b) { return Integer.compare(a.sequence, b.sequence); }
            });
            model.cwps.add(c);
            if (model.horizonStart == null || c.plannedStart.isBefore(model.horizonStart)) model.horizonStart = c.plannedStart;
        }
        for (Project p : model.projects.values())
            if (model.horizonEnd == null || p.plannedEnd.isAfter(model.horizonEnd)) model.horizonEnd = p.plannedEnd;
        return model;
    }

    /** 将带时区的日期时间字符串转换为本地日期。 */
    static LocalDate date(String value, ZoneId zone) {
        return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate();
    }
    /** 将本地日期转换为该时区当天零点的 ISO 字符串。 */
    static String atStart(LocalDate value, ZoneId zone) { return value.atStartOfDay(zone).toOffsetDateTime().toString(); }
    /** 读取数值字段，缺失或非数值时返回零。 */
    static BigDecimal decimal(JsonNode n, String field) { return n.path(field).isNumber() ? n.path(field).decimalValue() : BigDecimal.ZERO; }
    /** 读取文本字段，缺失时返回空串。 */
    static String text(JsonNode n, String field) { return n.path(field).asText(""); }
}
