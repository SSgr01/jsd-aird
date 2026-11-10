# 企业业务读取、数据呈现与计算接入

本轮在 `main` 完成业务适配，使用隔离仓储样本、独立临时 PostgreSQL 账本及现有计算服务验证。新增业务域默认关闭；按用户要求，当前本地环境可显式启用管理员联通开关，先使用读取及现有计算服务。生产权限仍由 `BusinessDataAccess` 实现接入，现有项目、实验及已发布结构化检索继续走原有授权服务。

## 执行与模块边界

```mermaid
flowchart LR
  D[原生执行循环与按需工具发现] --> P[企业 business-data 能力包]
  P --> C[Python 操作账本与完整来源]
  C --> J[Java 读取与计算适配]
  J --> A[逐次记录授权与固定版本]
  A --> R[各业务域自己的读取适配器]
  A --> M[现有预测、配方与实验优化服务]
  R --> F[可读正文、表格、对象引用]
  F --> N[原生文件系统与脚本分析]
```

- 原生执行循环、文件、脚本、附件、计划、Inbox、预览与交付不增加平行实现。
- 各域实现共享的 `BusinessReadPort`，只有域自身访问其仓储；AI 模块通过注入的契约调用，不直接依赖其他域的 application/infrastructure。
- `BusinessExistingData` 复用已有项目、实验和结构化检索服务；对外统一读取契约，对内保留真实权限与数据语义。
- 能力包起始仅激活 `business_search`，其他读取和计算工具沿原有 `tool_search` 按需启用。统一包启用时隐藏重复的旧读取工具名，不删除内部服务。

## 工具契约

| 工具 | 行为 |
| --- | --- |
| `business_search` | 不传 type 返回可用类型、筛选和必要筛选字段；传 type 查找已授权记录，返回 ref 与分页 |
| `business_read` | 读取返回的 ref；保留 revision、版本及父对象定位，实验继续支持分区、样品和字段分页 |
| `business_links` | 不传 relation 先列出已确认关系；选择后分别授权、读取目标，顶层 candidates 统一登记来源 |
| `business_query` | 字段白名单上的有类型表格及 count/sum/avg/min/max；支持 output=file，禁止任意 SQL |
| `get_compute_context` | 返回实际输入、单位、模型版本或历史数据集，并附现有服务的 request_contract |
| `run_prediction` | 调用现有性能预测，不训练或发布模型 |
| `run_formula_design` | 调用现有配方搜索，保留目标、材料、条件、模型绑定和候选限制 |
| `run_experiment_optimization` | 调用现有 HISTORICAL_GP_BO，必须明确数据集、样品行及 X/Y |
| `get_compute_result` | 按真实运行 id 查询状态和结果；终态结果才登记稳定来源 |

通用读取分页最多 40 条；已有项目/实验固定页宽由适配器转换，不重复分页。新域的内部扫描上限 5000 条，超过上限要求缩小范围，不输出残缺统计。未知或无法执行的筛选显式拒绝，不悄悄扩大查询。

## 数据覆盖与真实关联

| 类型 | 内容与关联 |
| --- | --- |
| PROJECT / STAGE / TASK | 原有授权项目、阶段、任务，保留业务计数与实际状态；项目继续只读 |
| REQUIREMENT / MATERIAL | 原有项目明确关联的需求、材料记录 |
| EXPERIMENT | 现有固定版本及字段契约；不改模板框架、审核或发布流程 |
| STRUCTURED_DATA | 复用已发布结构化检索的文件、记录、字段读取；query 映射实际记录检索词，精读校验具体来源与正文哈希 |
| DATA_IMPORT / STRUCTURED_RECORD | 导入文件及原始行；分别保留原值、规范值、人工修正、排除及来源信息 |
| CUSTOMER / CUSTOMER_CONTACT / CUSTOMER_COMMUNICATION / CUSTOMER_REQUIREMENT | 客户、联系人、跟进、需求；沿客户 ID 与已有项目 ID 展开，不以同名推断关联 |
| INVENTORY_BALANCE / INVENTORY_TRANSACTION | 库存域余额、出入库、冲销与对应业务；余额不冒充批次可用量 |
| SAMPLE_DISPATCH / SHIPMENT | 发样、反馈、出货、客户和库存流水；出货 COA 沿确切业务编号解析 |
| PRODUCTION_ORDER / PRODUCTION_UPLOAD | 正式生产单及其修订、上传生产单及保存版本；历史上传内容读取当时的工作表数值与坐标 |
| QUALITY_STANDARD / RECORD / COA / DEFECT / MANUAL / MSDS / LABEL | 品管记录、固定版本及已存业务编号关系，例如 COA→测试记录→采用标准 |
| RESEARCH_TEST | 研发测试报告/标准，读取保存正文或表格；保留未知业务字段，剔除编辑器样式与协议 |
| SPECTRUM | 样品、批号、条件、原文件哈希和完整原文件；未有原始数值曲线时不声称可精确计算峰面积 |
| SPECTRUM_ANALYSIS | 按已有 id 读取本用户的历史谱图分析，标记为二次 AI 分析；不提供全局历史分析搜索 |

这些是实际存在的关联。没有已存 ID 或确切业务编号时，工具只能返回搜索线索。历史对象若只保存正文而未保存当时的索引元数据，会明确区分固定版本正文和当前索引信息。

## 呈现、原文件和来源

- 模型读取 Markdown 正文、表格、简短 ref 与分页；权限、版本、哈希及完整来源保留在服务端账本。
- 原生工具展示扩展复用来源卡片和侧栏；不增加资料范围、成果或待发送面板。
- `business_query` 保留数字、零、缺失和文本状态；不同单位、库存域、方法或条件混合统计时要求分组，包括嵌套字段。
- `output=file` 生成有 schema、typed rows 和逐行 ref 的 JSON。通过原生 `ctx.fs` 发布到会话目录的 `.business-data/`，继续用原生文件与脚本工具分析。
- `business_read` 对谱图可设置 `include_file=true`：逐次 READ、EXPORT 校验后读取原文件，检查登记 SHA-256，并通过同一原生文件桥接交付。当前单文件上限 8 MiB；超过上限明确要求从原业务入口下载并使用原生附件。
- 生产上传的待确认识别值与已发布版本分开，不把当前识别结果补进历史版本。公式保留表达式及已有缓存值，未有缓存值时不当作零。
- 新原文地址为 `/api/v1/agents/business/records/...`、`/query` 和 `/compute/...`；重新校验身份、权限、对象、版本及查询快照，使用 no-store。返回业务页面的真实入口。
- 普通文献原文、原生 PDF 组件及现有附件路径保持现有实现。

## 计算一致性与未知结果

计算操作身份由 Python 根据业务 Run 与原生 call_id 生成，模型不能指定组织、用户或幂等键。提交返回权威运行 id、状态及查询工具；提交成功不代表后台计算成功，预测和候选不作为实测。

响应丢失后先查询已有业务回执。未找到回执只能记为 UNKNOWN，不能自动重试；找到记录只确认提交已接受，再查询真实计算状态。沿用原有操作账本，不增加另一套任务循环。

历史优化数据按实验和版本重新授权，模型可见行、列、计数及异常均过滤。当前底层服务会冻结整张历史数据集，因此包含未授权样品时拒绝提交，即使本次只选择其中一部分。提交时由服务端固定已授权数据的哈希，底层冻结前重新核对；发生变化返回版本冲突。不会向模型开放训练、模型发布或优化结果回写。

## 与权限工作的对接

`shared.business.BusinessDataAccess` 是唯一新增授权接点：

```java
Set<String> resourceTypes();
boolean allowed(Actor actor, String type, UUID id, String operation, JsonNode context);
```

- 实现由权限侧按业务域提供 Spring bean；同一类型只能有一个授权所有者，重复配置启动失败。
- 当前使用 SEARCH、READ、EXPORT、EXECUTE。集合查询的 id 为 null，单记录操作带真实 id；context 保留必要筛选或完整 ref，包括库存域、父对象与版本。
- 搜索先检查集合授权，再逐记录授权，之后才能向模型分页、统计或输出文件；关联目标独立校验。
- 没有实现或授权失败默认拒绝。本地管理员联通是下面明确限定的例外；已有权限实现的拒绝不会被该开关覆盖。不能根据模型身份字段授权。
- 不改 IAM 规则、客户归属或数据库迁移，避免与另一条权限工作冲突。

计算权限类型为 `AI_PREDICTION`、`AI_FORMULA_DESIGN`、`AI_EXPERIMENT_OPTIMIZATION`。读取上下文按集合 READ 校验；提交按集合 EXECUTE 校验；结果、回执及交付按实际运行 id 再次校验。它们与同名业务页面的可访问性分别处理，页面可用不代表自动授予助手执行权限。

### 本地管理员联通

当前阶段用户要求先接通业务、不等待逐记录权限开发。使用现有启动脚本，在本地 `.env` 设置 `JSD_AIRD_BUSINESS_LOCAL_ACCESS=true` 后重启 API 即可；关闭时设为 `false` 并重启。代码默认关闭，只有 `local` 配置且未启用 `prod` 时才安装 `LocalBusinessAccess`。

- 仅当前已登录、身份委托角色为 SYSTEM_ADMIN 的系统管理员可用，不接受模型传入身份。
- 注册的业务读取适配器允许 SEARCH、READ、EXPORT；三类已有研发计算允许 READ、EXECUTE，不新增客户、出货、生产或项目写入。
- 各业务服务已有的组织、用户隔离继续执行，版本、文件哈希、预算、回执和幂等不受此开关影响。旧客户仓储没有完善的组织归属，本地管理员读取沿用现有客户页面的全局数据范围，不据此宣称跨组织生产权限已完成。
- 已有 `BusinessDataAccess` 策略始终优先，包括明确拒绝；后续权限工作接入相同类型无需改读取适配器，也不会与本地开关产生重复授权所有者。
- 普通账号、未登录请求、未注册业务类型、业务写入及生产配置不会从这个开关获得授权。

## 启动与验证

沿用 [README](../README.md) 的 API、Control、Gateway 和现有计算服务启动命令；原生文件分析继续使用原沙箱。新工具改变能力指纹，验收使用新会话。缺少权限实现时目录中会显示未开放类型，已接入的原有项目/实验路径继续可用。

Java 隔离样本：`BusinessDataServiceTest`、`BusinessDomainAdaptersTest`、`BusinessExistingDataTest`、`BusinessComputeServiceTest`、`BusinessSourceControllerTest`。覆盖真实 ID 关系、两类生产记录、固定版本、结构化原值与修正、谱图原文件、条件统计、撤权、计算快照与回执。

Python 的 `test_business_data.py` 与 `test_business_dispatch.py` 覆盖原生调用身份、完整证据与模型投影、按需发现、隔离账本及丢失响应对账。独立 PostgreSQL 只保存合成的测试用户、会话和回执，不使用业务库、不运行 Java Flyway。

Node 的 `business-dataset.test.mjs` 验证原生文件发布、字节与路径校验及停止；网关回归使用项目已有独立 Node 24。计算服务的 `test_historical_optimization.py` 和 `test_workbench_formula_prediction.py` 使用合成数据验证现有真实算法及模型文件。

测试输出进入忽略的 `.runtime/business-data-acceptance/` 与 Maven `target/`。生产跨组织权限联调、真实业务数据覆盖及完整客户浏览器任务需在权限实现启用后另行验收，不能用本轮隔离结果替代。
