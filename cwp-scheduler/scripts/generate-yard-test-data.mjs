import { writeFile } from 'node:fs/promises'

const outputPath = new URL('../examples/cwp-schedule-test.json', import.meta.url)

function isoDate(base, offsetDays) {
  const date = new Date(`${base}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offsetDays)
  return `${date.toISOString().slice(0, 10)}T00:00:00+08:00`
}

function capacityGroup(id, name, locationCode, locationName, baseline, maximum, facilityReference, substitutes = []) {
  return {
    resourceGroupId: id,
    resourceGroupName: name,
    consumptionMode: 'CAPACITY',
    substituteResourceGroupIds: substitutes,
    locationCode,
    locationName,
    facilityReference,
    capacity: { baselineAmount: baseline, maxAmount: maximum, unit: '标准工时', timeUnit: 'month' },
  }
}

function occupancyGroup(id, name, locationCode, locationName, regions, facilityReference, substitutes = []) {
  return {
    resourceGroupId: id,
    resourceGroupName: name,
    consumptionMode: 'OCCUPANCY_RATIO',
    substituteResourceGroupIds: substitutes,
    locationCode,
    locationName,
    facilityReference,
    regions: regions.map(([regionId, regionName]) => ({ regionId, regionName })),
  }
}

function assemblyGroup() {
  const phases = [1, 2].map(phase => ({
    phaseCode: `ASSEMBLY-PHASE-${phase}`,
    phaseName: `${phase}期总装场地`,
    stations: Array.from({ length: 8 }, (_, index) => ({
      stationCode: `PH${phase}-ST${index + 1}`,
      stationName: `${phase}期工位${index + 1}`,
    })),
  }))
  return {
    resourceGroupId: 'RG-ASSEMBLY-YARD',
    resourceGroupName: '总装场地工位组',
    consumptionMode: 'GRID_BLOCK',
    substituteResourceGroupIds: [],
    locationCode: 'LOC-ASSEMBLY',
    locationName: '总装场地',
    facilityReference: '2期场地，每期8个工位；每工位按2×2网格建模',
    capacity: { amount: 64, unit: '网格块' },
    assemblyLayout: {
      allowCrossStation: true,
      blockDefinition: { rows: 2, cols: 2, blockCountPerStation: 4 },
      phases,
    },
  }
}

const resourceGroups = [
  capacityGroup('RG-PLATE-CUT', '板材下料线', 'LOC-CUT', '下料车间', 6000, 7200, '厂房5940㎡，月产能约0.18万吨'),
  capacityGroup('RG-PROFILE-CUT', '型钢下料线', 'LOC-CUT', '下料车间', 5200, 6400, '厂房5940㎡，月产能约0.16万吨'),
  capacityGroup('RG-PREFAB-AUTO', '自动跨预制线', 'LOC-PREFAB', '结构预制车间', 3600, 4500, '厂房6480㎡，月产能约0.08万吨', ['RG-PREFAB-MANUAL-A', 'RG-PREFAB-MANUAL-B']),
  capacityGroup('RG-PREFAB-MANUAL-A', '手动跨预制线A', 'LOC-PREFAB', '结构预制车间', 4800, 5800, '手动跨预制车间8100㎡，两条并行作业线之一', ['RG-PREFAB-MANUAL-B', 'RG-PREFAB-AUTO']),
  capacityGroup('RG-PREFAB-MANUAL-B', '手动跨预制线B', 'LOC-PREFAB', '结构预制车间', 4800, 5800, '手动跨预制车间8100㎡，两条并行作业线之一', ['RG-PREFAB-MANUAL-A', 'RG-PREFAB-AUTO']),
  capacityGroup('RG-PIPE-SPOOL-A', '工艺管线预制线A', 'LOC-PIPE', '工艺管线预制车间', 7200, 8800, '车间13122㎡，两条管线预制作业线之一', ['RG-PIPE-SPOOL-B']),
  capacityGroup('RG-PIPE-SPOOL-B', '工艺管线预制线B', 'LOC-PIPE', '工艺管线预制车间', 7200, 8800, '车间13122㎡，两条管线预制作业线之一', ['RG-PIPE-SPOOL-A']),
  capacityGroup('RG-PIPE-MAKE-A', '制管生产线A', 'LOC-PIPE-MAKE', '制管车间', 5000, 6200, '制管车间1.1万㎡，两条制管线之一', ['RG-PIPE-MAKE-B']),
  capacityGroup('RG-PIPE-MAKE-B', '制管生产线B', 'LOC-PIPE-MAKE', '制管车间', 5000, 6200, '制管车间1.1万㎡，两条制管线之一', ['RG-PIPE-MAKE-A']),
  occupancyGroup('RG-BLAST', '喷砂车间组', 'LOC-BLAST', '喷砂车间', [
    ['BLAST-B1', '喷砂车间B1'], ['BLAST-B2', '喷砂车间B2'], ['BLAST-B3', '喷砂车间B3'],
  ], '3个喷砂车间，总面积约3072㎡'),
  occupancyGroup('RG-PAINT', '喷漆车间组', 'LOC-PAINT', '喷漆车间', [
    ['PAINT-P1', '喷漆车间P1'], ['PAINT-P2', '喷漆车间P2'], ['PAINT-P3', '喷漆车间P3'], ['PAINT-P4', '喷漆车间P4'],
  ], '4个喷漆车间，总面积约8160㎡'),
  occupancyGroup('RG-PREOUTFIT', '预舾装区域组', 'LOC-PREOUTFIT', '预舾装区', Array.from({ length: 6 }, (_, index) => [
    `PREOUTFIT-${index + 1}`, `预舾装区${index + 1}`,
  ]), '6个预舾装区域，配套辅助车道', ['RG-RESERVED-YARD']),
  occupancyGroup('RG-RESERVED-YARD', '预留场地区域组', 'LOC-RESERVED', '预留场地', [
    ['RESERVED-1', '1号预留地（8100㎡）'],
    ['RESERVED-2', '2号预留地（12000㎡）'],
    ['RESERVED-3', '3号预留地（23000㎡）'],
    ['RESERVED-4', '4号预留地（23000㎡）'],
    ['RESERVED-5', '5号预留地（8100㎡）'],
  ], '5块预留场地，可作为预舾装溢出资源', ['RG-PREOUTFIT']),
  assemblyGroup(),
]

const locations = [
  ['LOC-CUT', '下料车间', 60, 650],
  ['LOC-PREFAB', '结构预制车间', 90, 700],
  ['LOC-PIPE', '工艺管线预制车间', 70, 760],
  ['LOC-PIPE-MAKE', '制管车间', 55, 740],
  ['LOC-BLAST', '喷砂车间', 36, 680],
  ['LOC-PAINT', '喷漆车间', 48, 720],
  ['LOC-PREOUTFIT', '预舾装区', 72, 760],
  ['LOC-RESERVED', '预留场地', 40, 650],
  ['LOC-ASSEMBLY', '总装场地', 100, 850],
]

function operation(code, name, sequence, ratio, workloadPerPersonDay, resourceGroupId) {
  return {
    opCode: code,
    opName: name,
    sequence,
    workloadRatio: ratio,
    laborNorm: { workloadPerPersonDay, workloadUnit: '标准工时', laborUnit: '工日' },
    resourceGroup: { resourceGroupId },
  }
}

function dependency(predecessorCwpCode) {
  return [{ predecessorCwpCode, relation: 'FS', lag: 1 }]
}

function cwp({ code, name, project, priority, start, duration, workload, progress, dependencies, operations, occupancyRatio, assemblyUnit, locked = false }) {
  const item = {
    cwpCode: code,
    cwpName: name,
    projectCode: project.projectCode,
    projectName: project.projectName,
    projectPriority: priority,
    plannedStart: isoDate(start, 0),
    plannedEnd: isoDate(start, duration - 1),
    isLocked: locked,
    workload: { totalAmount: workload, progress, unit: '标准工时' },
    dependencies,
    processRoute: { routeCode: `ROUTE-${code}`, operations },
  }
  if (occupancyRatio) item.ratioOccupation = { occupancyRatio }
  if (assemblyUnit) item.assemblyUnit = assemblyUnit
  return item
}

const projects = []
const cwps = []
const projectBase = '2026-08-03'

for (let projectIndex = 0; projectIndex < 5; projectIndex += 1) {
  const projectNo = String(projectIndex + 1).padStart(2, '0')
  const projectStart = isoDate(projectBase, projectIndex * 56).slice(0, 10)
  const project = {
    projectCode: `PROJECT-OFFSHORE-${projectNo}`,
    projectName: `海工建造项目${projectNo}`,
    plannedEnd: isoDate(projectStart, 75),
    finishHardConstraint: true,
  }
  projects.push(project)

  for (let unitIndex = 0; unitIndex < 3; unitIndex += 1) {
    const unitNo = String(unitIndex + 1).padStart(2, '0')
    const unitCode = `UNIT-${projectNo}-${unitNo}`
    const unitName = `${project.projectName}单体${unitNo}`
    const unitStart = isoDate(projectStart, unitIndex * 10).slice(0, 10)
    const prefix = `CWP-${projectNo}-${unitNo}`
    const workloadFactor = 1 + projectIndex * 0.05 + unitIndex * 0.08
    const stage1 = `${prefix}-01`
    const stage2 = `${prefix}-02`
    const stage3 = `${prefix}-03`
    const stage4 = `${prefix}-04`
    const stage5 = `${prefix}-05`
    const gridShapes = [
      { rows: 1, cols: 2, blockCount: 2, layout: '1x2' },
      { rows: 2, cols: 2, blockCount: 4, layout: '2x2' },
      { rows: 1, cols: 4, blockCount: 4, layout: '1x4' },
    ]

    cwps.push(cwp({
      code: stage1, name: `${unitName}—材料下料`, project, priority: 5 - projectIndex,
      start: unitStart, duration: 7, workload: Math.round(1200 * workloadFactor), progress: unitIndex === 0 ? 0.1 : 0,
      dependencies: [], locked: unitIndex === 0,
      operations: [
        operation('OP-PLATE-CUT', '板材下料', 10, 0.65, 14, 'RG-PLATE-CUT'),
        operation('OP-PROFILE-CUT', '型钢下料', 20, 0.35, 13, 'RG-PROFILE-CUT'),
      ],
    }))
    cwps.push(cwp({
      code: stage2, name: `${unitName}—结构与管线预制`, project, priority: 5 - projectIndex,
      start: isoDate(unitStart, 8).slice(0, 10), duration: 10, workload: Math.round(1900 * workloadFactor), progress: 0,
      dependencies: dependency(stage1),
      operations: [
        operation('OP-PREFAB', '结构预制', 10, 0.55, 12, unitIndex === 0 ? 'RG-PREFAB-AUTO' : 'RG-PREFAB-MANUAL-A'),
        operation('OP-PIPE-SPOOL', '工艺管线预制', 20, 0.25, 11, 'RG-PIPE-SPOOL-A'),
        operation('OP-PIPE-MAKE', '制管', 30, 0.20, 12, 'RG-PIPE-MAKE-A'),
      ],
    }))
    cwps.push(cwp({
      code: stage3, name: `${unitName}—喷砂`, project, priority: 5 - projectIndex,
      start: isoDate(unitStart, 19).slice(0, 10), duration: 4, workload: Math.round(420 * workloadFactor), progress: 0,
      dependencies: dependency(stage2), occupancyRatio: 0.45 + unitIndex * 0.1,
      operations: [operation('OP-BLAST', '表面喷砂处理', 10, 1, 10, 'RG-BLAST')],
    }))
    cwps.push(cwp({
      code: stage4, name: `${unitName}—喷漆`, project, priority: 5 - projectIndex,
      start: isoDate(unitStart, 24).slice(0, 10), duration: 5, workload: Math.round(520 * workloadFactor), progress: 0,
      dependencies: dependency(stage3), occupancyRatio: 0.4 + unitIndex * 0.08,
      operations: [operation('OP-PAINT', '表面喷漆处理', 10, 1, 10, 'RG-PAINT')],
    }))
    cwps.push(cwp({
      code: stage5, name: `${unitName}—预舾装与总装`, project, priority: 5 - projectIndex,
      start: isoDate(unitStart, 30).slice(0, 10), duration: 14, workload: Math.round(2300 * workloadFactor), progress: 0,
      dependencies: dependency(stage4), occupancyRatio: 0.5 + unitIndex * 0.1,
      assemblyUnit: { unitCode, unitName, requiredBlocks: gridShapes[unitIndex] },
      operations: [
        operation('OP-PREOUTFIT', '预舾装', 10, 0.35, 12, 'RG-PREOUTFIT'),
        operation('OP-ASSEMBLY', '总装场地合拢', 20, 0.65, 11, 'RG-ASSEMBLY-YARD'),
      ],
    }))
  }
}

const result = {
  optimizationObjectives: { enabled: true, objectives: [] },
  costModel: {
    enabled: true,
    scheduleDeviationCostPerDay: 500,
    lockViolationCostPerDay: 10000,
    laborUnitCosts: locations.map(([locationCode, , , amountPerPersonDay]) => ({ locationCode, amountPerPersonDay })),
    resourceCostRates: resourceGroups.map(group => ({
      resourceGroupId: group.resourceGroupId,
      baselineUnitCost: group.consumptionMode === 'CAPACITY' ? 18 : 0,
      overtimeUnitCost: group.consumptionMode === 'CAPACITY' ? 30 : 0,
      occupancyUnitCostPerDay: group.consumptionMode === 'OCCUPANCY_RATIO' ? 1200 : 0,
      blockUnitCostPerDay: group.consumptionMode === 'GRID_BLOCK' ? 600 : 0,
    })),
  },
  locationLaborConstraints: {
    enabled: true,
    locations: locations.map(([locationCode, locationName, maxLaborPerDay]) => ({ locationCode, locationName, maxLaborPerDay })),
  },
  projects,
  resourceBindingPolicy: { resourceGroups },
  cwps,
}

await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
console.log(`已生成 ${projects.length} 个项目、${projects.length * 3} 个单体、${cwps.length} 个 CWP、${resourceGroups.length} 类资源`)
