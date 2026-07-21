# CWP 海工四级计划排程系统

面向**海洋工程（海工）建造**的多约束高级计划排程（APS）系统，聚焦于 **CWP（Construction Work Package，建造工作包）四级计划**的自动排程与资源指标计算。

本服务读取 CWP、项目、工艺路线、人员上限与资源组等输入，使用确定性的纯 Java 启发式算法，生成排程方案、资源利用率、冲突清单、关键路径与甘特图数据，并通过 Vue 驾驶舱提供交互式查看与规则调优。

> 详细设计、API、计算口径与算法说明见 [`cwp-scheduler/README.md`](cwp-scheduler/README.md)。

## 技术栈

- **后端**：Spring Boot 2.7.18（Java 8），Maven 构建，纯 Java 启发式排程引擎
- **前端**：Vue 3 + Vite 排程驾驶舱（甘特图、资源利用率、冲突诊断、中文规则助手）
- **测试**：JUnit 5 + Spring Boot Test

## 目录结构

```
CWP/
├── cwp-scheduler/        # 排程服务（Spring Boot + Vue 前端）
│   ├── src/              # Java 后端源码（排程引擎、API、规则、校验）
│   ├── frontend/         # Vue 3 驾驶舱前端源码
│   ├── examples/         # 内置船厂示例输入
│   ├── scripts/          # 测试数据生成脚本
│   └── README.md         # 服务详细文档
├── sources/              # 只读参考资料（外部同步，勿修改）
└── AGENTS.md             # 协作 / 代理上下文说明
```

## 快速开始

```bash
cd cwp-scheduler
mvn clean package
java -jar target/cwp-scheduler-1.0.0.jar
```

启动后访问 `http://localhost:8080/` 打开排程驾驶舱。完整启动、API 与构建说明见 [`cwp-scheduler/README.md`](cwp-scheduler/README.md)。

## 说明

- 本仓库仅包含 CWP 排程服务的源代码；投标文档、技术规格书、业务示例数据等资料单独管理，不纳入此代码仓库。
- 排程结果与规则配置保存在服务内存中，重启后不保留。
- 算法保证计算口径可复现、冲突可追溯，但不承诺数学意义上的全局最优。
