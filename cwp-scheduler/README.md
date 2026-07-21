# CWP 四级计划排程服务

这是一个面向 JDK 8 的 Spring Boot 2.7.18 无状态排程服务。它读取 CWP、项目、工艺路线、人员上限和资源组，使用确定性的纯 Java 启发式算法生成排程、资源指标、冲突清单、关键路径与甘特图数据。

现有示例输出 JSON 只用于说明字段结构。服务不会生成输入中不存在的 CWP，也不会复现无法从输入推导的冲突数字。

## 启动

```bash
mvn clean package
java -jar target/cwp-scheduler-1.0.0.jar
```

默认监听 `8080`。线程数、队列长度、求解时限和时区在 `src/main/resources/application.yml` 中配置。工程以 Java 8 字节码编译；更高版本 JDK 也可用于构建。

启动后可在浏览器打开 `http://localhost:8080/` 使用 Vue 3 排程驾驶舱。界面支持：

- 运行内置示例或上传排程输入 JSON；
- 从最近任务列表直接打开内存中的排程结果；
- 查看方案指标、CWP 甘特图、资源利用率、人员需求和冲突诊断；
- 在“规则助手”中用中文指令预览、确认并应用求解策略；
- 将排程结果导出为 JSON 文件。

前端源码位于 `frontend/`。修改界面后重新构建静态资源：

```bash
cd frontend
npm install
npm run build
cd ..
mvn package
```

开发模式可运行 `npm run dev`，Vite 会将 `/api` 请求代理到 `http://localhost:8080`。

内置示例 `examples/cwp-schedule-test.json` 按船厂布局构造，包含 5 个项目、每项目 3 个单体，以及下料、结构预制、工艺管线、制管、喷砂、喷漆、预舾装、预留场地和两期总装工位等资源。数据生成逻辑位于 `scripts/generate-yard-test-data.mjs`；界面中的“运行示例排程”会直接提交该数据。

## API

创建排程任务：

```bash
curl -i -X POST http://localhost:8080/api/v1/cwp-schedule-jobs \
  -H 'Content-Type: application/json' \
  --data-binary @input.json
```

响应为 HTTP 202：

```json
{"jobId":"...","status":"QUEUED","progress":0,"warnings":[]}
```

查询状态和结果：

```bash
curl http://localhost:8080/api/v1/cwp-schedule-jobs/{jobId}
curl http://localhost:8080/api/v1/cwp-schedule-jobs/{jobId}/result
```

下载带有标准附件文件名的排程结果 JSON：

```bash
curl -OJ http://localhost:8080/api/v1/cwp-schedule-jobs/{jobId}/result/download
```

查询当前服务实例中的最近任务：

```bash
curl http://localhost:8080/api/v1/cwp-schedule-jobs
```

查询当前求解规则、将中文指令解析为变更预览、确认应用：

```bash
curl http://localhost:8080/api/v1/solver-rules
curl -X POST http://localhost:8080/api/v1/solver-rules/interpret \
  -H 'Content-Type: application/json' \
  -d '{"message":"高优先级项目先排，产能利用率上限设为90%"}'
curl -X POST http://localhost:8080/api/v1/solver-rules/proposals/{proposalId}/apply
```

规则采用“解析预览 → 人工确认 → 版本化生效”流程，不会根据语言指令执行任意代码或改写 Java 文件。当前支持调整同层任务排序、替代资源、关键任务前置链锁定、同成本日期偏好、产能/人力安全上限和无解兜底策略。每个新排程任务在启动时保存规则快照，结果中的 `solverRuleSnapshot` 可用于追溯。规则和变更历史目前保存在服务内存中，服务重启后恢复默认规则。

任务尚未完成时读取结果返回 409；输入校验失败返回 400；任务不存在返回 404。任务保存在内存中，服务重启后不会保留。

## 输入扩展

原始输入需要补充成本主数据。每个资源位置必须有人工单价，每个资源组必须有资源单价：

```json
{
  "costModel": {
    "enabled": true,
    "scheduleDeviationCostPerDay": 100,
    "lockViolationCostPerDay": 10000,
    "laborUnitCosts": [
      {"locationCode": "LOC_PREFAB", "amountPerPersonDay": 500}
    ],
    "resourceCostRates": [
      {
        "resourceGroupId": "RG_PREFAB_AREA_01",
        "baselineUnitCost": 0,
        "overtimeUnitCost": 0,
        "occupancyUnitCostPerDay": 1000,
        "blockUnitCostPerDay": 0
      }
    ]
  }
}
```

单工序 CWP 的 `workloadRatio` 缺省为 1。多工序 CWP 必须逐工序提供该字段，且总和严格等于 1：

```json
{"opCode":"OP-10","sequence":10,"workloadRatio":0.6}
```

校验器还会拒绝：重复编码、非法日期、依赖环、进度或占比越界、工作量单位不一致、未知资源、网格容量与布局不一致，以及缺失成本单价。批次外前置任务视为已完成并写入 `scheduleSummary.warnings`。占用模式或单位不兼容的替代关系也会被忽略并告警。

## 计算口径

- 剩余工作量：`totalAmount × (1-progress)`。
- 起止日均计入工期；未锁定任务只平移、不改变计划工期。
- 工作量按排程自然日均摊，跨月时按各月占用天数分摊。
- 产能利用率：月剩余工作量除以 `baselineAmount`；`maxAmount` 用于硬约束。
- 工序日人数：`ceil(日工作量 / workloadPerPersonDay)`。
- 月均人数：月内日需求总和除以该位置当月实际开工日数。
- 关键任务必须满足 CPM 总时差为 0。
- 资源和人员无法全部满足时仍返回诊断性排程，相关任务状态为 `scheduledWithConflict`，且 `scheduleSummary.feasible=false`。

支持 `CAPACITY`、`OCCUPANCY_RATIO` 和 `GRID_BLOCK`。网格被建模为每期两行、每个工位两列的连续二维空间，可表达 `2x2`、`1x2`、`1x4` 以及同一期相邻工位跨位占用。同一 `assemblyUnit` 的 CWP 共用最早开始至最晚结束的单体窗口。

## 算法说明

算法先做 CPM 和锁定集合扩展，然后按依赖深度、锁定状态、项目优先级、计划开始时间和编码稳定排序。未锁定任务枚举项目截止日期前的候选日期，依次检查主资源、兼容替代资源、月产能、日人力和工位占比；候选按硬违约、日期偏移和成本选择。总装单体在任务时间确定后使用二维 first-fit decreasing 放置。

核心算法位于 `src/main/java/com/example/aps/cwp/engine/ScheduleEngine.java`，动态规则模型位于 `src/main/java/com/example/aps/cwp/rules/SolverRules.java`，自然语言受控解析器位于 `src/main/java/com/example/aps/cwp/service/SolverRuleService.java`。

该实现保证计算口径可复现、冲突可追溯，但不承诺数学意义上的全局最优。

## 输出

输出包含原定义的七个业务对象，并增加：

- `scheduleSummary`：可行性、算法状态、目标指标、总成本、硬约束违约数、耗时和告警。
- `monthlyWorkshopUtilization[].resourceGroupId/resourceGroupName`。
- `cwpGanttOutput.tasks[].allocatedResourceGroupId/violations`。
- `resourceConflictList[].conflictType=LABOR` 和正确的 `GRID` 冲突类型。
- `solverRuleSnapshot`：本次排程使用的规则版本和完整配置。

利用率保留四位小数，月均人数和总成本保留两位小数；内部计算使用 `BigDecimal`。

## 测试

```bash
mvn test
```

测试覆盖核心剩余工作量和利用率口径、多工序校验、异步创建/结果读取，以及 400/404 API 行为。
