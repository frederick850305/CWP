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
from datetime import datetime, timedelta

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
    'CUT':   (22000, 26000),
    'YARD':  (14000, 18000),
    'OR':    (16000, 20000),
    'VR':    (16000, 20000),
}
OCCUPANCY_REGIONS = {
    'BLASTING-SHOP-1': ['BLAST-B1-A', 'BLAST-B1-B'],
    'BLASTING-SHOP-2': ['BLAST-B2-A', 'BLAST-B2-B'],
    'BLASTING-SHOP-3': ['BLAST-B3-A', 'BLAST-B3-B'],
    'VR-INS-GE':       ['INS-GE-1', 'INS-GE-2', 'INS-GE-3'],
    'YARD-INS':        ['YARD-INS-1', 'YARD-INS-2', 'YARD-INS-3', 'YARD-INS-4', 'YARD-INS-5', 'YARD-INS-6'],
    'YARD-PER-INS':    ['PER-INS-1', 'PER-INS-2', 'PER-INS-3', 'PER-INS-4'],
}
LOCATION_OF = {
    'CUT': 'LOC-CUT', 'BLAST': 'LOC-BLAST', 'INS': 'LOC-OUTFIT',
    'FAB': 'LOC-FAB', 'YARD': 'LOC-FAB', 'OR': 'LOC-PIPE', 'VR': 'LOC-PIPE',
}
LOCATION_NAMES = {
    'LOC-CUT': '下料车间', 'LOC-BLAST': '喷砂车间', 'LOC-OUTFIT': '舾装区',
    'LOC-FAB': '结构预制车间', 'LOC-PIPE': '工艺管线预制车间',
}
LABOR_PER_DAY = {'LOC-CUT': 1100, 'LOC-BLAST': 1600, 'LOC-OUTFIT': 3200, 'LOC-FAB': 2200, 'LOC-PIPE': 2200}
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

# 基于海工建造的主工艺顺序补强内部 FS 关系。Excel 原数据中部分关系是
# 高层级 SS/FF 控制点，或指向未纳入示例的外部 CWP；下列链条用于表达可排程的
# “预制完成 -> 下一层片/专业开始 -> 预舾装/安装 -> 涂装/完工”关键交付链。
REALISTIC_FS_CHAINS = (
    # 蓬莱19-3：C 片组合梁、甲板片和专业预舾装
    ('C1110', 'C1150', 'C1160'),
    ('C1000', 'C3450', 'C3460', 'C3470', 'C3480', 'C13470', 'C3590', 'C2430', 'C2440'),
    ('C1730', 'C13470'),
    ('C2230', 'C2430'),
    ('C2460', 'C2560'),
    # 蓬莱19-3：D 片甲板片分段、仪表电缆和称重交付
    ('D1000', 'D3450', 'D3460', 'D4060', 'D3470', 'D3480', 'D5920', 'D3590', 'D3720'),
    ('D1730', 'D3590'),
    ('D2220', 'D2390', 'D2430', 'D3240', 'D3720'),
    ('D2460', 'D2430'),
    # 蓬莱19-3：E 片按标高逐层预制、预舾装
    ('E3450', 'E3460', 'E4060', 'E3470', 'E3480', 'E3590', 'E5920'),
    # 曹妃甸：ROW 片、附件安装、涂装和完工
    ('A1020', 'A1030', 'A1980'),
    ('A1580', 'A1660', 'A1850', 'A1980'),
    ('A1990', 'A1450', 'A1650'),
    ('A1200', 'A1450', 'A1500', 'A1680', 'A1950', 'A1980'),
    ('A1440', 'A1500'),
    ('A2070', 'A1430', 'A1680'),
    ('A1860', 'A1980'),
    ('A1300', 'A1410', 'A1980'),
    ('A2990', 'A1450'),
    ('F2160', 'A1980'),
    ('F2200', 'A1980'),
)

# Excel 中个别关系的方向/专业语义不适合作为该示例的内部排程约束。
DROP_INTERNAL_DEPENDENCIES = {
    ('A1500', 'A1450'),  # 包板/阳极安装应在 ROW 片预制完成后开始
    ('A1450', 'A1030'),  # 防沉板预制与 ROW 片主链解耦
    ('A1450', 'A1680'),  # 已由 A1450 -> A1500/A1650 -> A1680 传递
    ('C1000', 'C13470'), # 已由 C1000 -> C3450...C3480 -> C13470 传递
    ('E3450', 'E4060'),  # 已由 E3450 -> E3460 -> E4060 传递
    ('E3450', 'E3470'),  # 移除原 Excel 中冗余的 FS -1 天捷径
    ('E3460', 'E3480'),  # 已由 E3460 -> E4060 -> E3470 -> E3480 传递
    ('E3470', 'E5920'),  # 已由 E3470 -> E3480 -> E3590 -> E5920 传递
}


def enrich_realistic_fs_dependencies(cwps_records):
    """在保留 Excel 原始关系的基础上，补齐可执行的内部 FS 主链。

    返回 (added, converted, removed)，并在发现跨项目关系或依赖环时立即报错。
    """
    by_code = {c['cwpCode']: c for c in cwps_records}
    added = converted = removed = 0

    for cwp in cwps_records:
        cleaned = []
        for dep in cwp.get('dependencies', []):
            pair = (dep['predecessorCwpCode'], cwp['cwpCode'])
            if pair in DROP_INTERNAL_DEPENDENCIES:
                removed += 1
                continue
            cleaned.append(dep)
        cwp['dependencies'] = cleaned

    for chain in REALISTIC_FS_CHAINS:
        for predecessor, successor in zip(chain, chain[1:]):
            if predecessor not in by_code or successor not in by_code:
                continue
            pred = by_code[predecessor]
            succ = by_code[successor]
            if pred['projectCode'] != succ['projectCode']:
                raise ValueError(f'FS 关系不得跨项目: {predecessor} -> {successor}')
            existing = next((d for d in succ['dependencies']
                             if d['predecessorCwpCode'] == predecessor), None)
            if existing:
                if existing.get('relation') != 'FS' or existing.get('lag', 0) != 0:
                    existing['relation'] = 'FS'
                    existing['lag'] = 0
                    converted += 1
            else:
                succ['dependencies'].append({
                    'predecessorCwpCode': predecessor,
                    'relation': 'FS',
                    'lag': 0,
                })
                added += 1

    # 去重并固定输出顺序，便于审阅 diff 和重复生成。
    for cwp in cwps_records:
        unique = {}
        for dep in cwp.get('dependencies', []):
            key = (dep['predecessorCwpCode'], dep['relation'], dep.get('lag', 0))
            unique[key] = dep
        cwp['dependencies'] = [unique[key] for key in sorted(unique)]

    # 仅对示例内部关系做 DAG 校验；外部前驱依旧作为已完成边界条件。
    successors = defaultdict(list)
    indegree = {code: 0 for code in by_code}
    for succ in cwps_records:
        for dep in succ.get('dependencies', []):
            pred_code = dep['predecessorCwpCode']
            if pred_code in by_code:
                successors[pred_code].append(succ['cwpCode'])
                indegree[succ['cwpCode']] += 1
    ready = [code for code, degree in indegree.items() if degree == 0]
    visited = 0
    while ready:
        code = ready.pop()
        visited += 1
        for successor in successors[code]:
            indegree[successor] -= 1
            if indegree[successor] == 0:
                ready.append(successor)
    if visited != len(by_code):
        cyclic = sorted(code for code, degree in indegree.items() if degree > 0)
        raise ValueError(f'补强 FS 后出现依赖环: {cyclic}')

    return added, converted, removed


def realistic_duration_days(cwp_name):
    """按海工 CWP 工序类型给出可用于排程演示的典型日历工期。"""
    name = cwp_name or ''
    if '陆地建造完工' in name:
        return 5
    if '称重' in name:
        return 7
    if '补漆' in name:
        return 15
    if '涂装' in name or '喷漆' in name:
        return 25
    if '电缆敷设' in name:
        return 20
    if '安装' in name or '总装' in name:
        return 20
    if '预舾装' in name:
        return 30
    if '甲板片' in name or 'ROW片预制' in name:
        return 45
    if '组合梁预制' in name:
        return 35
    if '预制' in name:
        return 25
    if '卷制' in name or '接长' in name:
        return 30
    return 25


def normalize_realistic_planned_windows(cwps_records):
    """按依赖约束重建 CWP 计划窗口，避免 Excel 高层计划日期被误当作作业工期。"""
    by_code = {c['cwpCode']: c for c in cwps_records}
    project_bases = {}
    for cwp in cwps_records:
        start = datetime.fromisoformat(cwp['plannedStart']).date()
        project = cwp['projectCode']
        if project not in project_bases or start < project_bases[project]:
            project_bases[project] = start

    successors = defaultdict(list)
    indegree = {code: 0 for code in by_code}
    for succ in cwps_records:
        for dep in succ.get('dependencies', []):
            pred_code = dep['predecessorCwpCode']
            if pred_code in by_code:
                successors[pred_code].append(succ['cwpCode'])
                indegree[succ['cwpCode']] += 1

    ready = sorted(code for code, degree in indegree.items() if degree == 0)
    order = []
    while ready:
        code = ready.pop(0)
        order.append(code)
        for successor in sorted(successors[code]):
            indegree[successor] -= 1
            if indegree[successor] == 0:
                ready.append(successor)
                ready.sort()
    if len(order) != len(by_code):
        raise ValueError('无法按依赖关系生成计划窗口：存在内部依赖环')

    scheduled = {}
    stream_offsets = {'A': 0, 'C': 0, 'D': 45, 'E': 90, 'F': 30}
    for code in order:
        cwp = by_code[code]
        duration = realistic_duration_days(cwp.get('cwpName'))
        start = project_bases[cwp['projectCode']] + timedelta(days=stream_offsets.get(code[0], 0))
        for dep in cwp.get('dependencies', []):
            pred = scheduled.get(dep['predecessorCwpCode'])
            if not pred:
                continue
            lag = dep.get('lag', 0)
            relation = dep.get('relation', 'FS')
            if relation == 'FS':
                required = pred['end'] + timedelta(days=lag + 1)
            elif relation == 'SS':
                required = pred['start'] + timedelta(days=lag)
            elif relation == 'FF':
                required = pred['end'] + timedelta(days=lag - duration + 1)
            else:  # SF
                required = pred['start'] + timedelta(days=lag - duration + 1)
            if required > start:
                start = required
        end = start + timedelta(days=duration - 1)
        scheduled[code] = {'start': start, 'end': end}
        cwp['plannedStart'] = f'{start.isoformat()}T00:00:00+08:00'
        cwp['plannedEnd'] = f'{end.isoformat()}T00:00:00+08:00'

    return min(v['start'] for v in scheduled.values()), max(v['end'] for v in scheduled.values())


def synchronize_project_planned_ends(projects, cwps_records):
    """将项目计划完工日与其最后一个 CWP 交付日对齐，使关键路径有明确零时差终点。"""
    latest_by_project = {}
    for cwp in cwps_records:
        project = cwp['projectCode']
        end = cwp['plannedEnd']
        if project not in latest_by_project or end > latest_by_project[project]:
            latest_by_project[project] = end
    for project in projects:
        if project['projectCode'] in latest_by_project:
            project['plannedEnd'] = latest_by_project[project['projectCode']]


def normalize_realistic_resource_capacity(result):
    """将 Excel 缺失后的示例资源假设校准到当前工程量级。"""
    groups = result['resourceBindingPolicy']['resourceGroups']
    for group in groups:
        gid = group['resourceGroupId']
        if group['consumptionMode'] == 'CAPACITY':
            profile = next((values for token, values in CAPACITY_PROFILE.items()
                            if token in gid.upper()), (14000, 18000))
            group['capacity']['baselineAmount'] = profile[0]
            group['capacity']['maxAmount'] = profile[1]
        else:
            regions = OCCUPANCY_REGIONS.get(gid)
            if regions:
                group['capacity']['amount'] = len(regions)
                group['regions'] = [{'regionId': region, 'regionName': region} for region in regions]

    for location in result['locationLaborConstraints']['locations']:
        code = location['locationCode']
        if code in LABOR_PER_DAY:
            location['maxLaborPerDay'] = LABOR_PER_DAY[code]


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
    if len(sys.argv) > 1 and sys.argv[1] == '--enrich-existing':
        out_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
        with open(out_path, 'r', encoding='utf-8') as f:
            result = json.load(f)
        added, converted, removed = enrich_realistic_fs_dependencies(result['cwps'])
        window_start, window_end = normalize_realistic_planned_windows(result['cwps'])
        synchronize_project_planned_ends(result['projects'], result['cwps'])
        normalize_realistic_resource_capacity(result)
        with open(out_path, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
            f.write('\n')
        print(f'已补强示例 FS 依赖: {out_path}')
        print(f'  新增 FS: {added}，转换为 FS: {converted}，移除不合理内部关系: {removed}')
        print(f'  重建计划窗口: {window_start} -> {window_end}')
        return

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

    added_fs, converted_fs, removed_dependencies = enrich_realistic_fs_dependencies(cwps_records)
    normalize_realistic_planned_windows(cwps_records)

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
    print(f"  FS 补强: 新增 {added_fs}，转换 {converted_fs}，移除不合理关系 {removed_dependencies}")
    print(f"  资源组来源：真实填写 {filled_rg} 工序行，opCode 推断补全 {inferred_rg} 工序行")


if __name__ == '__main__':
    main()
