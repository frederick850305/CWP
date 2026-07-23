import { access, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const examplesDir = resolve(scriptDir, '../examples')
const templatePath = resolve(examplesDir, 'cwp-schedule-base.json')
const outputPath = resolve(examplesDir, 'cwp-schedule-test.json')

const PROJECT_COUNT = 15
const CWP_COUNT = 1000
const PROJECT_STAGGER_DAYS = 28
const CAPACITY_SCALE = 8

const clone = value => structuredClone(value)

function datePart(value) {
  return String(value).slice(0, 10)
}

function shiftDate(value, days) {
  if (!value) return value
  const date = new Date(`${datePart(value)}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return `${date.toISOString().slice(0, 10)}T00:00:00+08:00`
}

function daysBetween(start, end) {
  const startDate = new Date(`${datePart(start)}T00:00:00Z`)
  const endDate = new Date(`${datePart(end)}T00:00:00Z`)
  return Math.max(1, Math.round((endDate - startDate) / 86_400_000))
}

function round(value, digits = 2) {
  const scale = 10 ** digits
  return Math.round(value * scale) / scale
}

async function ensureBaseTemplate() {
  try {
    await access(templatePath)
  } catch {
    const current = JSON.parse(await readFile(outputPath, 'utf8'))
    if (current.projects?.length !== 2 || current.cwps?.length !== 64) {
      throw new Error(
        '缺少基准模板，且当前 cwp-schedule-test.json 不是已校准的 2 项目/64 CWP 数据，无法安全创建模板。',
      )
    }
    await writeFile(templatePath, `${JSON.stringify(current, null, 2)}\n`)
    console.log(`已保存基准模板：${templatePath}`)
  }
}

function scaleResourceGroups(groups) {
  return (groups ?? []).map(group => {
    const scaled = clone(group)
    if (Number.isFinite(scaled.maxCapacity)) scaled.maxCapacity *= CAPACITY_SCALE
    if (Number.isFinite(scaled.baselineCapacity)) scaled.baselineCapacity *= CAPACITY_SCALE
    if (Number.isFinite(scaled.maxLaborPerDay)) scaled.maxLaborPerDay *= CAPACITY_SCALE
    if (Array.isArray(scaled.regions)) {
      const regions = []
      for (let copyIndex = 1; copyIndex <= CAPACITY_SCALE; copyIndex += 1) {
        for (const region of scaled.regions) {
          regions.push({
            ...clone(region),
            regionCode: `${region.regionCode}-${String(copyIndex).padStart(2, '0')}`,
            regionName: `${region.regionName}-${String(copyIndex).padStart(2, '0')}`,
          })
        }
      }
      scaled.regions = regions
    }
    return scaled
  })
}

function cloneProcessRoute(processRoute, prefix) {
  if (!processRoute) return processRoute
  // Excel 基准数据会按计划天数重复展开相同工序。规模测试复制 CWP 时将完全相同的
  // 工序合并并累加占比，避免 1000 CWP 被放大成万级重复节点。
  const grouped = new Map()
  for (const operation of processRoute.operations ?? []) {
    const key = [
      operation.opCode,
      operation.opName,
      operation.resourceGroup?.resourceGroupId,
      operation.laborNorm?.workloadUnit,
    ].join('|')
    if (!grouped.has(key)) grouped.set(key, { ...clone(operation), workloadRatio: 0 })
    const item = grouped.get(key)
    item.workloadRatio += Number(operation.workloadRatio ?? 0)
  }
  const operations = [...grouped.values()]
  const ratioTotal = operations.reduce((sum, operation) => sum + operation.workloadRatio, 0) || 1
  return {
    ...clone(processRoute),
    routeCode: `${prefix}-${processRoute.routeCode}`,
    operations: operations.map((operation, index) => ({
      ...clone(operation),
      opCode: `${prefix}-${operation.opCode}-${String(index + 1).padStart(2, '0')}`,
      sequence: index + 1,
      workloadRatio: round(operation.workloadRatio / ratioTotal, 6),
    })),
  }
}

function scaleWorkload(workload, factor) {
  return {
    ...clone(workload),
    totalAmount: round(workload.totalAmount * factor),
  }
}

function cloneTemplateCwp(cwp, project, shiftDays, workloadFactor, codeSet) {
  const code = `${project.prefix}-${cwp.cwpCode}`
  return {
    ...clone(cwp),
    projectCode: project.projectCode,
    projectName: project.projectName,
    cwpCode: code,
    cwpName: `${project.projectName} · ${cwp.cwpName}`,
    plannedStart: shiftDate(cwp.plannedStart, shiftDays),
    plannedEnd: shiftDate(cwp.plannedEnd, shiftDays),
    workload: scaleWorkload(cwp.workload, workloadFactor),
    dependencies: (cwp.dependencies ?? [])
      .filter(dependency => codeSet.has(dependency.predecessorCwpCode))
      .map(dependency => ({
        ...clone(dependency),
        predecessorCwpCode: `${project.prefix}-${dependency.predecessorCwpCode}`,
      })),
    processRoute: cloneProcessRoute(cwp.processRoute, project.prefix),
  }
}

function createExtraCwps(template, project, shiftDays, count, workloadFactor) {
  const sourceCodes = ['A1580', 'A1660', 'A1850']
  const sources = sourceCodes.map(code => template.cwps.find(cwp => cwp.cwpCode === code))
  if (sources.some(source => !source)) {
    throw new Error(`基准模板缺少扩展链：${sourceCodes.join(' -> ')}`)
  }

  const result = []
  let start = shiftDate('2024-12-10T00:00:00+08:00', shiftDays)
  for (let index = 0; index < count; index += 1) {
    const source = sources[index]
    const duration = daysBetween(source.plannedStart, source.plannedEnd)
    const end = shiftDate(start, duration)
    const code = `${project.prefix}-EX-${source.cwpCode}`
    result.push({
      ...clone(source),
      projectCode: project.projectCode,
      projectName: project.projectName,
      cwpCode: code,
      cwpName: `${project.projectName} · 扩展作业面 · ${source.cwpName}`,
      plannedStart: start,
      plannedEnd: end,
      workload: {
        ...scaleWorkload(source.workload, workloadFactor * 0.7),
        progress: 0,
      },
      isLocked: false,
      dependencies:
        index === 0
          ? []
          : [
              {
                predecessorCwpCode: result[index - 1].cwpCode,
                relation: 'FS',
                lagDays: 0,
              },
            ],
      processRoute: cloneProcessRoute(source.processRoute, `${project.prefix}-EX`),
    })
    start = end
  }
  return result
}

function validate(output) {
  if (output.projects.length !== PROJECT_COUNT) {
    throw new Error(`项目数错误：${output.projects.length}`)
  }
  if (output.cwps.length !== CWP_COUNT) {
    throw new Error(`CWP 数错误：${output.cwps.length}`)
  }

  const projectCodes = new Set(output.projects.map(project => project.projectCode))
  // 多项目排程下，同一 cwpCode 可出现在不同项目中，故以 projectCode|cwpCode 复合键标识唯一性。
  const cwpByKey = new Map()
  for (const cwp of output.cwps) {
    if (!projectCodes.has(cwp.projectCode)) throw new Error(`未知项目：${cwp.cwpCode}`)
    const key = `${cwp.projectCode}|${cwp.cwpCode}`
    if (cwpByKey.has(key)) throw new Error(`CWP 复合编码重复：${key}`)
    cwpByKey.set(key, cwp)
  }

  const successors = new Map(output.cwps.map(cwp => [`${cwp.projectCode}|${cwp.cwpCode}`, []]))
  const indegree = new Map(output.cwps.map(cwp => [`${cwp.projectCode}|${cwp.cwpCode}`, 0]))
  const relationCounts = { FS: 0, SS: 0, FF: 0 }
  for (const cwp of output.cwps) {
    for (const dependency of cwp.dependencies ?? []) {
      const predKey = `${cwp.projectCode}|${dependency.predecessorCwpCode}`
      const predecessor = cwpByKey.get(predKey)
      // 前驱默认在本项目内解析；跨项目或不存在的前驱视为外部已完成任务，不参与拓扑成环。
      if (!predecessor) continue
      if (predecessor.cwpCode === cwp.cwpCode) throw new Error(`存在自依赖：${cwp.cwpCode}`)
      successors.get(predKey).push(`${cwp.projectCode}|${cwp.cwpCode}`)
      indegree.set(`${cwp.projectCode}|${cwp.cwpCode}`, indegree.get(`${cwp.projectCode}|${cwp.cwpCode}`) + 1)
      relationCounts[dependency.relation] = (relationCounts[dependency.relation] ?? 0) + 1
    }
  }

  const queue = [...indegree.entries()].filter(([, degree]) => degree === 0).map(([code]) => code)
  let visited = 0
  while (queue.length > 0) {
    const code = queue.shift()
    visited += 1
    for (const successor of successors.get(code)) {
      indegree.set(successor, indegree.get(successor) - 1)
      if (indegree.get(successor) === 0) queue.push(successor)
    }
  }
  if (visited !== output.cwps.length) throw new Error('依赖网络存在环路')

  return relationCounts
}

await ensureBaseTemplate()
const template = JSON.parse(await readFile(templatePath, 'utf8'))
if (template.projects?.length !== 2 || template.cwps?.length !== 64) {
  throw new Error('基准模板应为 2 个项目、64 条 CWP。')
}

const templateCodes = new Set(template.cwps.map(cwp => cwp.cwpCode))
const cwps = []
const projects = []
for (let projectIndex = 0; projectIndex < PROJECT_COUNT; projectIndex += 1) {
  const ordinal = String(projectIndex + 1).padStart(2, '0')
  const project = {
    prefix: `P${ordinal}`,
    projectCode: `LOAD-PROJECT-${ordinal}`,
    projectName: `海工规模化排程测试项目${ordinal}`,
  }
  const shiftDays = projectIndex * PROJECT_STAGGER_DAYS
  const workloadFactor = 0.94 + (projectIndex % 5) * 0.03
  const projectCwps = template.cwps.map(cwp =>
    cloneTemplateCwp(cwp, project, shiftDays, workloadFactor, templateCodes),
  )
  const extraCount = projectIndex < 10 ? 3 : 2
  projectCwps.push(...createExtraCwps(template, project, shiftDays, extraCount, workloadFactor))
  cwps.push(...projectCwps)

  const plannedStart = projectCwps.reduce(
    (minimum, cwp) => (cwp.plannedStart < minimum ? cwp.plannedStart : minimum),
    projectCwps[0].plannedStart,
  )
  const plannedEnd = projectCwps.reduce(
    (maximum, cwp) => (cwp.plannedEnd > maximum ? cwp.plannedEnd : maximum),
    projectCwps[0].plannedEnd,
  )
  projects.push({
    ...clone(template.projects[projectIndex % template.projects.length]),
    projectCode: project.projectCode,
    projectName: project.projectName,
    plannedStart,
    plannedEnd,
    projectPriority: 5 - (projectIndex % 5),
  })
}

const output = {
  ...clone(template),
  projects,
  cwps,
  resourceBindingPolicy: {
    ...clone(template.resourceBindingPolicy),
    resourceGroups: scaleResourceGroups(template.resourceBindingPolicy?.resourceGroups),
  },
  locationLaborConstraints: {
    ...clone(template.locationLaborConstraints),
    locations: (template.locationLaborConstraints?.locations ?? []).map(location => ({
      ...clone(location),
      maxLaborPerDay: Number.isFinite(location.maxLaborPerDay)
        ? location.maxLaborPerDay * CAPACITY_SCALE
        : location.maxLaborPerDay,
    })),
  },
}

const relationCounts = validate(output)
await writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`)

const cwpCounts = projects.map(project => ({
  projectCode: project.projectCode,
  count: cwps.filter(cwp => cwp.projectCode === project.projectCode).length,
}))
console.log(`已生成：${outputPath}`)
console.log(`项目：${projects.length}，CWP：${cwps.length}`)
console.log(`依赖：FS=${relationCounts.FS}, SS=${relationCounts.SS}, FF=${relationCounts.FF}`)
console.log(`项目 CWP 分布：${cwpCounts.map(item => item.count).join(', ')}`)
