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

final class Domain {
    private Domain() { }

    static final class Model {
        final Map<String, Project> projects = new LinkedHashMap<String, Project>();
        final Map<String, ResourceGroup> groups = new LinkedHashMap<String, ResourceGroup>();
        final Map<String, LaborLimit> laborLimits = new LinkedHashMap<String, LaborLimit>();
        final Map<String, BigDecimal> laborRates = new HashMap<String, BigDecimal>();
        final Map<String, ResourceRate> resourceRates = new HashMap<String, ResourceRate>();
        final List<Cwp> cwps = new ArrayList<Cwp>();
        final CostModel cost = new CostModel();
        LocalDate horizonStart;
        LocalDate horizonEnd;
    }

    static final class Project {
        String code;
        String name;
        LocalDate plannedEnd;
        String plannedEndText;
        boolean finishHardConstraint;
    }

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

    static final class Region {
        String id;
        String name;
    }
    static final class Phase {
        String id;
        String name;
        List<Station> stations = new ArrayList<Station>();
    }
    static final class Station {
        String id;
        String name;
    }
    static final class LaborLimit {
        String locationCode;
        String locationName;
        int maxPerDay;
    }
    static final class ResourceRate {
        BigDecimal baselineUnitCost = BigDecimal.ZERO;
        BigDecimal overtimeUnitCost = BigDecimal.ZERO;
        BigDecimal occupancyUnitCostPerDay = BigDecimal.ZERO;
        BigDecimal blockUnitCostPerDay = BigDecimal.ZERO;
    }
    static final class CostModel {
        boolean enabled;
        BigDecimal deviationPerDay = BigDecimal.ZERO;
        BigDecimal lockViolationPerDay = BigDecimal.ZERO;
    }

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
    static final class Operation {
        String code;
        String name;
        int sequence;
        BigDecimal ratio;
        BigDecimal workload;
        BigDecimal workloadPerPersonDay;
        String resourceGroupId;
    }
    static final class Dependency {
        String predecessor;
        String relation;
        int lag;
    }
    static final class AssemblyUnit {
        String code;
        String name;
        int rows;
        int cols;
        int blockCount;
        String layout;
    }
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
    static final class OccupancyPlacement {
        String resourceGroupId;
        String stationId;
        String stationName;
    }
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

    static LocalDate date(String value, ZoneId zone) {
        return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate();
    }
    static String atStart(LocalDate value, ZoneId zone) { return value.atStartOfDay(zone).toOffsetDateTime().toString(); }
    static BigDecimal decimal(JsonNode n, String field) { return n.path(field).isNumber() ? n.path(field).decimalValue() : BigDecimal.ZERO; }
    static String text(JsonNode n, String field) { return n.path(field).asText(""); }
}
