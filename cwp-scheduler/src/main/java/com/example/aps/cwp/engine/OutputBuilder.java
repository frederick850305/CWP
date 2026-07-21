package com.example.aps.cwp.engine;

import com.example.aps.cwp.engine.Domain.Cwp;
import com.example.aps.cwp.engine.Domain.Dependency;
import com.example.aps.cwp.engine.Domain.GridPlacement;
import com.example.aps.cwp.engine.Domain.LaborLimit;
import com.example.aps.cwp.engine.Domain.Model;
import com.example.aps.cwp.engine.Domain.Operation;
import com.example.aps.cwp.engine.Domain.Phase;
import com.example.aps.cwp.engine.Domain.Project;
import com.example.aps.cwp.engine.Domain.Region;
import com.example.aps.cwp.engine.Domain.ResourceGroup;
import com.example.aps.cwp.engine.Domain.ResourceRate;
import com.example.aps.cwp.engine.Domain.ScheduledTask;
import com.example.aps.cwp.engine.Domain.Station;
import com.example.aps.cwp.engine.ScheduleEngine.Ledger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OutputBuilder {
    private final ObjectMapper mapper;
    private final ZoneId zone;

    OutputBuilder(ObjectMapper mapper, ZoneId zone) { this.mapper = mapper; this.zone = zone; }

    ObjectNode build(Model model, Map<String, ScheduledTask> scheduled, Ledger ledger,
                     List<String> warnings, long runtimeMillis) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode utilization = utilization(model, ledger); root.set("monthlyWorkshopUtilization", utilization);
        root.set("monthlyLaborDemandCurve", laborCurve(model, ledger));
        ArrayNode conflicts = conflicts(model, scheduled, ledger); root.set("resourceConflictList", conflicts);
        root.set("projectCriticalPathOutput", criticalPaths(model, scheduled));
        root.set("prefabStationGanttOutput", prefabGantt(model, scheduled));
        root.set("assemblyStationGanttOutput", assemblyGantt(model, scheduled));
        root.set("cwpGanttOutput", cwpGantt(model, scheduled));
        root.set("scheduleSummary", summary(model, scheduled, ledger, utilization, conflicts, warnings, runtimeMillis));
        return root;
    }

    private ArrayNode utilization(Model model, Ledger ledger) {
        class Row { YearMonth month; ResourceGroup group; BigDecimal used; }
        List<Row> rows = new ArrayList<Row>();
        for (Map.Entry<String, BigDecimal> e : ledger.capacityByMonth.entrySet()) {
            String[] p = e.getKey().split("\\|"); ResourceGroup g = model.groups.get(p[0]);
            if (g == null || !"CAPACITY".equals(g.mode)) continue;
            Row row = new Row(); row.group = g; row.month = YearMonth.parse(p[1]); row.used = e.getValue(); rows.add(row);
        }
        Collections.sort(rows, new Comparator<Row>() { public int compare(Row a, Row b) {
            int m = a.month.compareTo(b.month); return m != 0 ? m : a.group.id.compareTo(b.group.id);
        }});
        ArrayNode out = mapper.createArrayNode();
        for (Row row : rows) {
            ObjectNode n = out.addObject(); n.put("month", row.month.toString());
            n.put("locationCode", row.group.locationCode); n.put("locationName", row.group.locationName);
            n.put("resourceGroupId", row.group.id); n.put("resourceGroupName", row.group.name);
            ObjectNode cap = n.putObject("totalCapacity"); cap.set("amount", number(row.group.baseline));
            cap.put("unit", row.group.unit); cap.put("timeUnit", "month"); cap.put("source", "boundResourceGroup");
            n.set("usedAmount", number(row.used));
            n.set("utilizationRate", number(row.used.divide(row.group.baseline, 4, RoundingMode.HALF_UP)));
        }
        return out;
    }

    private ArrayNode laborCurve(Model model, Ledger ledger) {
        class Sum { int persons; Set<LocalDate> active = new HashSet<LocalDate>(); String name; }
        Map<String, Sum> sums = new LinkedHashMap<String, Sum>();
        for (Map.Entry<String, Integer> e : ledger.laborByDay.entrySet()) {
            String[] p = e.getKey().split("\\|"); String loc = p[0]; LocalDate date = LocalDate.parse(p[1]);
            String key = YearMonth.from(date) + "|" + loc; Sum sum = sums.get(key);
            if (sum == null) { sum = new Sum(); sum.name = locationName(model, loc); sums.put(key, sum); }
            sum.persons += e.getValue(); if (e.getValue() > 0) sum.active.add(date);
        }
        List<String> keys = new ArrayList<String>(sums.keySet()); Collections.sort(keys);
        Map<String, ArrayNode> months = new LinkedHashMap<String, ArrayNode>();
        for (String key : keys) {
            String[] p = key.split("\\|"); ArrayNode locations = months.get(p[0]);
            if (locations == null) { locations = mapper.createArrayNode(); months.put(p[0], locations); }
            Sum s = sums.get(key); ObjectNode n = locations.addObject(); n.put("locationCode", p[1]); n.put("locationName", s.name);
            BigDecimal average = BigDecimal.valueOf(s.persons).divide(BigDecimal.valueOf(s.active.size()), 2, RoundingMode.HALF_UP);
            n.set("demand", number(average));
        }
        ArrayNode out = mapper.createArrayNode();
        for (Map.Entry<String, ArrayNode> e : months.entrySet()) { ObjectNode n = out.addObject(); n.put("month", e.getKey()); n.set("byLocation", e.getValue()); }
        return out;
    }

    private ArrayNode conflicts(Model model, Map<String, ScheduledTask> scheduled, Ledger ledger) {
        ArrayNode out = mapper.createArrayNode(); int sequence = 1;
        for (Map.Entry<String, BigDecimal> e : ledger.capacityByMonth.entrySet()) {
            String[] p = e.getKey().split("\\|"); ResourceGroup g = model.groups.get(p[0]);
            BigDecimal effectiveMaximum = g == null ? BigDecimal.ZERO
                    : g.maximum.multiply(ledger.rules.getCapacitySafetyFactor());
            if (g == null || !"CAPACITY".equals(g.mode) || e.getValue().compareTo(effectiveMaximum) <= 0) continue;
            YearMonth month = YearMonth.parse(p[1]); ObjectNode n = conflictBase(out, sequence++, "CAPACITY", month.atEndOfMonth(), g);
            n.set("availableAmount", number(effectiveMaximum)); n.set("requiredAmount", number(e.getValue()));
            n.set("shortageAmount", number(e.getValue().subtract(effectiveMaximum))); n.put("unit", g.unit);
            n.set("conflictedCwps", conflictedCwps(scheduled, g.id, month));
        }
        for (Map.Entry<String, Integer> e : ledger.laborByDay.entrySet()) {
            String[] p = e.getKey().split("\\|"); LaborLimit limit = model.laborLimits.get(p[0]);
            int effectiveMaximum = limit == null ? 0 : BigDecimal.valueOf(limit.maxPerDay)
                    .multiply(ledger.rules.getLaborSafetyFactor()).setScale(0, RoundingMode.FLOOR).intValue();
            if (limit == null || e.getValue() <= effectiveMaximum) continue;
            ObjectNode n = out.addObject(); n.put("conflictId", "RC-" + (sequence++)); n.put("conflictType", "LABOR");
            n.put("date", p[1]); n.put("locationCode", p[0]); n.put("resourceGroupId", "LABOR@" + p[0]);
            n.put("resourceGroupName", limit.locationName + "人力"); n.put("conflictScope", "CWP");
            n.put("availableAmount", effectiveMaximum); n.put("requiredAmount", e.getValue());
            n.put("shortageAmount", e.getValue() - effectiveMaximum); n.put("unit", "person");
            n.set("conflictedCwps", activeCwps(scheduled, p[0], LocalDate.parse(p[1]), model));
        }
        for (ScheduledTask task : scheduled.values()) {
            if (task.violationCodes.contains("OCCUPANCY_LIMIT")) {
                ResourceGroup g = firstGroup(task, model, "OCCUPANCY_RATIO");
                ObjectNode n = conflictBase(out, sequence++, "OCCUPANCY", task.start, g);
                n.put("availableAmount", g.regions.size()); n.set("requiredAmount", number(task.cwp.occupancyRatio));
                n.set("shortageAmount", number(task.cwp.occupancyRatio)); n.put("unit", "ratio");
                n.set("conflictedCwps", oneCwp(task));
            }
            if (task.violationCodes.contains("GRID_LIMIT")) {
                ResourceGroup g = firstGroup(task, model, "GRID_BLOCK");
                ObjectNode n = conflictBase(out, sequence++, "GRID", task.start, g); n.put("conflictScope", "UNIT");
                n.set("availableAmount", number(g.maximum)); n.put("requiredAmount", task.cwp.unit.blockCount);
                n.put("shortageAmount", task.cwp.unit.blockCount); n.put("unit", "小块");
                ArrayNode units = n.putArray("conflictedUnits"); ObjectNode u = units.addObject();
                u.put("unitCode", task.cwp.unit.code); u.put("unitName", task.cwp.unit.name);
                u.put("requiredAmount", task.cwp.unit.blockCount); u.put("allocatedAmount", 0);
                u.put("shortageAmount", task.cwp.unit.blockCount); u.put("amountUnit", "小块");
                u.putArray("relatedCwps").add(task.cwp.code); n.set("conflictedCwps", oneCwp(task));
            }
        }
        return out;
    }

    private ObjectNode conflictBase(ArrayNode out, int sequence, String type, LocalDate date, ResourceGroup g) {
        ObjectNode n = out.addObject(); n.put("conflictId", "RC-" + date + "-" + g.id + "-" + sequence);
        n.put("conflictType", type); n.put("date", date.toString()); n.put("locationCode", g.locationCode);
        n.put("resourceGroupId", g.id); n.put("resourceGroupName", g.name); n.put("conflictScope", "CWP"); return n;
    }

    private ObjectNode criticalPaths(Model model, Map<String, ScheduledTask> scheduled) {
        ObjectNode out = mapper.createObjectNode(); out.put("timeUnit", "day"); ArrayNode projects = out.putArray("projects");
        Map<String, LocalDate> latestEnd = calculateLatestEnds(model, scheduled);
        for (Project project : model.projects.values()) {
            List<ScheduledTask> tasks = projectTasks(scheduled, project.code); LocalDate scheduledEnd = null;
            for (ScheduledTask t : tasks) if (scheduledEnd == null || t.end.isAfter(scheduledEnd)) scheduledEnd = t.end;
            ObjectNode p = projects.addObject(); p.put("projectCode", project.code); p.put("projectName", project.name);
            p.put("projectPlannedEnd", project.plannedEndText);
            if (scheduledEnd == null) p.putNull("projectScheduledEnd"); else p.put("projectScheduledEnd", Domain.atStart(scheduledEnd, zone));
            p.put("projectFinishHardConstraint", project.finishHardConstraint);
            p.put("isProjectFinishOnTime", scheduledEnd != null && !scheduledEnd.isAfter(project.plannedEnd));
            ArrayNode critical = p.putArray("criticalPathCwps"); Set<String> criticalCodes = new HashSet<String>();
            for (ScheduledTask t : tasks) {
                LocalDate latestStart = latestEnd.get(t.cwp.code).minusDays(t.cwp.duration - 1L);
                long floatDays = ChronoUnit.DAYS.between(t.start, latestStart);
                if (floatDays != 0) continue;
                criticalCodes.add(t.cwp.code); ObjectNode c = critical.addObject();
                c.put("cwpCode", t.cwp.code); c.put("cwpName", t.cwp.name); c.put("plannedStart", t.cwp.plannedStartText);
                c.put("plannedEnd", t.cwp.plannedEndText); c.put("scheduledStart", Domain.atStart(t.start, zone));
                c.put("scheduledEnd", Domain.atStart(t.end, zone)); c.put("totalFloatDays", floatDays); c.put("isCritical", true);
                c.put("criticalReason", "总时差为0，任何后移都会消耗项目完工约束。");
            }
            ArrayNode links = p.putArray("pathLinks");
            for (ScheduledTask t : tasks) for (Dependency d : t.cwp.dependencies) if (criticalCodes.contains(t.cwp.code) && criticalCodes.contains(d.predecessor)) {
                ObjectNode l = links.addObject(); l.put("fromCwpCode", d.predecessor); l.put("toCwpCode", t.cwp.code);
                l.put("relation", d.relation); l.put("lagDays", d.lag);
            }
        }
        return out;
    }

    private Map<String, LocalDate> calculateLatestEnds(Model model, Map<String, ScheduledTask> scheduled) {
        Map<String, LocalDate> latest = new HashMap<String, LocalDate>();
        for (ScheduledTask t : scheduled.values()) latest.put(t.cwp.code, model.projects.get(t.cwp.projectCode).plannedEnd);
        List<ScheduledTask> reverse = new ArrayList<ScheduledTask>(scheduled.values()); Collections.reverse(reverse);
        for (int iteration = 0; iteration < reverse.size(); iteration++) for (ScheduledTask succ : reverse) {
            LocalDate succEnd = latest.get(succ.cwp.code); LocalDate succStart = succEnd.minusDays(succ.cwp.duration - 1L);
            for (Dependency d : succ.cwp.dependencies) {
                ScheduledTask pred = scheduled.get(d.predecessor); if (pred == null) continue;
                LocalDate bound;
                if ("FS".equals(d.relation)) bound = succStart.minusDays(d.lag + 1L);
                else if ("SS".equals(d.relation)) bound = succStart.minusDays(d.lag).plusDays(pred.cwp.duration - 1L);
                else if ("FF".equals(d.relation)) bound = succEnd.minusDays(d.lag);
                else bound = succEnd.minusDays(d.lag).plusDays(pred.cwp.duration - 1L);
                if (bound.isBefore(latest.get(pred.cwp.code))) latest.put(pred.cwp.code, bound);
            }
        }
        return latest;
    }

    private ObjectNode prefabGantt(Model model, Map<String, ScheduledTask> scheduled) {
        ObjectNode out = mapper.createObjectNode(); out.put("timeUnit", "day"); out.put("yAxis", "stationName"); out.put("xAxis", "time");
        ArrayNode rows = out.putArray("rows");
        for (ResourceGroup g : model.groups.values()) if ("OCCUPANCY_RATIO".equals(g.mode)) for (Region region : g.regions) {
            ObjectNode row = rows.addObject(); row.put("stationId", region.id); row.put("stationName", region.name); ArrayNode occupancies = row.putArray("occupancies");
            for (ScheduledTask t : scheduled.values()) if (t.occupancy != null && region.id.equals(t.occupancy.stationId)) {
                ObjectNode o = occupancies.addObject(); o.put("cwpCode", t.cwp.code); o.put("cwpName", t.cwp.name);
                o.put("projectCode", t.cwp.projectCode); o.put("start", Domain.atStart(t.start, zone)); o.put("end", Domain.atStart(t.end, zone));
                o.set("occupancyRatio", number(t.cwp.occupancyRatio)); o.put("occupancyUnit", "ratio");
            }
        }
        return out;
    }

    private ObjectNode assemblyGantt(Model model, Map<String, ScheduledTask> scheduled) {
        ObjectNode out = mapper.createObjectNode(); out.put("timeUnit", "day"); out.put("yAxis", "stationName"); out.put("xAxis", "time");
        ArrayNode rows = out.putArray("rows"); Set<String> emittedUnits = new HashSet<String>();
        for (ResourceGroup g : model.groups.values()) if ("GRID_BLOCK".equals(g.mode)) for (Phase phase : g.phases) for (Station station : phase.stations) {
            ObjectNode row = rows.addObject(); row.put("phaseId", phase.id); row.put("phaseName", phase.name);
            row.put("stationId", station.id); row.put("stationName", station.name); ArrayNode occupancies = row.putArray("occupancies");
            for (ScheduledTask t : scheduled.values()) if (t.grid != null && t.grid.stationIds.contains(station.id)) {
                String unique = station.id + "|" + t.cwp.unit.code; if (!emittedUnits.add(unique)) continue;
                List<ScheduledTask> unitTasks = unitTasks(scheduled, t.cwp.unit.code); LocalDate start=t.start,end=t.end;
                ArrayNode related; for(ScheduledTask u:unitTasks){if(u.start.isBefore(start))start=u.start;if(u.end.isAfter(end))end=u.end;}
                ObjectNode o=occupancies.addObject();o.put("unitCode",t.cwp.unit.code);o.put("unitName",t.cwp.unit.name);
                o.put("start",Domain.atStart(start,zone));o.put("end",Domain.atStart(end,zone)); related=o.putArray("relatedCwps");
                for(ScheduledTask u:unitTasks)related.add(u.cwp.code);
            }
        }
        return out;
    }

    private ObjectNode cwpGantt(Model model, Map<String, ScheduledTask> scheduled) {
        ObjectNode out=mapper.createObjectNode();out.put("timeUnit","day");ArrayNode tasks=out.putArray("tasks");
        for(ScheduledTask t:scheduled.values()){
            ObjectNode n=tasks.addObject();n.put("cwpCode",t.cwp.code);n.put("cwpName",t.cwp.name);n.put("projectCode",t.cwp.projectCode);
            String allocated=t.operationResource.isEmpty()?"":t.operationResource.values().iterator().next();
            n.put("allocatedResourceGroupId",allocated); n.put("locationCode", locationFor(t, model));
            n.put("plannedStart",t.cwp.plannedStartText);n.put("plannedEnd",t.cwp.plannedEndText);
            n.put("scheduledStart",Domain.atStart(t.start,zone));n.put("scheduledEnd",Domain.atStart(t.end,zone));
            n.put("status",t.withConflict?"scheduledWithConflict":"scheduled");n.set("progress",number(t.cwp.progress));
            ArrayNode violations=n.putArray("violations");for(String v:t.violationCodes)violations.add(v);
        }
        return out;
    }

    private ObjectNode summary(Model model, Map<String, ScheduledTask> scheduled, Ledger ledger,
                               ArrayNode utilization, ArrayNode conflicts, List<String> warnings, long runtimeMillis) {
        int hard=0;for(ScheduledTask t:scheduled.values())hard+=t.violationCodes.size(); hard+=conflicts.size();
        BigDecimal cost=totalCost(model,scheduled,ledger);BigDecimal avg=BigDecimal.ZERO;
        if(utilization.size()>0){for(int i=0;i<utilization.size();i++)avg=avg.add(utilization.get(i).path("utilizationRate").decimalValue());avg=avg.divide(BigDecimal.valueOf(utilization.size()),4,RoundingMode.HALF_UP);}
        int priorityScore=0;for(ScheduledTask t:scheduled.values())if(!t.withConflict)priorityScore+=t.cwp.priority;
        ObjectNode out=mapper.createObjectNode();out.put("feasible",hard==0);out.put("algorithmStatus",hard==0?"FEASIBLE_HEURISTIC":"DIAGNOSTIC_BEST_EFFORT");
        ObjectNode metrics=out.putObject("objectiveMetrics");metrics.put("projectPriorityScore",priorityScore);
        metrics.set("avgWorkshopCapacityUtilization",number(avg));metrics.set("totalScheduleCost",number(cost));
        out.set("totalCost",number(cost));out.put("hardConstraintViolationCount",hard);out.put("runtimeMillis",runtimeMillis);
        ArrayNode ws=out.putArray("warnings");for(String w:warnings)ws.add(w);return out;
    }

    private BigDecimal totalCost(Model model, Map<String,ScheduledTask> scheduled, Ledger ledger){
        BigDecimal total=BigDecimal.ZERO;
        for(Map.Entry<String,Integer>e:ledger.laborByDay.entrySet()){String loc=e.getKey().split("\\|")[0];BigDecimal rate=model.laborRates.get(loc);if(rate!=null)total=total.add(rate.multiply(BigDecimal.valueOf(e.getValue())));}
        for(Map.Entry<String,BigDecimal>e:ledger.capacityByMonth.entrySet()){String id=e.getKey().split("\\|")[0];ResourceGroup g=model.groups.get(id);ResourceRate r=model.resourceRates.get(id);if(r==null)continue;BigDecimal normal=e.getValue().min(g.baseline);BigDecimal overtime=e.getValue().subtract(g.baseline).max(BigDecimal.ZERO);total=total.add(normal.multiply(r.baselineUnitCost)).add(overtime.multiply(r.overtimeUnitCost));}
        Set<String> gridUnits=new HashSet<String>();
        for(ScheduledTask t:scheduled.values()){
            long days=t.cwp.duration;long deviation=Math.abs(ChronoUnit.DAYS.between(t.cwp.plannedStart,t.start));total=total.add(model.cost.deviationPerDay.multiply(BigDecimal.valueOf(deviation)));
            if(t.occupancy!=null){ResourceRate r=model.resourceRates.get(t.occupancy.resourceGroupId);if(r!=null)total=total.add(t.cwp.occupancyRatio.multiply(BigDecimal.valueOf(days)).multiply(r.occupancyUnitCostPerDay));}
            if(t.grid!=null&&gridUnits.add(t.cwp.unit.code)){ResourceRate r=model.resourceRates.get(t.grid.resourceGroupId);if(r!=null)total=total.add(BigDecimal.valueOf(t.cwp.unit.blockCount).multiply(BigDecimal.valueOf(days)).multiply(r.blockUnitCostPerDay));}
            if(t.cwp.locked&&( !t.start.equals(t.cwp.plannedStart)||!t.end.equals(t.cwp.plannedEnd)))total=total.add(model.cost.lockViolationPerDay);
        }
        return total.setScale(2,RoundingMode.HALF_UP);
    }

    private ArrayNode conflictedCwps(Map<String,ScheduledTask> scheduled,String groupId,YearMonth month){ArrayNode a=mapper.createArrayNode();for(ScheduledTask t:scheduled.values())if(t.operationResource.containsValue(groupId)&&(!YearMonth.from(t.end).isBefore(month)&&!YearMonth.from(t.start).isAfter(month))){ObjectNode n=a.addObject();n.put("cwpCode",t.cwp.code);n.put("cwpName",t.cwp.name);}return a;}
    private ArrayNode activeCwps(Map<String,ScheduledTask> scheduled,String location,LocalDate date,Model model){ArrayNode a=mapper.createArrayNode();for(ScheduledTask t:scheduled.values())if(!date.isBefore(t.start)&&!date.isAfter(t.end)){boolean found=false;for(String id:t.operationResource.values())if(model.groups.get(id).locationCode.equals(location))found=true;if(found){ObjectNode n=a.addObject();n.put("cwpCode",t.cwp.code);n.put("cwpName",t.cwp.name);}}return a;}
    private ArrayNode oneCwp(ScheduledTask t){ArrayNode a=mapper.createArrayNode();ObjectNode n=a.addObject();n.put("cwpCode",t.cwp.code);n.put("cwpName",t.cwp.name);return a;}
    private List<ScheduledTask> projectTasks(Map<String,ScheduledTask>s,String project){List<ScheduledTask>r=new ArrayList<ScheduledTask>();for(ScheduledTask t:s.values())if(project.equals(t.cwp.projectCode))r.add(t);return r;}
    private List<ScheduledTask> unitTasks(Map<String,ScheduledTask>s,String unit){List<ScheduledTask>r=new ArrayList<ScheduledTask>();for(ScheduledTask t:s.values())if(t.cwp.unit!=null&&unit.equals(t.cwp.unit.code))r.add(t);return r;}
    private ResourceGroup firstGroup(ScheduledTask t,Model m,String mode){for(String id:t.operationResource.values()){ResourceGroup g=m.groups.get(id);if(mode.equals(g.mode))return g;}throw new IllegalStateException("No group for "+mode);}
    private String locationFor(ScheduledTask t,Model m){if(t.operationResource.isEmpty())return "";ResourceGroup g=m.groups.get(t.operationResource.values().iterator().next());return g==null?"":g.locationCode;}
    private String locationName(Model m,String code){LaborLimit l=m.laborLimits.get(code);if(l!=null)return l.locationName;for(ResourceGroup g:m.groups.values())if(code.equals(g.locationCode))return g.locationName;return code;}
    private com.fasterxml.jackson.databind.JsonNode number(BigDecimal value){return mapper.getNodeFactory().numberNode(value.stripTrailingZeros());}
}
