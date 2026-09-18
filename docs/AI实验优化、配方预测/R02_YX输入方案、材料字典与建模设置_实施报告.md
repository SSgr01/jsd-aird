# R02 Y/X、输入方案、材料字典与建模设置实施报告

> 状态：COMPLETED  
> 完成日期：2026-09-14  
> 适用设计：[AI研发模块_页面与后端重构详细设计_V4.1.md](./AI研发模块_页面与后端重构详细设计_V4.1.md)  
> 任务入口：[AI配方预测与实验优化_开发任务总表.md](./AI配方预测与实验优化_开发任务总表.md)

## 1. 实施结论

R02已经完成建模设置的前后端闭环。新页面路径为`/assistant/modeling-settings`，页面顶层只包含“预测目标 Y”和“输入字段库 X”两个页签；来源映射、输入方案和训练策略收在Y详情中，材料别名和材料字典收在配方组成字段所在的X区域中。

后端新增独立的`ai.rnd.modeling`领域服务、Repository和Controller，支持Y、X、来源映射、输入方案、训练策略、材料别名和材料字典的创建、版本、发布、冻结或停用。所有查询使用当前组织隔离，写命令使用业务幂等键和乐观锁并写入公共审计。新路由使用`ai.modeling.read`、`ai.modeling.manage`和`ai.config.manage`，没有把旧`ai.use`或`ai.model.manage`自动映射为新权限。

V57仍是仓库唯一新增迁移文件。本轮在它首次落库前直接完成了R02所需结构，不新增V58，也没有修改V1～V56。当前`.env`数据库和用户提供的测试环境数据库均未执行DDL、迁移或数据写入。

## 2. 数据库与领域规则

### 2.1 V57最终配置结构

V57中的配置段现在明确包含：

- `ai.prediction_target.current_input_scheme_id`，并以组织和目标复合外键约束当前方案。
- `ai.target_version.value_type`，历史Y版本保存完整结果类型。
- `ai.source_mapping_version.target_version_id`，映射绑定具体Y版本。
- `ai.input_field_version`保存类型、单位、可用时点、标准字段引用、定义和预处理。
- `ai.input_scheme.target_version_id`及`material_dictionary_version_id`，方案哈希同时包含Y版本、X版本清单、预处理和材料字典哈希。
- `ai.configuration_command_receipt`，按组织、操作和幂等键保存请求哈希及返回资源；同键不同载荷返回`IDEMPOTENCY_CONFLICT`。
- `mdm.material_alias`和`ai.material_dictionary_version`，别名按组织唯一，字典按内容哈希和版本唯一。

数据库触发器保护已发布Y/X/来源映射/训练策略、冻结输入方案及字段清单、冻结材料字典、Snapshot和正式模型载荷。冻结材料字典允许通过受控命令转为`RETIRED`，但不能更改词表、编码器、哈希和历史元数据。

### 2.2 发布和冻结门禁

实际服务执行以下规则：

- 连续Y必须填写单位、合法上下界、测试方法、SOP、测试阶段、预处理和优化方向；发布前至少有一份该Y版本的已发布来源映射。
- 序数Y至少有两个无重复有序类别；二分类恰有两个类别且正类属于类别集合；多分类至少有两个无重复类别。
- 普通X发布前必须绑定有效标准字段；配方组成X必须同时绑定`FORMULA.ITEM.MATERIAL_CODE`和`FORMULA.ITEM.RATIO`。
- 通用输入方案创建接口只接受已发布Y版本和已发布X版本，拒绝`POST_EXPERIMENT`字段，当前选入字段全部为必需字段且顺序从0连续。
- 方案包含配方组成字段时必须绑定已冻结材料字典；方案冻结与Y当前方案指针在同一事务内按revision原子切换。
- 冻结后旧方案、旧字典和已有模型绑定不随新配置变化。

初始化时创建的60°光泽初始方案是受控例外：它与8个X草稿、Y草稿一起建立，便于管理员补齐正式定义后逐项发布；在Y/X发布前不能通过冻结门禁。

### 2.3 材料身份

`mdm.material`仍是唯一材料身份来源，没有从旧任务档案自动生成材料。别名规范化采用Unicode NFKC、首尾去空白、连续空白合并和大小写折叠，`/`、`-`、`=`等材料编码字符保持原义。同组织规范化别名冲突时返回现有映射证据。

材料字典从有效MDM材料中选择，保存角色、从0开始的编码顺序、令牌和编码器配置。创建新内容会形成新版本；冻结后内容不可改，停用不破坏历史方案和模型引用。

## 3. 目录初始化

历史版本曾提供 UV/PU 目录初始化动作；该入口已移除。当前 Y/X 通过“新增 Y”、正式 SQL 和来源映射配置维护，页面不再要求先初始化一套预置目录。

首次执行创建19个Y草稿、8个60°光泽初始X草稿和1份未冻结输入方案；重复调用不会重复创建。初始化没有迁入100%归一化、固定材料槽位、旧训练门槛或材料名单。缺少正式SOP、值域、类别或来源映射的Y保持草稿并被发布门禁阻断。

## 4. API、权限和页面

### 4.1 后端接口

`ai-rnd.v1.openapi.yaml`已经把R02接口的通用`JsonObject/Page/Ok`占位替换为具体请求、响应和分页结构，覆盖：

- Y目录、Y版本、发布和停用。
- X目录、X版本和发布。
- 来源映射版本及发布。
- 输入方案、预览和冻结。
- 训练策略版本及发布。
- 标准字段和MDM材料只读投影。
- 材料别名、材料字典版本、冻结和停用。
- UV/PU目录显式初始化。

分页目录按`updated_at DESC, id`稳定排序；Y列表支持关键词、状态、结果类型和分类，X列表支持关键词、状态和字段类型。所有失败响应实际输出`code/message/requestId/detail`，权限过滤器对AI研发路由也使用相同错误结构。

### 4.2 建模设置页面

页面已接入真实API和IAM：

- Y页支持筛选、初始化、新建、版本编辑、来源映射、输入方案预览/冻结及高级训练策略。
- X页支持筛选、新建、版本历史、标准字段绑定，以及材料别名和材料字典管理。
- 仅有`ai.modeling.read`时进入完整只读态；配置按钮按`ai.modeling.manage`和`ai.config.manage`分别控制。
- 菜单与路由要求`ai.modeling.read`，页面不调用静态TaskProfile或旧FeatureView。
- R03/R04完成前，目标数据状态和方案预览固定显示“待评估”和`DATA_PIPELINE_NOT_READY`；样本数、可训练数、排除数和变化样本均为`null`或空集合，不显示伪造的0条统计。
- 尚无正式模型时显示“尚未训练”，不借用旧案例或演示模型状态。

## 5. 验证结果

数据库验证全部使用隔离实例。Docker服务当时不可用，因此同一个`FlywayMigrationIT`通过外部测试参数连接临时启动的本机PostgreSQL 18.4和pgvector；测试代码仍保留Testcontainers默认路径。

2026-09-14又完成了一次真实服务与浏览器验收：前端Vite、Spring Boot和PostgreSQL 18.4隔离库同时启动，通过真实IAM登录进入`/assistant/modeling-settings`。验收覆盖UV/PU目录初始化、Y/X双页签、60°光泽定义/来源映射/输入方案详情、方案预览，以及未满足发布条件时的冻结阻断；浏览器控制台没有错误或警告。实测同时修复了三项启动与展示问题：SnakeYAML改为后端运行时依赖；未配置外部AI模型时各Spring AI provider默认使用`none`；侧栏精确选中当前路由，Y/X表格在页面范围内稳定滚动和换行。

| 检查 | 结果 |
|---|---|
| 空库迁移 | V1～V52 → MDM/RND基础DDL → V53～V57通过 |
| R02目录初始化 | 首次19个Y、8个X、1个方案；相同键重放和新键重复初始化通过 |
| 幂等冲突 | 相同幂等键不同请求载荷返回409对应错误码 |
| 发布门禁 | 不完整60°光泽Y发布被阻断；未发布Y/X不能通过通用方案创建/冻结 |
| 预览边界 | `NOT_EVALUATED`、`DATA_PIPELINE_NOT_READY`，所有样本统计为空 |
| 数据库约束 | 跨组织FK、同Y双ACTIVE、已发布/冻结内容修改均被拒绝；演练事务结束回滚 |
| OpenAPI与权限 | YAML可解析；所有操作唯一权限；R02接口使用具体Schema；错误结构测试通过 |
| Java定向测试 | `AiRndContractTest`、`AiRndExceptionHandlerTest`、`ConfigurationHashingTest`、`ModelingCatalogResourceTest`、`PermissionRouteFilterTest`、`IamPermissionCatalogTest`通过 |
| Flyway集成测试 | PostgreSQL 18.4隔离库完整执行并通过服务级初始化、幂等、发布阻断和预览断言 |
| Web类型检查 | `tsc -b --pretty false`通过 |
| Web R02 Lint | 建模设置页面、AI研发客户端/类型及路由测试按当前ESLint规则通过 |
| Web R02测试 | 4个测试文件、9项通过，覆盖双页签、只读空态、显式初始化、API幂等头、材料字典revision、类型和权限 |
| Web生产构建 | Vite构建及bundle gate通过，主包1077KB，小于1200KB门槛 |
| 真实页面验证 | 真实IAM登录、目录初始化幂等、Y/X列表、60°光泽详情、方案预览和冻结门禁通过；浏览器控制台无错误或警告 |

全仓库前端测试实际执行了189项，其中188项通过；原有`ResearchTestListPage.test.tsx`的“导入文件”按钮断言失败，与R02页面及路由无调用关系。全仓库ESLint仍有255个既有错误，集中在库存页、项目页和旧测试；R02新增范围已使用同一规则集单独通过。后端全量测试的既有阻断为旧架构边界违规和缺少`docs/word-import-smoke/*.docx`测试资料；R02定向测试及完整迁移测试均通过。以上基线问题未被记为R02功能通过，也未在本任务扩大范围修改。

## 6. 边界与R03入口

R02没有实现双来源事实接入、资格计算、人工审查、Snapshot调度、训练、模型发布或预测。旧TaskProfile、FeatureView和旧AI路由仍物理保留，供分阶段开发期间旧入口运行；R10按既定清单一次性断流和删除，本轮没有增加新旧契约兼容层。

R03可以直接使用V57已冻结的`data.confirmed_submission*`、`ai.training_sample`、`ai.sample_source`、`ai.sample_revision`和来源映射结构，实现数据中心确认提交与实验本COMPLETED版本的统一事实、修订、接管和同源去重。R03接入前不得把建模设置中的“待评估”替换为推算值。
