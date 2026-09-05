# jsd-aird-ai

T07-A/T07-A.1/T07-A.2/T07-A.3/T07-A.4/T07-A.5 的独立 Python 计算服务。它负责不可变快照校验、
Lineage/Sheet 双重分组验证、连续目标的 GP/RF/LightGBM/XGBoost/CatBoost
统一竞争、硬度序数预测、Binary/Categorical分类竞争、区间/概率校准、模型包生成、评分和 BayBE 候选
推荐。服务没有数据库权限，也不会创建实验、激活生产模型或发布配方。

## 本地运行

推荐 Python 3.12；当前代码同时支持 3.11，方便仓库现有 Windows 工具链运行测试。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
.\.venv\Scripts\python.exe -m pip install --no-deps -e .
.\.venv\Scripts\python.exe -m pytest -m "not golden"
.\.venv\Scripts\python.exe -m pytest -m golden
.\.venv\Scripts\python.exe -m jsd_aird_ai.main
```

默认监听 `127.0.0.1:8090`。容器内监听 `0.0.0.0:8090`。

本地文件 URL 默认关闭。只有测试或离线黄金数据运行时才设置：

```powershell
$env:JSD_AIRD_AI_ALLOW_FILE_URLS='true'
```

现有 V2 快照使用的自定义 Parquet 页脚与标准读取器不兼容。离线验收前可从
原始 XLSX 重建标准 PyArrow 副本；脚本不会覆盖原始快照：

```powershell
.\.venv\Scripts\python.exe tools\rebuild_uvpu_snapshot.py
```

内部接口为：

- `POST /internal/v1/snapshots/validate`
- `POST /internal/v1/models/train`
- `POST /internal/v1/models/score`
- `POST /internal/v1/models/recommend`
- `GET /internal/v1/health/live` 和 `GET /internal/v1/health/ready`

## T07-A.1：可重复的X→多Y开发调用

以下命令均在仓库根目录执行。首次训练当前正式任务档案对应的开发模型：

```powershell
.\.tmp\t07-venv\Scripts\python.exe jsd-aird-ai\tools\train_uvpu_dev_model.py
```

模型包按SHA-256内容寻址保存到：

```text
.runtime/formula-models/UVPU_APPLICATION_FORMULATION/1.0/synthetic-development/
```

相同档案、快照和种子默认复用已有模型；需要重新训练时增加`--force`。直接输入
示例X并预测多个Y：

```powershell
.\.tmp\t07-venv\Scripts\python.exe jsd-aird-ai\tools\score_uvpu_dev_model.py
```

修改`jsd-aird-ai/examples/uvpu-score-input.json`即可输入其他配方和工艺。通过真实HTTP
接口验证时，先启动服务：

```powershell
$env:JSD_AIRD_AI_ALLOW_FILE_URLS='true'
.\.tmp\t07-venv\Scripts\python.exe -m jsd_aird_ai.main
```

再在另一个窗口调用：

```powershell
.\.tmp\t07-venv\Scripts\python.exe jsd-aird-ai\tools\score_uvpu_http.py
```

`.runtime`中的模型和最近响应是本机开发制品，不提交Git。跨语言契约使用脱敏的
`contracts/formula-model.v1/examples`黄金请求和响应。

## T07-A.2：算法门禁

- 每个目标同时执行 `formula_lineage_group` 与 `source_sheet` 的 5 折 GroupKFold；
  随机 KFold 仅可作为关闭状态的诊断项。
- 连续目标统一比较五种 Adapter；少于任务档案 `modelEligibility` 门槛的模型会被
  标记为 `SKIPPED_INSUFFICIENT_SAMPLES`，不会阻断其他模型。
- 五种连续模型的正式 90% 区间都来自 group-isolated OOF residual conformal；GP
  额外保留后验原生区间用于诊断。
- 铅笔硬度走 cumulative ordinal logistic，返回业务等级与等级概率；删失次数模型
  尚未实现，`CENSORED_COUNT` 不会静默降级成普通连续回归。
- 当前 400 条快照固定为 `SYNTHETIC`、`productionEligible=false`，且基线为
  `DEVELOPMENT_KNN`，因此任何指标结果都不能成为生产激活依据。
- 单模型线程数由 `JSD_AIRD_AI_MODEL_THREADS` 控制，默认 2；在线评分只加载冠军。

## T07-A.3：适用域与泛化门禁

- 每个目标按自身有效训练行构建适用域，输出 `IN_DOMAIN`、`NEAR_BOUNDARY` 或
  `OUT_OF_DOMAIN`、最近训练行距离、未知类别、数值越界字段和回退原因。
- 90%业务区间采用内层Group OOF残差校准、外层Group验证独立统计PICP；
  Lineage CV与Sheet CV分别执行，外层验证目标不会参与自身区间半径校准。
- 模型卡保存有效行、两套fold manifest、折间NMAE波动和算法等价哈希；耗时只保留在
  本次TrainResponse运行观测中，不参与核心模型制品身份。
- `tools/replay_uvpu_snapshot.py`用于多seed完整重训，
  `tools/stability_uvpu_snapshot.py`用于较快的跨seed点预测CV和OOF指标扰动诊断，
  `tools/benchmark_uvpu_scoring.py`用于1至20,000行的冠军模型批量评分基准。

## T07-A.4：实验优化与BayBE搜索质量闭环

- 推荐链先执行任务档案材料约束，再仅对本次`request.targets`执行A.3适用域；模型包中
  未请求的Y不会参与淘汰。`OUT_OF_DOMAIN`候选不会
  进入冠军模型评分，`NEAR_BOUNDARY`只允许作为带风险标记的探索候选。
- 响应提供原始候选、约束淘汰、重复、OOD、Near Boundary、最终可评分空间、BayBE
  短名单和空间不足原因。
- 实验优化明确区分`CONTROL`、`CONSERVATIVE`、`BALANCED`、`EXPLORATORY`；探索价值
  同时组合不确定性、适用域覆盖缺口、历史配方距离和基本预测潜力。
- CONTROL返回`controlSource=EXACT_BASELINE|NEAREST_FEASIBLE_BASELINE`，不会把域外
  baseline的最近可行替代误称为原配方复测。
- `FORMULA_PREDICTION`仅按目标满足概率排序，`EXPERIMENT_OPTIMIZATION`额外考虑保守区间、
  信息价值及历史/批次多样性，两者不共享同一个换名排序函数。
- `tools/recommend_uvpu_dev_experiments.py`生成固定开发模型的四角色推荐；
  `tools/replay_uvpu_experiment_optimization.py`执行5至10轮只读Synthetic回放，并比较
  AI优化、随机可行和局部扰动策略。回放同时输出共同最小实验预算、固定预算检查点，
  以及只加入探索点前后固定held-out集的NMAE、PICP90、区间宽度、域覆盖和最近训练距离。

## T07-A.5：Binary/Categorical Target Pipeline

- Binary/Categorical均比较LogisticRegression、RF、LightGBM、XGBoost和CatBoost；
- 所有分类器共享相同有效行、Lineage/Sheet外层fold和类别编码；CatBoost使用原生类别，
  其余模型复用OneHot Pipeline；
- 概率校准来自外层训练集内部的Group OOF概率，外层验证行不参与本折校准；
- Binary输出业务类别、raw/calibrated概率、正类概率和阈值，Categorical输出所有观察类别概率；
- Categorical冠军默认选择Lineage/Sheet worst-side综合分最低模型；近似容差只作诊断，真实同分
  才依次按双CV稳定性、概率质量、计算成本和最终确定性模型顺序决胜；
- 分类预测复用A.3适用域，OUT_OF_DOMAIN时`modelUsable=false`；
- 独立Synthetic分类fixture不会修改400条黄金快照，运行完整五分类器验收：

```powershell
.\.tmp\t07-venv\Scripts\python.exe jsd-aird-ai\tools\train_synthetic_classification_fixture.py
```

## 安全边界

- 生产请求只接受 `http`/`https` 的短期授权 URL。
- 训练输出通过调用方提供的短期 PUT URL 写回对象存储。
- 模型包必须先通过请求 SHA-256 和包内清单校验，才会交给 joblib 加载。
- 完整配方和客户数据不写入应用日志。
