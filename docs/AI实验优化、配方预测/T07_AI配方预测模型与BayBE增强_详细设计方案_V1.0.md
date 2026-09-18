# T07 AI配方预测模型与BayBE增强详细设计方案 V1.0

> 日期：2026-09-03  
> 状态：T07-A、T07-A.1、T07-A.2、T07-A.3、T07-A.4、T07-A.5、T07-B已完成
> 首个任务档案：`UVPU_APPLICATION_FORMULATION`  
> 内部契约：`formula-model.v1`

## 1. 目标与边界

T07为T06“案例、统计、规则”能力增加模型评分和BayBE候选搜索，但不替代T06。
模型不可用、目标未覆盖、快照不合格或服务调用失败时，客户请求必须回退
`CASE_STAT_RULE`。任何Python候选都必须经过Java硬规则终审，模型不能直接发布
配方、创建正式实验或修改已完成实验。

为避免依赖并行开发中的T03—T06，T07分为：

- **T07-A算法预开发**：A完成独立服务与GP/RF闭环，A.1完成可重复X→多Y调用，
  A.2完成Lineage/Sheet双CV、GP/RF/LightGBM/XGBoost/CatBoost竞争、统一conformal UQ、
  Ordinal硬度与配置化生产门禁；A.3完成目标级适用域、嵌套分组Conformal、验证制品
  固化、稳定性摘要与批量评分基准。
- **T07-B 生产集成适配**：已接入T05正式分析投影、T06同批同折案例基线/规则、
  PostgreSQL异步任务、对象存储授权和客户研究任务。

T07-A不增加Flyway迁移，不修改T03—T06客户页面或接口。

## 2. 总体架构

```text
COMPLETED实验版本
  -> ExperimentAnalysisView（T05，只读）
  -> Snapshot Builder（Java）
  -> measurements.parquet + source-map.parquet + manifest.json
  -> FORMULA_MODEL_BUILD（Postgres Worker）
  -> jsd-aird-ai（无数据库权限）
  -> 不可变模型包 + 逐目标模型卡
  -> 按目标激活
  -> T06规则预筛 -> 模型评分/BayBE搜索 -> Java规则终审
  -> research_run候选 -> 用户主动创建实验草稿
```

`jsd-aird-ai`采用Python 3.12 CPU容器，浏览器不能访问。Java通过内部Bearer认证
调用同步计算接口；长任务、幂等、重试、取消和超时由已有Postgres Worker负责。

## 3. 契约

契约源位于：

- `jsd-aird-ai/contracts/formula-model.v1/formula-model.v1.schema.json`
- `jsd-aird-ai/src/jsd_aird_ai/contracts.py`
- `jsd-aird-ai/src/jsd_aird_ai/task_profiles/uvpu_application_formulation.v1.json`

所有请求固定携带：

- `contractVersion = formula-model.v1`
- `requestId`
- `taskProfileHash`
- `snapshotHash`
- `seed`

评分和推荐请求还必须携带顶层`modelBundleHash`，并与短期下载引用中的SHA-256完全
一致；校验和训练阶段尚无模型包，因此不携带该字段。

训练、评分和推荐通过SHA-256绑定任务档案、快照和模型包。模型包中的
`model-payload.joblib`只有在外层包哈希、包内清单哈希和契约版本全部通过后才能
反序列化。

### 3.1 内部接口

| 方法 | 地址 | 行为 |
|---|---|---|
| `POST` | `/internal/v1/snapshots/validate` | 校验三件套、谱系、共享工艺和目标覆盖 |
| `POST` | `/internal/v1/models/train` | 训练GP/RF并将模型包写入短期PUT URL |
| `POST` | `/internal/v1/models/score` | 对最多20000条配方/上下文组合评分 |
| `POST` | `/internal/v1/models/recommend` | 产生对照、保守、平衡、探索候选 |
| `GET` | `/internal/v1/health/live` | 进程存活 |
| `GET` | `/internal/v1/health/ready` | 契约及BayBE/pandas/pyarrow/sklearn版本 |

错误码固定为`INVALID_SNAPSHOT`、`HASH_MISMATCH`、`UNSUPPORTED_CONTRACT`、
`INSUFFICIENT_DATA`、`UNSUPPORTED_TARGET_TYPE`、`MODEL_NOT_READY`、
`NO_FEASIBLE_CANDIDATE`和`INTERNAL_COMPUTE_ERROR`。契约、哈希和数据错误不可重试；
网络及5xx最多重试两次。

### 3.2 T07-B Java接口

T05必须通过`rnd::api`提供`ExperimentAnalysisProvider`，AI模块不得访问RND Repository。
AI模块增加`FormulaModelFacade`，提供readiness、score、recommend、buildSnapshot、
activateTarget和rollbackTarget。T06现有客户接口只增加以下响应字段：

- `mode: MODEL | HYBRID | CASE_STAT_RULE`
- `modelStatus: READY | PARTIAL | UNAVAILABLE`
- `snapshotId/modelVersionIds/taskProfileVersion/ruleVersion/seed`
- 每目标`expected/lower/upper/unit/scorerType`
- `fallbackReasons`

## 4. UV/PU任务档案与快照

配方由六选一主树脂、`DSP-3315`、`SS059/PC=1/1`和余额组分`S-48`组成。
搜索参数化为`mainResinCode + mainResinPct + 两种助剂`，随后计算：

```text
S-48 = 100 - mainResinPct - DSP-3315 - SS059/PC=1/1
```

总和必须为`100±0.02`。膜厚是实验级字段；固含、UV强度、UV能量、温湿度、
基材、施工方式和固化光源在当前原始报告中按Sheet共享。
任务档案同时版本化每个目标的编码、类型、方向、单位、测试方法、基材、mandatory
和权重，以及规则版本、材料步长、候选最小距离和保守候选最大距离。

首期模型目标：

- `Y__WARPING_PET_INITIAL_CM`，连续，最小化；
- `Y__WARPING_PET_12H_CM`，连续，最小化；
- `Y__STEEL_WOOL_500G_CYCLES`，连续，最大化；
- `Y__STEEL_WOOL_1KG_CYCLES`，连续，最大化。

`Y__HARDNESS_PET_1KG_ORD`保留为序数目标，只检查取值集合，不转为连续回归，
返回`UNSUPPORTED_TARGET_TYPE`并由T06降级处理。

### 4.1 模拟数据修复说明

原V2自定义Parquet的footer声明400行，但PyArrow 21解码为0行。原文件保持不变，
实现提供`tools/rebuild_uvpu_snapshot.py`，从原始模拟XLSX生成标准PyArrow快照：

`UVPU_APPLICATION_FORMULATION_Synthetic_TrainingSnapshot_V2_Shared_Process_PyArrow`

修复快照为400条测量、40个配方谱系、67个来源Sheet；目标有效数分别为378、
371、386、380和306。XLSX展示值有四舍五入，因此该快照只用于工作流和算法契约
验收，不用于生产效果声明。`production_eligible=false`不可绕过。

## 5. 建模与验证

每个连续目标独立处理，目标缺失行不参与该目标训练，绝不填0。所有必需特征
缺失的行被排除并计入覆盖报告。

- 采用5折`GroupKFold(formula_lineage_group)`，同谱系不得跨折。
- GP使用Matérn 5/2 ARD、噪声核和目标标准化。
- RF在每个外层训练折内比较三组树数、`min_samples_leaf`和`max_features`配置，
  外层验证折只用于无偏评估；最终模型采用各训练折最常胜出的配置。
- 主要指标为`NMAE = MAE / (P95-P5)`；同时输出MAE、RMSE、R²、Spearman、
  90%区间覆盖率和平均区间宽度。
- GP使用后验90%区间；RF使用分组OOF残差的90% conformal半径。
- GP/RF NMAE差小于0.01时选择GP，否则选择NMAE更低者。
- T07-A以分组KNN作为离线相似案例基线；T07-B必须与T06基线做跨语言黄金比对。
- 另按`source_sheet`分组报告冠军模型NMAE作为共享工艺泛化审计指标，不替代谱系主切分。

门槛按目标判定：

| 门槛 | 条件 | 能力 |
|---|---|---|
| 开发验证 | 至少30条、5个谱系 | 可训练、评估、生成模型包 |
| 生产激活 | 至少60条、12个谱系，NMAE≤20%，相对T06基线改善≥10%，快照允许生产 | 可人工激活 |

模型包允许`PARTIAL`：通过的连续目标继续工作，其他目标按目标降级。

## 6. BayBE搜索

BayBE固定为0.15.0并隔离在`BaybeOptimizerAdapter`中。先按任务档案使用Sobol和
类别枚举产生最多20000个候选，计算余额组分并完成硬约束预筛；再把候选与已测量
点建立为BayBE显式离散空间，使用GP和多目标desirability生成短名单。冠军GP/RF
对短名单重新评分，Java最后复核规则。

固定策略：

- `CONTROL`：基线或最近合规配方；
- `CONSERVATIVE`：按预测区间悲观边界排序；
- `BALANCED`：BayBE短名单按多目标期望desirability重排；
- `EXPLORATORY`：在强制目标约束下优先区间宽度和信息增益。

候选间L1距离默认至少2个百分点。空间不足时返回更少候选和`missingStrategies`，
不得补造违规结果。每个候选明确返回“仍需Java权威规则校验”。
`CONSERVATIVE`与基线的L1距离默认不超过10个百分点（约等于某一非平衡组分变化
5个百分点时连同`S-48`产生的总L1变化），阈值由任务档案版本化。

## 7. T07-B持久化与运维

合并时使用主分支下一个空闲Flyway版本，新增：

- `ai.formulation_task_profile`
- `ai.formula_model_snapshot`
- `ai.formula_model_version`
- `ai.formula_model_target`
- `ai.formula_model_activation`

快照、模型和激活历史只追加不覆盖；每个组织、任务档案、目标只能有一个当前激活
记录。对象键为：

```text
ai/formula-models/{organizationId}/{taskProfileCode}/{snapshotId}/{artifactName}
```

`ops::api`需要提供模型专用短期GET、预留PUT和上传后SHA-256复核边界，并新增
`app.storage.compute-endpoint`。本地文件存储不能提供容器授权时返回
`MODEL_STORAGE_UNAVAILABLE`，不影响T06。

异步任务包括`FORMULA_MODEL_BUILD`和`FORMULA_MODEL_ROLLBACK`；`RESEARCH_RUN`
增加可选模型阶段。`AsyncJobHandler`增加超时覆盖：校验15分钟、训练4小时、评分/
推荐60秒，未覆盖时继续使用全局15分钟。

首个生产版本人工按目标激活。快照新增至少10条或增长20%后可在周日02:00提出重建
建议；新旧模型必须在同批同折条件下按目标主指标比较，系统不按跨目标综合改善自动
替换。网络、超时、计算服务或对象存储临时故障只使当前请求降级，不改变活动模型；
模型包损坏、哈希异常、加载或版本兼容失败时暂停对应目标等待人工复评。最近10条正式
反馈误差超过验证误差1.5倍时同样只暂停并告警，是否回退上一版本由实施方人工决定。

模型运维新增`ai.model.manage`，客户使用仍为`ai.use`；查看来源和创建草稿分别继续
要求`experiment.view`和`experiment.create`。

## 8. 验收

T07-A必须通过：JSON Schema/Pydantic契约、快照哈希、共享工艺、谱系隔离、缺失与
真实0、四连续目标训练、序数目标降级、模型包防篡改、评分、BayBE候选、多样性、
生产资格阻断、内部鉴权和容器配置测试。

### 8.1 T07-A.1开发交付闭环

为使正向预测在T07-B合并前可重复使用，开发环境增加三个命令：当前正式任务档案
训练、本地直接评分和真实HTTP评分。模型包采用SHA-256内容寻址，
`active-dev-model.json`只作为本地开发指针；档案、快照、种子和模型包哈希全部匹配
时默认复用，任何一项变化都必须重新训练。运行制品位于`.runtime`且不提交Git。

输入文件使用与正式接口相同的`ScoreRow`结构，可一次提交一行或多行X。黄金请求将
实际模型哈希与脱敏短期URL组合，黄金响应保存固定预测值，供T07-B Java/Python
双向契约测试直接复用。

### 8.2 T07-A.2生产算法门禁对齐

每个连续或序数目标同时执行按`formula_lineage_group`和`source_sheet`隔离的5折
GroupKFold。连续目标五种Adapter共用完全相同的目标行和外层fold，超参数只在外层训练
折内选择；CatBoost使用原生类别特征，其余模型使用统一数值化/OneHot Pipeline。

所有生产和模型参赛门槛来自任务档案。五种连续模型的正式90%业务区间统一采用分组隔离
OOF绝对残差conformal校准，GP后验区间仅作为额外诊断。硬度走累计Logistic序数模型；
`CENSORED_COUNT`在完整删失模型实现前禁止降级为普通回归。

当前基线明确标为`DEVELOPMENT_KNN`。固定Synthetic快照始终保持`dataNature=SYNTHETIC`、
`productionEligible=false`，即使数值门槛全部通过也返回
`SYNTHETIC_DATA_NOT_PRODUCTION_ELIGIBLE`，不得创建或激活生产模型。

### 8.3 T07-A.3模型适用域与泛化门禁加固

每个目标使用该目标过滤缺失值后的有效训练行建立独立适用域。通用数值化/OneHot空间
用于计算归一化欧氏距离，训练阈值来自同时排除同谱系与同来源Sheet样本后的最近邻距离
分布；同时保存每个数值特征的观测范围与1%/99%稳健边界、每个类别特征的已知取值。
未知类别、越过数值观测范围或距离超过99%阈值判为`OUT_OF_DOMAIN`，默认要求降级；
距离超过95%阈值或落入数值边界区判为`NEAR_BOUNDARY`。适用域只说明统计覆盖，不输出
因果解释，也不代替Java硬规则。

连续目标的业务区间采用嵌套分组Conformal：每个Lineage/Sheet外层训练折内部产生分组
隔离OOF残差并确定该折半径，再只在未参与校准的外层验证折统计PICP90。全量部署半径
可由完整外层OOF残差确定，但不得反向用于验证PICP。生产门禁继续要求两种外层PICP
同时达标。

模型卡为每目标保存有效行ID哈希、Lineage fold哈希、Sheet fold哈希、折大小、候选折间
NMAE和冠军折间稳定性。另生成排除运行耗时的`algorithmEquivalenceSha256`；相同快照、
档案、seed、依赖版本、fold、参数和核心指标可判断为算法等价。多seed重放与批量评分
性能通过独立工具执行，不扩大普通训练接口，也不依赖数据库。

### 8.4 T07-A.4 AI实验优化与BayBE搜索质量闭环

推荐链固定为“任务档案与请求材料约束 → Sobol显式候选池 → 本次请求目标适用域 → OOD剔除 →
冠军模型评分 → 强制目标保守区间门禁 → BayBE短名单 → 四角色重排 → 历史与批次多样性”。
任何`OUT_OF_DOMAIN`候选不得进入冠军scorer；`NEAR_BOUNDARY`不得成为CONTROL、
CONSERVATIVE或BALANCED，只能在具备基本目标潜力、历史新颖性和批次距离时成为受控探索。
模型包内未出现在`request.targets`的Y不得参与候选域聚合。CONTROL必须返回
`EXACT_BASELINE`或`NEAREST_FEASIBLE_BASELINE`来源，后者不得表述为原配方原样复测。

实验优化与配方预测采用不同模式。`FORMULA_PREDICTION`最大化当前目标的期望满足度；
`EXPERIMENT_OPTIMIZATION`同时考虑期望多目标desirability、保守区间、模型区间宽度、
适用域覆盖缺口、历史配方距离和本批多样性。探索价值不是区间宽度的别名。

响应保存搜索空间全阶段计数、候选配方差异、逐目标预测及区间、域状态、最近训练距离、
历史/批次L1距离、预测性改善与退让、结构化目标冲突、选择理由及风险。以上均为模型证据，
不输出SHAP或因果表述。空间不足时返回更少候选、`missingStrategies`及对应原因。

独立回放工具以只读400条Synthetic快照拟合隐藏ExtraTrees oracle，从100条Round 0开始，
每轮重新拟合内存GP、分组Conformal和适用域并推荐下一批，支持5至10轮；同时对比随机
可行与局部扰动。策略比较同时输出共同最小实验预算和4/8/12/16等预算检查点；每轮在固定
held-out oracle集合上比较只加入EXPLORATORY点前后的NMAE、PICP90、归一化区间宽度、
IN_DOMAIN/非OOD覆盖率和最近训练距离。回放状态不写回Parquet，前后文件哈希必须一致，
结果只验证工作流。

### 8.5 T07-A.5 Binary/Categorical Target Pipeline

Binary与Categorical目标复用同一有效行、Lineage/Sheet外层fold、任务档案、适用域和模型包。
候选分类器为LogisticRegression、RandomForest、LightGBM、XGBoost和CatBoost；前四者复用
OneHot数值化Pipeline，CatBoost原生处理类别特征。Binary冠军综合概率质量、PR-AUC、F1和
少数类recall；Categorical冠军综合macro-F1、balanced accuracy和概率质量，禁止按accuracy
单指标选择。Categorical以Lineage/Sheet两侧较差的综合`selectionScore`最低值为一级排序；
配置的近似平局带只作诊断，不能让固定模型名称顺序覆盖更低分模型。仅真实同分时依次比较
双CV fold稳定性、较差侧calibrated log loss、训练加推理成本，最后才使用确定性模型顺序。

概率校准只使用外层训练折内部的Group OOF概率，外层验证行不得参与本折校准。评分输出保留
业务类别名、raw/calibrated概率、正类概率/阈值或全部类别置信度，并执行与连续目标相同的
Applicability Domain；OUT_OF_DOMAIN必须`modelUsable=false`。单类别、少数类不足或观察类
不足返回`MODEL_NOT_READY`。CENSORED_COUNT继续保留，不在A.5实现。

T07-B必须通过：Java/Python黄金契约、PostgreSQL/MinIO集成、COMPLETED来源限定、
T06基线一致性、目标级降级、Python停机/超时/非法响应/哈希篡改降级、Java规则
终审、异步幂等、历史不覆盖、固定研究版本、激活/回退和Modulith边界测试。

遗留`ai.training_dataset`和`ai.training_dataset_record`不得读取或迁移。首期不引入
MLflow、DVC、RabbitMQ、GPU或独立算法工作台。
