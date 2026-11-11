# 模块边界

## 检测策略

项目使用：

```yaml
spring:
  modulith:
    detection-strategy: explicitly-annotated
```

只有明确标注 `@ApplicationModule` 的包才是 Spring Modulith 应用模块。`bootstrap` 和 `platform` 属于装配与技术基础设施，不声明为业务模块。

## Java 模块

| 模块      | 当前职责边界                           | 数据库 Schema |
| --------- | -------------------------------------- | ------------- |
| `core`    | 统一业务对象和跨域引用                 | `core`        |
| `iam`     | 组织、用户、角色和数据范围             | `iam`         |
| `mdm`     | 客户、供应商、物料、产品、单位等主数据 | `mdm`         |
| `tpl`     | 模板、字段定义和运行时实例             | `tpl`         |
| `rnd`     | 项目、实验、配方和样品                 | `rnd`         |
| `quality` | 标准、检验、判定和报告                 | `quality`     |
| `spc`     | 图谱、曲线、处理和特征                 | `spc`         |
| `mfg`     | 生产、批次、库存和追溯                 | `mfg`         |
| `kb`      | 知识版本、ACL、全文和向量              | `kb`          |
| `ai`      | 数据集、分析、模型和预测               | `ai`          |
| `ops`     | 文件、导入、任务、审计和 Outbox        | `ops`         |
| `export`  | 通用导出编排                           | 无独立 Schema |

## 公开接口

模块根包以外的类型默认是内部实现。跨模块 Java 契约放在模块的 `api` 子包，并通过 `@NamedInterface("api")` 明确公开。

其他模块只能声明对 `module::api` 的依赖，不允许直接访问目标模块的 `adapter`、`application`、`domain` 或 `infrastructure`。

当前已有真实业务用例。`ops.application.port` 是既有的 `ops::api` 命名接口；其他模块的可复用解析协议、业务命令和只读查询协议放在 `api`，实现类与写入仓储仍留在模块内部。

主要跨模块契约：

- `iam.api.DelegationIdentity` 仅提供委托校验必需的用户状态、组织和授权版本，不暴露账号存储记录。
- `mdm.api.BusinessPartners`、`PartnerContacts` 和 `AgentProjectFacade` 提供明确业务用例；项目实验 HTTP 控制器归 `rnd` 所有，URL 不变。
- `kb.api` 提供文档解析、OCR、文件扫描和有序文档协议；调用方不依赖 MinerU 实现。
- `tpl.api.TemplateWordSource` 只提供导出所需的原文及快照引用，调用方不能访问模板写入仓储。
- `tpl.api.ExperimentTemplateSource` 由实验仓储实现，模板用例仅消费所需快照，避免 `tpl` 反向依赖 `rnd`。
- `quality.api.QualityRecordLookup` 提供现有按组织和角色过滤的业务编号查询，保留 COA 放行校验。
- 共用 JSON 解析、图片转 PDF 和 LaTeX 文本处理位于 `shared`，不通过 `ai` 或 `rnd` 模块复用技术实现。
- `shared.business.BusinessReadPort` 由各域提供自己的读取适配，AI 仅消费共享契约，不跨域访问仓储；`BusinessDataAccess` 为权限侧提供记录授权接点，缺失时默认拒绝。模型操作身份、来源和计算接入见 `jsd-aird-agent/docs/enterprise-business-data.md`。

新增跨模块调用时必须显式声明实际使用的 `module::api`，不能通过开放整个业务模块来绕过边界。`ModuleBoundaryTest` 检查真实生产模块图，`ArchitectureRulesTest` 只导入生产类并保持全部分层规则。
