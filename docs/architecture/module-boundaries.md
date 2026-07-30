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

当前脚手架没有业务用例，因此各业务模块仅包含模块声明；公开接口在首个真实用例出现时创建。
