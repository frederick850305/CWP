<script setup>
import { computed, onMounted, ref } from 'vue'
import sampleInput from '../../examples/cwp-schedule-test.json'

const API = '/api/v1/cwp-schedule-jobs'
const RULE_API = '/api/v1/solver-rules'
const viewMode = ref('dashboard')
const jobs = ref([])
const currentJob = ref(null)
const result = ref(null)
const loading = ref(false)
const jobsLoading = ref(false)
const error = ref('')
const toast = ref('')
const activeTab = ref('overview')
const jobQuery = ref('')
const fileInput = ref(null)
const rulesState = ref({ rules: {}, supportedCommands: [], history: [] })
const ruleMessage = ref('')
const ruleProposal = ref(null)
const ruleBusy = ref(false)
const ruleConversation = ref([
  { role: 'assistant', text: '告诉我你希望怎样调整排程策略。我会先生成结构化变更预览，只有确认后才会生效。' },
])

// 排程算法选择与后台路由
const algorithms = ref([])
const selectedAlgorithm = ref('default')

const tabs = [
  { id: 'overview', label: '排程总览' },
  { id: 'gantt', label: 'CWP 甘特图' },
  { id: 'resources', label: '资源与人力' },
  { id: 'conflicts', label: '冲突诊断' },
]

const summary = computed(() => result.value?.scheduleSummary ?? {})
const metrics = computed(() => summary.value.objectiveMetrics ?? {})
const tasks = computed(() => result.value?.cwpGanttOutput?.tasks ?? [])
const utilizations = computed(() => result.value?.monthlyWorkshopUtilization ?? [])
const conflicts = computed(() => result.value?.resourceConflictList ?? [])
const projects = computed(() => result.value?.projectCriticalPathOutput?.projects ?? [])
const laborRows = computed(() => (result.value?.monthlyLaborDemandCurve ?? []).flatMap(month =>
  (month.byLocation ?? []).map(row => ({ ...row, month: month.month }))))
const maxLabor = computed(() => Math.max(1, ...laborRows.value.map(item => Number(item.demand))))

// 甘特图项目筛选
const selectedProject = ref('all')

/** 甘特图可选项目列表（按 projectCode 去重）。 */
const projectOptions = computed(() => {
  const seen = new Map()
  tasks.value.forEach(task => {
    if (task.projectCode && !seen.has(task.projectCode)) {
      seen.set(task.projectCode, task.projectName || task.projectCode)
    }
  })
  return Array.from(seen.entries()).map(([code, name]) => ({ code, name }))
})

/** 按当前选中项目过滤后的 CWP 任务。 */
const filteredTasks = computed(() => {
  if (selectedProject.value === 'all') return tasks.value
  return tasks.value.filter(task => task.projectCode === selectedProject.value)
})

// 资源类型筛选与分组条形图数据
const selectedResourceType = ref('all')

/** 所有资源类型（resourceGroupName）去重列表。 */
const resourceTypeOptions = computed(() => {
  const names = new Set(utilizations.value.map(item => item.resourceGroupName).filter(Boolean))
  return Array.from(names)
})

/** 把利用率与人员需求按 location+month 关联，并按当前选中的资源类型聚合。 */
const resourceTypeChartData = computed(() => {
  if (!utilizations.value.length) return []
  const laborByLocationMonth = new Map()
  laborRows.value.forEach(row => {
    laborByLocationMonth.set(`${row.locationName}|${row.month}`, Number(row.demand || 0))
  })
  const combined = utilizations.value.map(item => {
    const laborDemand = laborByLocationMonth.get(`${item.locationName}|${item.month}`) || 0
    return {
      resourceGroupName: item.resourceGroupName,
      locationName: item.locationName,
      month: item.month,
      utilization: Number(item.utilizationRate || 0),
      laborDemand,
      usedAmount: item.usedAmount,
      totalCapacity: item.totalCapacity
    }
  })

  if (selectedResourceType.value === 'all') {
    // 全部：按资源类型聚合，取平均值
    const groups = new Map()
    combined.forEach(item => {
      if (!groups.has(item.resourceGroupName)) {
        groups.set(item.resourceGroupName, { label: item.resourceGroupName, utilSum: 0, laborSum: 0, count: 0 })
      }
      const g = groups.get(item.resourceGroupName)
      g.utilSum += item.utilization
      g.laborSum += item.laborDemand
      g.count += 1
    })
    return Array.from(groups.values()).map(g => ({
      label: g.label,
      utilization: g.utilSum / g.count,
      laborDemand: g.laborSum / g.count
    }))
  }

  // 单选资源类型：按月份展开
  return combined
    .filter(item => item.resourceGroupName === selectedResourceType.value)
    .sort((a, b) => a.month.localeCompare(b.month))
    .map(item => ({
      label: item.month,
      utilization: item.utilization,
      laborDemand: item.laborDemand
    }))
})

/** 条形图右侧人员需求轴的最大值（利用率固定以 100% 为满刻度）。 */
const resourceChartMax = computed(() => ({
  utilization: 1,
  labor: Math.max(1, ...resourceTypeChartData.value.map(d => d.laborDemand))
}))
const completedJobs = computed(() => jobs.value.filter(job => job.status === 'COMPLETED'))
const ruleCards = computed(() => {
  const rules = rulesState.value.rules ?? {}
  return [
    { label: '同层任务排序', value: rules.orderingMode === 'PLANNED_START_FIRST' ? '计划日期优先' : '项目优先级优先' },
    { label: '替代资源', value: rules.allowResourceSubstitution ? '允许' : '禁止' },
    { label: '关键任务前置链', value: rules.lockCriticalPredecessors ? '联动锁定' : '不扩展锁定' },
    { label: '同成本日期', value: rules.preferEarlierOnTie ? '优先较早' : '优先较晚' },
    { label: '产能安全上限', value: `${Number(rules.capacitySafetyFactor ?? 1) * 100}%` },
    { label: '人力安全上限', value: `${Number(rules.laborSafetyFactor ?? 1) * 100}%` },
    { label: '无解处理', value: rules.diagnosticFallback ? '生成诊断方案' : '任务失败' },
  ]
})

const ganttRange = computed(() => {
  if (!filteredTasks.value.length) return null
  const dates = filteredTasks.value.flatMap(task => [task.plannedStart, task.plannedEnd, task.scheduledStart, task.scheduledEnd])
    .filter(Boolean).map(toDay)
  const start = new Date(Math.min(...dates))
  const end = new Date(Math.max(...dates))
  const days = dayDiff(start, end) + 1
  const interval = Math.max(1, Math.ceil(days / 8))
  const ticks = []
  for (let index = 0; index < days; index += interval) {
    const date = new Date(start)
    date.setUTCDate(date.getUTCDate() + index)
    ticks.push({ offset: index / days * 100, label: formatShortDate(date) })
  }
  return { start, end, days, ticks }
})

function toDay(value) {
  return new Date(`${String(value).slice(0, 10)}T00:00:00Z`).getTime()
}

function dayDiff(left, right) {
  return Math.round((right.getTime() - left.getTime()) / 86400000)
}

function ganttStyle(task, type) {
  if (!ganttRange.value) return {}
  const start = new Date(toDay(task[type === 'planned' ? 'plannedStart' : 'scheduledStart']))
  const end = new Date(toDay(task[type === 'planned' ? 'plannedEnd' : 'scheduledEnd']))
  const left = dayDiff(ganttRange.value.start, start) / ganttRange.value.days * 100
  const width = (dayDiff(start, end) + 1) / ganttRange.value.days * 100
  return { left: `${left}%`, width: `${Math.max(width, 1.2)}%` }
}

async function request(url, options) {
  const response = await fetch(url, options)
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const details = body.errors?.join('；') || body.message || body.failureReason || `请求失败（HTTP ${response.status}）`
    throw new Error(details)
  }
  return body
}

async function loadRules() {
  try {
    rulesState.value = await request(RULE_API)
  } catch (cause) {
    error.value = cause.message
  }
}

async function loadAlgorithms() {
  try {
    algorithms.value = await request(`${API}/algorithms`)
    // 若当前选中项不在清单中（如后台尚未就绪），回退到首个算法。
    if (!algorithms.value.some(alg => alg.code === selectedAlgorithm.value)) {
      selectedAlgorithm.value = algorithms.value[0]?.code || 'default'
    }
  } catch (cause) {
    // 算法清单获取失败时静默回退，不阻断其它功能。
  }
}

async function interpretRule(message = ruleMessage.value) {
  const text = message.trim()
  if (!text || ruleBusy.value) return
  ruleBusy.value = true
  error.value = ''
  ruleProposal.value = null
  ruleConversation.value.push({ role: 'user', text })
  ruleMessage.value = ''
  try {
    const proposal = await request(`${RULE_API}/interpret`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: text }),
    })
    ruleProposal.value = proposal
    ruleConversation.value.push({ role: 'assistant', text: proposal.message })
  } catch (cause) {
    error.value = cause.message
    ruleConversation.value.push({ role: 'assistant', text: cause.message, error: true })
  } finally {
    ruleBusy.value = false
  }
}

async function applyRuleProposal() {
  if (!ruleProposal.value || ruleBusy.value) return
  ruleBusy.value = true
  error.value = ''
  try {
    const state = await request(`${RULE_API}/proposals/${ruleProposal.value.proposalId}/apply`, { method: 'POST' })
    rulesState.value = state
    ruleConversation.value.push({ role: 'assistant', text: state.message })
    ruleProposal.value = null
    showToast(state.message)
  } catch (cause) {
    error.value = cause.message
  } finally {
    ruleBusy.value = false
  }
}

function ruleValue(value) {
  if (typeof value === 'boolean') return value ? '开启' : '关闭'
  if (value === 'PRIORITY_FIRST') return '项目优先级优先'
  if (value === 'PLANNED_START_FIRST') return '计划日期优先'
  if (typeof value === 'number' && value <= 1) return `${value * 100}%`
  return String(value)
}

async function loadJobs(selectLatest = false) {
  jobsLoading.value = true
  try {
    jobs.value = await request(API)
    const remembered = localStorage.getItem('cwp-last-job-id')
    if (!currentJob.value && jobs.value.length) {
      const initial = jobs.value.find(job => job.jobId === remembered && job.status === 'COMPLETED')
        || (selectLatest ? completedJobs.value[0] : null)
      if (initial) await openJob(initial.jobId)
    }
  } catch (cause) {
    error.value = cause.message
  } finally {
    jobsLoading.value = false
  }
}

async function openJob(jobId) {
  if (!jobId) return
  loading.value = true
  error.value = ''
  try {
    const status = await request(`${API}/${jobId.trim()}`)
    currentJob.value = status
    jobQuery.value = status.jobId
    localStorage.setItem('cwp-last-job-id', status.jobId)
    if (status.status === 'COMPLETED') {
      result.value = await request(`${API}/${status.jobId}/result`)
      selectedProject.value = 'all'
      if (status.algorithm) selectedAlgorithm.value = status.algorithm
      activeTab.value = 'overview'
    } else if (status.status === 'FAILED') {
      result.value = null
      throw new Error(status.failureReason || '排程任务执行失败')
    } else {
      result.value = null
      await pollJob(status.jobId)
    }
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function pollJob(jobId) {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 500))
    const status = await request(`${API}/${jobId}`)
    currentJob.value = status
    if (status.status === 'COMPLETED') {
      result.value = await request(`${API}/${jobId}/result`)
      await loadJobs()
      return
    }
    if (status.status === 'FAILED') throw new Error(status.failureReason || '排程任务执行失败')
  }
  throw new Error('排程任务等待超时，请稍后从最近任务中重新打开')
}

async function submitInput(input, sourceName) {
  loading.value = true
  error.value = ''
  result.value = null
  try {
    const job = await request(API, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ algorithm: selectedAlgorithm.value, input }),
    })
    currentJob.value = job
    jobQuery.value = job.jobId
    localStorage.setItem('cwp-last-job-id', job.jobId)
    showToast(`${sourceName}已提交，正在计算排程`)
    await pollJob(job.jobId)
    showToast('排程完成，已更新可视化结果')
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

function runSample() {
  submitInput(sampleInput, '示例数据')
}

async function handleFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const input = JSON.parse(await file.text())
    await submitInput(input, file.name)
  } catch (cause) {
    error.value = cause instanceof SyntaxError ? '所选文件不是有效的 JSON 文档' : cause.message
  } finally {
    event.target.value = ''
  }
}

function showToast(message) {
  toast.value = message
  window.setTimeout(() => { if (toast.value === message) toast.value = '' }, 3200)
}

async function downloadResult() {
  const jobId = currentJob.value?.jobId
  if (!result.value || !jobId) return
  try {
    const response = await fetch(`${API}/${jobId}/result/download`)
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new Error(body.message || body.failureReason || `导出失败（HTTP ${response.status}）`)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `cwp-schedule-${jobId}.json`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    showToast('排程结果 JSON 已导出')
  } catch (cause) {
    error.value = cause.message
  }
}

function formatMoney(value) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(Number(value ?? 0))
}

function formatPercent(value) {
  return `${(Number(value ?? 0) * 100).toFixed(1)}%`
}

function formatDate(value) {
  if (!value) return '—'
  return String(value).slice(0, 10)
}

function formatDateTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    .format(new Date(value))
}

/** 甘特图时间轴刻度：显示完整年月日，便于识别具体日期。 */
function formatShortDate(date) {
  const y = date.getUTCFullYear()
  const m = String(date.getUTCMonth() + 1).padStart(2, '0')
  const d = String(date.getUTCDate()).padStart(2, '0')
  return `${y}/${m}/${d}`
}

function shortId(id) {
  return id ? `${id.slice(0, 8)}…` : '—'
}

function statusText(status) {
  return { QUEUED: '排队中', SOLVING: '计算中', COMPLETED: '已完成', FAILED: '失败' }[status] ?? status
}

function conflictType(type) {
  return { CAPACITY: '产能', LABOR: '人力', GRID: '工位', OCCUPANCY: '占用率',
           DEADLINE: '项目延期', WINDOW: '无可行窗口', LOCKED: '锁定冲突',
           RESOURCE: '资源不足', DEPENDENCY: '依赖违反' }[type] ?? type
}

onMounted(() => { loadJobs(true); loadRules(); loadAlgorithms() })
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="CWP 排程驾驶舱首页">
        <span class="brand-mark"><i></i><i></i><i></i></span>
        <span><strong>CWP</strong><small>排程驾驶舱</small></span>
      </a>
      <div class="top-actions">
        <nav class="primary-nav" aria-label="主导航">
          <button :class="{ active: viewMode === 'dashboard' }" @click="viewMode = 'dashboard'">排程看板</button>
          <button :class="{ active: viewMode === 'rules' }" @click="viewMode = 'rules'; loadRules()">规则助手</button>
        </nav>
        <label v-if="viewMode === 'dashboard'" class="job-search">
          <span>任务 ID</span>
          <input v-model="jobQuery" placeholder="输入任务 ID" @keyup.enter="openJob(jobQuery)" />
          <button type="button" @click="openJob(jobQuery)" :disabled="!jobQuery || loading">打开</button>
        </label>
        <button v-if="viewMode === 'dashboard'" class="icon-button" type="button" title="刷新任务列表" @click="loadJobs()" :disabled="jobsLoading">
          <span :class="{ spinning: jobsLoading }">↻</span>
        </button>
      </div>
    </header>

    <main v-if="viewMode === 'dashboard'">
      <section class="hero">
        <div class="hero-copy">
          <p class="eyebrow"><span></span> OFFSHORE APS · LEVEL 4</p>
          <h1>把复杂排程，变成<br /><em>一眼可见的确定性</em></h1>
          <p class="hero-description">集中查看计划偏差、资源负荷、人力需求与约束冲突，让每个 CWP 的安排都有据可查。</p>
        </div>
        <div class="hero-actions">
          <label class="project-filter algorithm-filter">
            <span>算法</span>
            <select v-model="selectedAlgorithm" :disabled="loading" title="选择排程算法，提交后将调用后台对应算法">
              <option v-for="alg in algorithms" :key="alg.code" :value="alg.code" :title="alg.description">{{ alg.displayName }}</option>
            </select>
          </label>
          <button class="primary-button" type="button" @click="runSample" :disabled="loading">
            <span class="button-icon">▶</span>{{ loading ? '正在排程…' : '运行示例排程' }}
          </button>
          <button class="secondary-button" type="button" @click="fileInput?.click()" :disabled="loading">
            <span class="button-icon">＋</span>上传输入 JSON
          </button>
          <input ref="fileInput" class="visually-hidden" type="file" accept="application/json,.json" @change="handleFile" />
        </div>
      </section>

      <div v-if="error" class="message error-message">
        <span>!</span><p><strong>暂时无法完成操作</strong>{{ error }}</p><button @click="error = ''">×</button>
      </div>

      <section class="workspace">
        <aside class="recent-panel">
          <div class="panel-heading">
            <div><p class="eyebrow">RECENT RUNS</p><h2>最近任务</h2></div>
            <span class="count-badge">{{ jobs.length }}</span>
          </div>
          <div v-if="jobsLoading && !jobs.length" class="job-skeleton"><i></i><i></i><i></i></div>
          <div v-else-if="!jobs.length" class="empty-jobs">
            <span>◇</span><p>还没有排程任务</p><small>运行示例或上传输入数据开始</small>
          </div>
          <button v-for="job in jobs" :key="job.jobId" type="button" class="job-item"
            :class="{ active: currentJob?.jobId === job.jobId }" @click="openJob(job.jobId)">
            <span class="status-dot" :class="job.status.toLowerCase()"></span>
            <span class="job-copy">
              <strong>{{ shortId(job.jobId) }}</strong>
              <small>{{ formatDateTime(job.createdAt) }}</small>
            </span>
            <span class="job-status">{{ statusText(job.status) }}</span>
          </button>
          <p v-if="jobs.length" class="memory-note">任务存储于服务内存，服务重启后清空</p>
        </aside>

        <section class="dashboard-panel">
          <div v-if="loading && !result" class="loading-state">
            <div class="radar-loader"><i></i><span></span></div>
            <h2>正在生成排程方案</h2>
            <p>{{ currentJob ? `任务 ${shortId(currentJob.jobId)} · ${currentJob.progress ?? 0}%` : '正在读取数据…' }}</p>
            <div class="progress-track"><span :style="{ width: `${currentJob?.progress ?? 12}%` }"></span></div>
          </div>

          <div v-else-if="result" class="dashboard-content">
            <div class="result-heading">
              <div>
                <div class="result-title-line">
                  <h2>排程结果</h2>
                  <span class="feasibility" :class="summary.feasible ? 'success' : 'danger'">
                    <i></i>{{ summary.feasible ? '方案可行' : '存在硬约束冲突' }}
                  </span>
                </div>
                <p>任务 {{ currentJob?.jobId }} · 算法 {{ result.algorithmDisplayName || currentJob?.algorithm || '默认启发式' }} · 完成于 {{ formatDateTime(currentJob?.completedAt) }}</p>
              </div>
              <button class="download-button" type="button" @click="downloadResult">↓ 导出结果 JSON</button>
            </div>

            <div class="metric-grid">
              <article class="metric-card accent-teal">
                <div class="metric-icon">✓</div><p>方案可行性</p>
                <strong>{{ summary.feasible ? '可执行' : '需调整' }}</strong>
                <small>{{ summary.hardConstraintViolationCount ?? 0 }} 项硬约束违约</small>
              </article>
              <article class="metric-card accent-blue">
                <div class="metric-icon">▥</div><p>CWP 任务</p>
                <strong>{{ tasks.length }}</strong><small>{{ tasks.filter(item => item.status === 'scheduled').length }} 项正常排程</small>
              </article>
              <article class="metric-card accent-amber">
                <div class="metric-icon">%</div><p>平均资源利用率</p>
                <strong>{{ formatPercent(metrics.avgWorkshopCapacityUtilization) }}</strong><small>{{ utilizations.length }} 项月度资源记录</small>
              </article>
              <article class="metric-card accent-violet">
                <div class="metric-icon">¥</div><p>预计排程成本</p>
                <strong>{{ formatMoney(summary.totalCost) }}</strong><small>计算耗时 {{ summary.runtimeMillis ?? 0 }} ms</small>
              </article>
            </div>

            <nav class="tabs" aria-label="结果视图">
              <button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">
                {{ tab.label }}<span v-if="tab.id === 'conflicts' && conflicts.length">{{ conflicts.length }}</span>
              </button>
            </nav>

            <div v-if="activeTab === 'overview'" class="tab-panel overview-grid">
              <article class="content-card project-card">
                <div class="card-heading"><div><p class="eyebrow">PROJECT HEALTH</p><h3>项目交付健康度</h3></div><span>共 {{ projects.length }} 个项目</span></div>
                <div v-for="project in projects" :key="project.projectCode" class="project-row">
                  <div class="project-monogram">{{ project.projectName?.slice(0, 1) }}</div>
                  <div class="project-copy"><strong>{{ project.projectName }}</strong><small>{{ project.projectCode }}</small></div>
                  <div class="project-dates"><span>计划完工 <b>{{ formatDate(project.projectPlannedEnd) }}</b></span><span>排程完工 <b>{{ formatDate(project.projectScheduledEnd) }}</b></span></div>
                  <span class="on-time" :class="{ late: !project.isProjectFinishOnTime }">{{ project.isProjectFinishOnTime ? '按期' : '延期' }}</span>
                </div>
              </article>

              <article class="content-card utilization-preview">
                <div class="card-heading"><div><p class="eyebrow">CAPACITY PULSE</p><h3>资源负荷快照</h3></div><button @click="activeTab = 'resources'">查看详情 →</button></div>
                <div v-for="item in utilizations.slice(0, 4)" :key="`${item.month}-${item.resourceGroupId}`" class="capacity-row">
                  <div><strong>{{ item.resourceGroupName }}</strong><small>{{ item.month }} · {{ item.locationName }}</small></div>
                  <div class="capacity-meter"><span :style="{ width: `${Math.min(Number(item.utilizationRate) * 100, 100)}%` }"></span></div>
                  <b :class="{ hot: item.utilizationRate > 0.9 }">{{ formatPercent(item.utilizationRate) }}</b>
                </div>
                <div v-if="!utilizations.length" class="inline-empty">暂无产能型资源数据</div>
              </article>

              <article class="content-card overview-gantt">
                <div class="card-heading"><div><p class="eyebrow">SCHEDULE SNAPSHOT</p><h3>CWP 时间窗口</h3></div><button @click="activeTab = 'gantt'">展开甘特图 →</button></div>
                <div class="mini-task" v-for="task in tasks.slice(0, 5)" :key="task.cwpCode">
                  <span class="mini-index">{{ String(tasks.indexOf(task) + 1).padStart(2, '0') }}</span>
                  <div><strong>{{ task.cwpName }}</strong><small>{{ task.cwpCode }} · {{ task.allocatedResourceGroupId }}</small></div>
                  <span>{{ formatDate(task.scheduledStart) }}</span><i></i><span>{{ formatDate(task.scheduledEnd) }}</span>
                </div>
              </article>
            </div>

            <div v-else-if="activeTab === 'gantt'" class="tab-panel">
              <article class="content-card gantt-card">
                <div class="card-heading">
                  <div><p class="eyebrow">CWP TIMELINE</p><h3>计划与排程对比</h3></div>
                  <div class="gantt-controls">
                    <label class="project-filter">
                      <span>项目</span>
                      <select v-model="selectedProject">
                        <option value="all">全部项目</option>
                        <option v-for="project in projectOptions" :key="project.code" :value="project.code">{{ project.name }}</option>
                      </select>
                    </label>
                    <div class="gantt-legend"><span class="planned-key">计划</span><span class="scheduled-key">排程</span></div>
                  </div>
                </div>
                <div v-if="filteredTasks.length" class="gantt-scroll">
                  <div class="gantt-chart">
                    <div class="gantt-header">
                      <div class="gantt-label">CWP / 资源组</div>
                      <div class="gantt-axis">
                        <span v-for="tick in ganttRange.ticks" :key="tick.label + tick.offset" :style="{ left: `${tick.offset}%` }">{{ tick.label }}</span>
                      </div>
                    </div>
                    <div v-for="task in filteredTasks" :key="task.cwpCode" class="gantt-row">
                      <div class="gantt-label"><strong>{{ task.cwpName }}</strong><small>{{ task.cwpCode }} · {{ task.allocatedResourceGroupId }}</small></div>
                      <div class="gantt-track">
                        <i v-for="tick in ganttRange.ticks" :key="tick.label + tick.offset" :style="{ left: `${tick.offset}%` }"></i>
                        <span class="planned-bar" :style="ganttStyle(task, 'planned')"></span>
                        <span class="scheduled-bar" :class="{ conflicted: task.status !== 'scheduled' }" :style="ganttStyle(task, 'scheduled')">
                          <b>{{ task.cwpCode }}</b>
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-else class="inline-empty">没有可展示的 CWP 任务</div>
              </article>
            </div>

            <div v-else-if="activeTab === 'resources'" class="tab-panel resource-chart-panel">
              <article class="content-card resource-chart-card">
                <div class="card-heading">
                  <div>
                    <p class="eyebrow">RESOURCE LOAD & DEMAND</p>
                    <h3>资源利用率与人员需求</h3>
                  </div>
                  <span class="resource-chart-hint">按资源类型分组 · 点击筛选</span>
                </div>

                <div class="resource-type-filter">
                  <button type="button" :class="{ active: selectedResourceType === 'all' }" @click="selectedResourceType = 'all'">全部资源</button>
                  <button v-for="type in resourceTypeOptions" :key="type" type="button" :class="{ active: selectedResourceType === type }" @click="selectedResourceType = type">{{ type }}</button>
                </div>

                <div class="resource-chart-legend">
                  <span class="legend-item util"><i></i>利用率</span>
                  <span class="legend-item labor"><i></i>人员需求（人 / 开工日）</span>
                </div>

                <div class="grouped-bar-chart">
                  <div v-if="!resourceTypeChartData.length" class="inline-empty">暂无资源与人力数据</div>
                  <template v-else>
                    <div class="chart-row chart-header">
                      <div class="chart-label">{{ selectedResourceType === 'all' ? '资源类型' : '月份' }}</div>
                      <div class="chart-metrics">
                        <span>利用率</span>
                        <span>人员需求</span>
                      </div>
                    </div>
                    <div v-for="item in resourceTypeChartData" :key="item.label" class="chart-row">
                      <div class="chart-label" :title="item.label">{{ item.label }}</div>
                      <div class="chart-metrics">
                        <div class="metric-bar">
                          <div class="bar-track">
                            <div class="bar util-bar" :style="{ width: `${Math.min(item.utilization * 100, 100)}%` }"></div>
                          </div>
                          <span class="metric-value">{{ formatPercent(item.utilization) }}</span>
                        </div>
                        <div class="metric-bar">
                          <div class="bar-track">
                            <div class="bar labor-bar" :style="{ width: `${Math.min((item.laborDemand / resourceChartMax.labor) * 100, 100)}%` }"></div>
                          </div>
                          <span class="metric-value">{{ item.laborDemand.toFixed(2) }}</span>
                        </div>
                      </div>
                    </div>
                  </template>
                </div>
              </article>
            </div>

            <div v-else class="tab-panel">
              <article class="content-card conflict-card">
                <div class="card-heading"><div><p class="eyebrow">CONSTRAINT DIAGNOSTICS</p><h3>资源冲突清单</h3></div><span :class="conflicts.length ? 'alert-count' : 'safe-count'">{{ conflicts.length ? `${conflicts.length} 项待处理` : '未发现冲突' }}</span></div>
                <div v-if="!conflicts.length" class="no-conflicts"><span>✓</span><h4>所有硬约束均已满足</h4><p>当前方案没有检测到产能、人力、工位或占用率冲突。</p></div>
                <div v-else class="table-scroll">
                  <table>
                    <thead><tr><th>类型</th><th>日期</th><th>资源</th><th>可用量</th><th>需求量</th><th>缺口</th><th>关联 CWP</th></tr></thead>
                    <tbody><tr v-for="item in conflicts" :key="item.conflictId">
                      <td><span class="type-badge">{{ conflictType(item.conflictType) }}</span></td><td>{{ item.date }}</td><td><strong>{{ item.resourceGroupName }}</strong><small>{{ item.resourceGroupId }}</small></td>
                      <td>{{ item.availableAmount }} {{ item.unit }}</td><td>{{ item.requiredAmount }} {{ item.unit }}</td><td class="shortage">{{ item.shortageAmount }} {{ item.unit }}</td>
                      <td>{{ (item.conflictedCwps ?? []).map(cwp => cwp.cwpCode ?? cwp).join('、') || '—' }}</td>
                    </tr></tbody>
                  </table>
                </div>
              </article>
            </div>
          </div>

          <div v-else class="welcome-state">
            <div class="blueprint-mark"><span></span><i></i><b></b></div>
            <p class="eyebrow">READY TO SCHEDULE</p>
            <h2>从一份排程数据开始</h2>
            <p>运行内置示例，或上传符合接口规范的 JSON 文件。计算完成后，这里会呈现完整的可视化排程结果。</p>
            <div><button class="primary-button" @click="runSample">运行示例排程</button><button class="text-button" @click="fileInput?.click()">选择 JSON 文件 →</button></div>
          </div>
        </section>
      </section>
    </main>

    <main v-else class="rules-main">
      <section class="rules-hero">
        <div>
          <p class="eyebrow"><span></span> SOLVER POLICY STUDIO</p>
          <h1>用业务语言，调整排程规则</h1>
          <p>指令会先转换为受控的结构化规则。确认应用后，仅影响之后新建的排程任务。</p>
        </div>
        <span class="rule-version">ACTIVE RULESET · v{{ rulesState.rules?.version ?? 1 }}</span>
      </section>

      <div v-if="error" class="message error-message">
        <span>!</span><p><strong>规则操作未完成</strong>{{ error }}</p><button @click="error = ''">×</button>
      </div>

      <section class="rules-workspace">
        <aside class="rule-summary-panel">
          <div class="rule-panel-heading">
            <div><p class="eyebrow">ACTIVE POLICY</p><h2>当前生效规则</h2></div>
            <i></i>
          </div>
          <div class="rule-card" v-for="card in ruleCards" :key="card.label">
            <span>{{ card.label }}</span><strong>{{ card.value }}</strong>
          </div>
          <p class="rule-updated">更新于 {{ formatDateTime(rulesState.rules?.updatedAt) }}</p>
        </aside>

        <section class="rule-chat-panel">
          <div class="chat-heading">
            <div><p class="eyebrow">NATURAL LANGUAGE CONTROL</p><h2>规则对话</h2></div>
            <span><i></i> 本地受控解析器</span>
          </div>
          <div class="chat-stream">
            <div v-for="(item, index) in ruleConversation" :key="index" class="chat-message" :class="[item.role, { failed: item.error }]">
              <span>{{ item.role === 'assistant' ? 'APS' : 'YOU' }}</span><p>{{ item.text }}</p>
            </div>

            <div v-if="ruleProposal" class="proposal-card">
              <div class="proposal-title"><span>变更预览</span><small>尚未生效</small></div>
              <div v-for="change in ruleProposal.changes" :key="change.field" class="proposal-row">
                <strong>{{ change.label }}</strong>
                <span>{{ ruleValue(change.from) }}</span><i>→</i><b>{{ ruleValue(change.to) }}</b>
              </div>
              <div class="proposal-actions">
                <button class="text-button" @click="ruleProposal = null" :disabled="ruleBusy">取消</button>
                <button class="primary-button" @click="applyRuleProposal" :disabled="ruleBusy">{{ ruleBusy ? '正在应用…' : '确认并应用规则' }}</button>
              </div>
            </div>
          </div>

          <div class="rule-composer">
            <textarea v-model="ruleMessage" rows="3" placeholder="例如：高优先级项目先排，产能利用率上限设为 90%，禁止使用替代资源"
              @keydown.ctrl.enter.prevent="interpretRule()"></textarea>
            <div><span>Ctrl + Enter 发送</span><button class="primary-button" @click="interpretRule()" :disabled="!ruleMessage.trim() || ruleBusy">解析规则</button></div>
          </div>
        </section>

        <aside class="rule-examples-panel">
          <div class="rule-panel-heading"><div><p class="eyebrow">COMMAND GUIDE</p><h2>可用指令示例</h2></div></div>
          <button v-for="command in rulesState.supportedCommands" :key="command" @click="ruleMessage = command">
            <span>＋</span>{{ command }}
          </button>
          <div class="rule-safety-note"><strong>安全机制</strong><p>自然语言不能执行代码，也不会直接改写 Java 文件。每次变更都需要人工确认，并记录规则版本。</p></div>
        </aside>
      </section>
    </main>

    <div v-if="toast" class="toast"><span>✓</span>{{ toast }}</div>
    <footer><span>CWP Scheduler · Deterministic Heuristic Engine</span><span>Asia/Shanghai · API v1</span></footer>
  </div>
</template>
