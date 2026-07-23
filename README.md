# CWP 海工四级计划排程系统

面向**海洋工程（海工）建造**的多约束高级计划排程（APS）系统，聚焦于 **CWP（Construction Work Package，建造工作包）四级计划**的自动排程与资源指标计算。

本服务读取 CWP、项目、工艺路线、人员上限与资源组等输入，使用确定性的纯 Java 启发式算法，生成排程方案、资源利用率、冲突清单、关键路径与甘特图数据，并通过 Vue 驾驶舱提供交互式查看与规则调优。

> 详细设计、API、计算口径与算法说明见源码 `cwp-scheduler/src/main/java/com/example/aps/cwp/`（排程引擎 `engine/`、接口 `api/`、输入校验 `validation/`）。

## 技术栈

- **后端**：Spring Boot 2.7.18（Java 8），Maven 构建，纯 Java 启发式排程引擎
- **前端**：Vue 3 + Vite 排程驾驶舱（甘特图、资源利用率、冲突诊断、中文规则助手）；需 Node.js 20+（Vite 7 要求）
- **测试**：JUnit 5 + Spring Boot Test

## 目录结构

```
CWP/
├── cwp-scheduler/        # 排程服务（Spring Boot + Vue 前端）
│   ├── src/              # Java 后端源码（排程引擎、API、规则、校验）
│   ├── frontend/         # Vue 3 驾驶舱前端源码
│   ├── examples/         # 内置船厂示例输入
│   ├── scripts/          # 测试数据生成脚本
│   ├── .mvn/ + mvnw      # Maven Wrapper（免装 Maven，见「环境要求」）
├── sources/              # 只读参考资料（外部同步，勿修改）
└── AGENTS.md             # 协作 / 代理上下文说明
```

## 环境要求

- **JDK 8**（即 Java 1.8）：后端运行与编译所需
- **Maven**：无需预装，仓库已内置 Maven Wrapper，使用 `./mvnw`（首次运行自动下载 Maven 3.9.16）
- **Node.js 20+ 与 npm**：仅前端开发模式（`npm run dev`）需要；Vite 7 要求 Node 20.19+ 或 22.12+

> 仅运行打包后的后端静态页面（`java -jar ...jar`）不需要 Node.js。

## 快速开始（源码开发模式，推荐）

> 以下命令在**仓库根目录 `CWP/`**（即本文件所在目录）下执行。

无需打包，直接运行源码；修改代码后自动热更新。

```bash
# 终端 1：后端源码运行（改 Java 保存后 devtools 自动重启，无需打包）
cd cwp-scheduler
./mvnw spring-boot:run        # 也可用系统 mvn；仓库已内置 Maven Wrapper

# 终端 2：前端热更新（改代码即时生效）
cd cwp-scheduler/frontend
npm run dev
```

启动后访问 **http://localhost:5173/** 打开排程驾驶舱（前端通过代理调用 8080 后端）。

> 说明：8080 提供的是打包后的静态页面，开发调试请使用 5173。打包部署：`cd cwp-scheduler && ./mvnw package && java -jar target/cwp-scheduler-1.0.0.jar`。

## 停止服务

```bash
pkill -f "spring-boot:run"        # 停止后端
pkill -f "CwpSchedulerApplication"
pkill -f "vite"                   # 停止前端
```

## 说明

- 本仓库仅包含 CWP 排程服务的源代码；投标文档、技术规格书、业务示例数据等资料单独管理，不纳入此代码仓库。
- 排程结果与规则配置保存在服务内存中，重启后不保留。
- 算法保证计算口径可复现、冲突可追溯，但不承诺数学意义上的全局最优。

## 资源组可用量与示例数据

### 资源组可用资源量约束

排程输入 JSON 中，资源组的「可用资源量」由 `resourceBindingPolicy.resourceGroups[]` 按 `consumptionMode` 决定，外加 `locationLaborConstraints.locations[]` 的人力每日上限：

| consumptionMode | 可用量字段 | 说明 |
| --- | --- | --- |
| `CAPACITY` | `capacity.baselineAmount`（基线）+ `capacity.maxAmount`（硬上限） | 超 `baselineAmount` 计加班成本；`maxAmount` 为不可突破的硬约束 |
| `GRID_BLOCK` | `capacity.amount` | 总装网格月度小块可用总量 |
| `OCCUPANCY_RATIO` | `regions[]` 元素个数 | 每个区域占用上限硬编码 1.0，**不使用 `capacity` 字段** |
| 人力（并行） | `locationLaborConstraints.locations[].maxLaborPerDay` | 按 location 限制每日投入人数 |

> `OCCUPANCY_RATIO` 模式只看 `regions[]` 的个数（每区上限 1.0），代码（`Domain.java` / `Ledger.java`）从不读取 `capacity`，因此该模式下出现 `capacity` 字段属于冗余且易误导。

### 示例数据生成与版本控制约定

- `cwp-scheduler/examples/cwp-schedule-base.json`：**受控样本**（64 CWP 基准模板），已纳入版本控制，是 `scale-test-data.mjs` 的输入来源。
- `cwp-scheduler/examples/cwp-schedule-test.json`：由脚本从基准模板生成的 1000 CWP 大规模数据，**不纳入版本控制**（已在 `.gitignore` 中忽略）。本地运行 `node cwp-scheduler/scripts/scale-test-data.mjs` 即可重新生成。
- 生成脚本 `generate-yard-test-data.mjs` 的 `occupancyGroup` 不再为 `OCCUPANCY_RATIO` 组输出 `capacity` 字段，符合文档口径。

