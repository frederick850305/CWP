#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把投标《CWP排程示例数据.xlsx》中的真实数据转换为 CWP 排程引擎可接受的输入 JSON。

真实数据是「工序级明细」（每行一道工序），而引擎要求「每个 CWP 一个对象、operations 为数组」。
本脚本做以下映射与最小必要适配：

 1. 聚合：同一 cwpCode 的多道工序 -> 一个 CWP 对象的 processRoute.operations 数组
 2. 资源组：Excel 中资源组(resourceGroupId)只在约 1/4 的 CWP 填写。
    - 有填写的：直接用真实值（最真实）
    - 缺失的：用「同 opCode 在真实数据中出现最多的资源组」补全（基于真实统计，仍真实）
 3. workloadRatio：真实数据无此字段，用每工序定额人工日(laborNorm)归一化推导，使总和=1
 4. 依赖：真实格式 "T1210: SS -30, T1090: FF" -> [{predecessorCwpCode, relation, lag}]
 5. 单位：保留真实工作量单位（CWP 内 laborNorm.workloadUnit 与 workload.unit 强制一致）
 6. 产能/成本/人力：Excel 不含这些主数据，按资源组类型给出合理假设值（见下方常量）

用法：
  python3 scripts/generate-from-excel.py [输出路径，默认 ../examples/cwp-schedule-test.json]
"""
import json
import re
import sys
from collections import defaultdict, OrderedDict

EXCEL_PATH = "/var/folders/zb/0_746g2j71b1q8gg9_rjhr6w0000gn/T/codebuddy-dropped-files/b28725ae-e207-4d92-b9c4-db5f913f0ef9/投标CWP排程示例数据.xlsx"
DEFAULT_OUT = "/Users/zhenghai/Documents/2.FY26/1.项目/19.海工APS项目/CWP/cwp-scheduler/examples/cwp-schedule-test.json"

# ---- Excel 列名 ----
C_CWP   = 'cwpCode(cwp编码)'
C_NAME  = 'cwpName(cwp名称)'
C_PCODE = 'projectCode(项目编号)'
C_PNAME = 'projectName(项目名称)'
C_PS    = 'plannedStart(项目计划开始时间)'
C_PE    = 'plannedEnd(项目计划结束时间)'
C_AMT   = 'workload.totalAmount(预估工程量)'
C_PROC  = 'workload.process(实际累计 实际完成百分比)'
C_UNIT  = 'workload.unit(工作量单位编码)'
C_DEP   = 'dependencies(紧前关系)'
C_RCODE = 'processRoute.routeCode(工艺路线编码)'
C_RNAME = 'processRoute.routeName(工艺路线名称)'
C_OP    = 'processRoute.operations.opCode(工序编码)'
C_OPN   = 'processRoute.operations.opName(工序名称)'
C_WPPD  = 'processRoute.operations.laborNorm.workloadPerPersonDay（定额人工日）'
C_WUNIT = 'processRoute.operations.laborNorm.workloadUnit（定额工作量单位编码)'
C_RGID  = 'processRoute.operations.resourceGroup.resourceGroupId(资源组编码)'
C_RGNAME= 'processRoute.operations.resourceGroup.resourceGroupName(资源组名称)'

# ---- 资源组产能/成本/人力 假设（Excel 无主数据）----
CAPACITY_PROFILE = {  # 产能型资源组月产能（标准工时）
    'CUT':   (6000, 7200),
    'YARD':  (4000, 5000),
    'OR':    (7000, 8500),
    'VR':    (7000, 8500),
}
OCCUPANCY_REGIONS = {
    'BLASTING-SHOP-1': ['BLAST-B1'],
    'BLASTING-SHOP-2': ['BLAST-B2'],
    'BLASTING-SHOP-3': ['BLAST-B3'],
    'VR-INS-GE':       ['INS-GE-1'],
    'YARD-INS':        ['YARD-INS-1', 'YARD-INS-2'],
    'YARD-PER-INS':    ['PER-INS-1', 'PER-INS-2'],
}
LOCATION_OF = {
    'CUT': 'LOC-CUT', 'BLAST': 'LOC-BLAST', 'INS': 'LOC-OUTFIT',
    'FAB': 'LOC-FAB', 'YARD': 'LOC-FAB', 'OR': 'LOC-PIPE', 'VR': 'LOC-PIPE',
}
LOCATION_NAMES = {
    'LOC-CUT': '下料车间', 'LOC-BLAST': '喷砂车间', 'LOC-OUTFIT': '舾装区',
    'LOC-FAB': '结构预制车间', 'LOC-PIPE': '工艺管线预制车间',
}
LABOR_PER_DAY = {'LOC-CUT': 650, 'LOC-BLAST': 680, 'LOC-OUTFIT': 760, 'LOC-FAB': 700, 'LOC-PIPE': 760}
LABOR_UNIT_COST = {'LOC-CUT': 650, 'LOC-BLAST': 680, 'LOC-OUTFIT': 760, 'LOC-FAB': 700, 'LOC-PIPE': 760}
RG_NAME_DEFAULT = {
    'BLASTING-SHOP-1': '喷砂车间1', 'BLASTING-SHOP-2': '喷砂车间2', 'BLASTING-SHOP-3': '喷砂车间3',
    'C-CUT-PL': '板材下料线', 'C-CUT-PL-JK': '甲板下料线',
    'C-YARD-FAB-MAN': '场地手动预制线', 'C-YARD-FAB-ZG': '场地自动预制线',
    'OR-FAB-GE': '管系设备预制线', 'OR-FAB-ZG': '管系总装预制线', 'OR-FAB-ZHL': '管系舾装预制线',
    'VR-FAB-PI': '管系预制线', 'VR-INS-GE': '舾装设备线', 'YARD-INS': '场地舾装', 'YARD-PER-INS': '场地预舾装',
}
# 按 opCode 第2段兜底（当该 opCode 从未在真实资源组中出现时）
SEG_RG_DEFAULT = {'FAB': 'C-YARD-FAB-MAN', 'INS': 'YARD-INS', 'PAT': 'BLASTING-SHOP-1', 'JK': 'C-YARD-FAB-MAN'}

# 仅过滤明确非海工的项目（如 IT 系统类）；海工真实数据即使标注"第一轮测试/演示"也保留
FAKE_PROJECT_KEYWORDS = ('费用管理',)


def is_blank(v):
    return v is None or (hasattr(v, 'isna') and v.isna()) or str(v).strip() in ('', ' ', 'nan')


def classify_mode(rgid):
    r = rgid.upper()
    return 'OCCUPANCY_RATIO' if ('BLAST' in r or 'INS' in r) else 'CAPACITY'


def location_of(rgid):
    r = rgid.upper()
    for key, loc in LOCATION_OF.items():
        if key in r:
            return loc
    return 'LOC-FAB'


def fmt_dt(value):
    if is_blank(value):
        return None
    if hasattr(value, 'strftime'):
        return value.strftime('%Y-%m-%dT%H:%M:%S+08:00')
    s = str(value).strip()
    if not s or s.lower() == 'nan':
        return None
    # 兼容 Excel 字符串日期，如 "2024-04-01 00:00:00" / "2024/04/01" -> ISO-8601 +08:00
    s = s.replace('/', '-').replace(' ', 'T')
    if 'T' not in s:
        s = s + 'T00:00:00'
    if '+' not in s and 'Z' not in s:
        s = s + '+08:00'
    return s


def parse_deps(cell):
    if is_blank(cell):
        return []
    deps = []
    for part in re.split(r'[,;]', str(cell)):
        part = part.strip()
        if not part:
            continue
        m = re.match(r'^([^:]+)\s*:\s*([A-Za-z]+)\s*(-?\d+)?$', part)
        if m:
            rel = m.group(2).strip().upper()
            if rel in ('FS', 'SS', 'FF', 'SF'):
                deps.append({'predecessorCwpCode': m.group(1).strip(), 'relation': rel,
                             'lag': int(m.group(3)) if m.group(3) else 0})
    return deps


def main():
    import pandas as pd
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    cwps_df = pd.read_excel(EXCEL_PATH, sheet_name='cwps')

    # 过滤脏行
    cwps_df = cwps_df[cwps_df[C_CWP].notna()]
    cwps_df[C_CWP] = cwps_df[C_CWP].astype(str).str.strip()
    cwps_df = cwps_df[cwps_df[C_CWP] != '']

    # 构建 opCode -> 资源组 众数映射（基于真实填写的资源组）
    opcode_rg = defaultdict(lambda: defaultdict(int))
    for _, row in cwps_df.iterrows():
        if not is_blank(row[C_RGID]) and not is_blank(row[C_OP]):
            opcode_rg[str(row[C_OP]).strip()][str(row[C_RGID]).strip()] += 1
    OPCODE_RG_MAP = {op: max(cnt.items(), key=lambda x: x[1])[0] for op, cnt in opcode_rg.items()}

    def resolve_rg(opcode, excel_rg):
        if not is_blank(excel_rg):
            return str(excel_rg).strip()
        if opcode in OPCODE_RG_MAP:
            return OPCODE_RG_MAP[opcode]
        seg = opcode.split('-')[1] if '-' in opcode else ''
        return SEG_RG_DEFAULT.get(seg, 'C-YARD-FAB-MAN')

    # 聚合 cwpCode -> 工序 + CWP 级字段
    cwp_map = OrderedDict()
    filled_rg = 0
    inferred_rg = 0
    for _, row in cwps_df.iterrows():
        code = row[C_CWP]
        if is_blank(row[C_OP]):
            continue  # 无工序编码的行跳过
        opcode = str(row[C_OP]).strip()
        rgid = resolve_rg(opcode, row[C_RGID])
        if rgid == str(row[C_RGID]).strip() if not is_blank(row[C_RGID]) else False:
            filled_rg += 1
        else:
            inferred_rg += 1
        rgname = str(row[C_RGNAME]).strip() if not is_blank(row[C_RGNAME]) else RG_NAME_DEFAULT.get(rgid, rgid)
        try:
            wppd = 0.0 if is_blank(row[C_WPPD]) else float(row[C_WPPD])
        except Exception:
            wppd = 0.0
        entry = cwp_map.setdefault(code, {'ops': [], 'dep_set': set(),
                                          'names': [], 'pnames': [], 'rcodes': [], 'rnames': [],
                                          'starts': [], 'ends': [], 'units': [], 'amts': [], 'procs': []})
        entry['ops'].append({'opCode': opcode, 'opName': str(row[C_OPN]).strip() if not is_blank(row[C_OPN]) else '',
                             'rgId': rgid, 'rgName': rgname, 'wppd': wppd})
        for key, bucket in ((C_NAME, 'names'), (C_PNAME, 'pnames'), (C_RCODE, 'rcodes'),
                            (C_RNAME, 'rnames'), (C_UNIT, 'units'), (C_AMT, 'amts'), (C_PROC, 'procs'),
                            (C_PS, 'starts'), (C_PE, 'ends')):
            v = row[key]
            if not is_blank(v):
                entry[bucket].append(v)
        for d in parse_deps(row[C_DEP]):
            entry['dep_set'].add((d['predecessorCwpCode'], d['relation'], d['lag']))

    def first(lst):
        for x in lst:
            if not is_blank(x):
                return x
        return None

    cwps_records = []
    skipped_fake = 0
    skipped_no_op = 0
    skipped_no_amt = 0
    for code, entry in cwp_map.items():
        if not entry['ops']:
            skipped_no_op += 1
            continue
        sub = cwps_df[cwps_df[C_CWP] == code]
        pcode = str(sub.iloc[0][C_PCODE]).strip()
        pname = str(sub.iloc[0][C_PNAME]).strip() if not is_blank(sub.iloc[0][C_PNAME]) else None
        if pname and any(k in pname for k in FAKE_PROJECT_KEYWORDS):
            skipped_fake += 1
            continue

        unit = first(entry['units'])
        amt_raw = first(entry['amts'])
        amt_val = pd.to_numeric(amt_raw, errors='coerce') if not is_blank(amt_raw) else None
        if amt_val is None or amt_val <= 0:  # 引擎要求 totalAmount>0，真实数据中多数 CWP 缺工程量
            skipped_no_amt += 1
            continue
        proc = first(entry['procs'])
        ps = min(entry['starts']) if entry['starts'] else None
        pe = max(entry['ends']) if entry['ends'] else None

        wppds = [op['wppd'] for op in entry['ops']]
        total = sum(wppds)
        raw = [1.0 / len(entry['ops'])] * len(entry['ops']) if total <= 0 else [w / total for w in wppds]
        ratios = [round(r, 6) for r in raw]
        if len(ratios) > 1:  # 补偿浮点误差，使总和精确等于 1
            ratios[-1] = round(1.0 - sum(ratios[:-1]), 6)

        operations = []
        for i, op in enumerate(entry['ops']):
            operations.append({
                'opCode': op['opCode'], 'opName': op['opName'], 'sequence': i + 1,
                'workloadRatio': ratios[i],
                'laborNorm': {'workloadPerPersonDay': op['wppd'], 'workloadUnit': unit if unit else '结构吨'},
                'resourceGroup': {'resourceGroupId': op['rgId'], 'resourceGroupName': op['rgName']},
            })

        progress = 0.0
        if proc is not None:
            try:
                pv = float(proc)
                progress = pv / 100.0 if pv > 1 else pv
            except Exception:
                progress = 0.0

        cwps_records.append({
            'cwpCode': code, 'cwpName': first(entry['names']) or code,
            'projectCode': pcode, 'projectName': pname, 'projectPriority': 5,
            'plannedStart': fmt_dt(ps), 'plannedEnd': fmt_dt(pe), 'isLocked': False,
            'workload': {'totalAmount': float(amt_val),
                         'progress': round(progress, 4), 'unit': unit if unit else '结构吨'},
            'dependencies': [{'predecessorCwpCode': p, 'relation': r, 'lag': l} for (p, r, l) in entry['dep_set']],
            'processRoute': {'routeCode': first(entry['rcodes']) or f'ROUTE-{code}',
                             'routeName': first(entry['rnames']) or '', 'operations': operations},
        })

    # 项目聚合
    proj_seen = OrderedDict()
    for c in cwps_records:
        pc = c['projectCode']
        if pc not in proj_seen:
            proj_seen[pc] = {'projectCode': pc, 'projectName': c['projectName'], 'plannedEnd': c['plannedEnd']}
        elif c['plannedEnd'] and (proj_seen[pc]['plannedEnd'] is None or c['plannedEnd'] > proj_seen[pc]['plannedEnd']):
            proj_seen[pc]['plannedEnd'] = c['plannedEnd']
    projects = [{'projectCode': pc, 'projectName': p['projectName'] or pc,
                 'plannedEnd': p['plannedEnd'] or '2026-12-31T00:00:00+08:00', 'finishHardConstraint': True}
                for pc, p in proj_seen.items()]

    # 资源组定义（从出现过的 rgId）
    group_ids = list(dict.fromkeys(op['rgId'] for e in cwp_map.values() for op in e['ops']))
    resource_groups = []
    for gid in group_ids:
        mode = classify_mode(gid)
        loc = location_of(gid)
        base = {'resourceGroupId': gid, 'resourceGroupName': RG_NAME_DEFAULT.get(gid, gid),
                'consumptionMode': mode, 'substituteResourceGroupIds': [],
                'locationCode': loc, 'locationName': LOCATION_NAMES.get(loc, loc)}
        if mode == 'CAPACITY':
            prof = next((v for k, v in CAPACITY_PROFILE.items() if k in gid.upper()), (5000, 6000))
            base['capacity'] = {'baselineAmount': prof[0], 'maxAmount': prof[1], 'unit': '标准工时', 'timeUnit': 'month'}
        else:
            regions = OCCUPANCY_REGIONS.get(gid, [gid])
            base['capacity'] = {'amount': len(regions), 'unit': '独立区域'}
            base['regions'] = [{'regionId': r, 'regionName': r} for r in regions]
        resource_groups.append(base)

    # 成本与人力
    labor_unit_costs = [{'locationCode': loc, 'amountPerPersonDay': LABOR_UNIT_COST[loc]} for loc in LABOR_UNIT_COST]
    resource_cost_rates = []
    for g in resource_groups:
        if g['consumptionMode'] == 'CAPACITY':
            resource_cost_rates.append({'resourceGroupId': g['resourceGroupId'], 'baselineUnitCost': 18,
                                        'overtimeUnitCost': 30, 'occupancyUnitCostPerDay': 0, 'blockUnitCostPerDay': 0})
        else:
            resource_cost_rates.append({'resourceGroupId': g['resourceGroupId'], 'baselineUnitCost': 0,
                                        'overtimeUnitCost': 0, 'occupancyUnitCostPerDay': 1200, 'blockUnitCostPerDay': 0})
    cost_model = {'enabled': True, 'scheduleDeviationCostPerDay': 500, 'lockViolationCostPerDay': 10000,
                  'laborUnitCosts': labor_unit_costs, 'resourceCostRates': resource_cost_rates}
    location_labor = {'enabled': True, 'locations': [
        {'locationCode': loc, 'locationName': LOCATION_NAMES[loc], 'maxLaborPerDay': LABOR_PER_DAY[loc]} for loc in LABOR_PER_DAY]}

    result = {'optimizationObjectives': {'enabled': True, 'objectives': []}, 'costModel': cost_model,
              'locationLaborConstraints': location_labor, 'projects': projects,
              'resourceBindingPolicy': {'resourceGroups': resource_groups}, 'cwps': cwps_records}

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"已生成：{out_path}")
    print(f"  项目数: {len(projects)}")
    print(f"  CWP 数: {len(cwps_records)}（过滤非真实项目 {skipped_fake}；无工序 {skipped_no_op}；缺工程量 {skipped_no_amt}）")
    print(f"  资源组数: {len(resource_groups)}（CAPACITY={sum(1 for g in resource_groups if g['consumptionMode']=='CAPACITY')}, OCCUPANCY={sum(1 for g in resource_groups if g['consumptionMode']=='OCCUPANCY_RATIO')}）")
    print(f"  依赖条数: {sum(len(c['dependencies']) for c in cwps_records)}")
    print(f"  资源组来源：真实填写 {filled_rg} 工序行，opCode 推断补全 {inferred_rg} 工序行")


if __name__ == '__main__':
    main()
