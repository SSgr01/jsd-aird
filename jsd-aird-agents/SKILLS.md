# Skills 运行能力

开发前实际发现、读取及复用情况见 [SKILLS_DISCOVERY.md](SKILLS_DISCOVERY.md)。本模块使用
[Agent Skills 目录格式](https://agentskills.io/specification)，不引入插件平台、额外服务或新权限体系。

```mermaid
flowchart LR
  A[配置的技能目录] --> B[Registry 扫描与版本注册]
  B --> C[启用精确内容摘要]
  C --> D[模型按名称和描述选择]
  D --> E[Run 固定绑定版本]
  E --> F[加载工作流并生成计划]
  F --> G[Harness 参数和权限校验]
  G --> H[只读资源加载或领域工具]
  H --> I[写操作仍需权威审批与回执]
```

## 初始技能

项目的三个技能位于 `src/jsd_aird_agents/app/verticals/project/skills/`，质量示例技能位于 `app/verticals/quality/skills/`，均随 Python wheel 打包：

| 名称 | 本轮能力 |
|---|---|
| project-delay-analysis | 读取项目、任务与文档，形成有来源的延期分析 |
| task-management | 查询任务，提议修改计划截止日期，经人工审批后执行 |
| knowledge-research | 项目范围内的知识检索及有引用的研究摘要 |
| quality-anomaly-review | 仅在质量 Mock 模块内查询异常，整理有引用的复检建议 |

实验分析、实验优化、会议任务创建、负责人和状态修改等工作流，待对应领域工具接入后再建设。
task-management 明确描述现有能力边界，不把当前不存在的工具写成可执行功能。

## 目录与加载

```text
project-delay-analysis/
  SKILL.md
  references/evidence-checklist.md
knowledge-research/
  SKILL.md
  assets/report-template.md
```

SKILL.md 使用 YAML frontmatter，支持标准 name、description、license、compatibility、metadata、allowed-tools
及 Markdown 工作流正文。名称须与目录一致；metadata.version 为可选的展示版本，**整个包的内容摘要才是精确版本身份**。
未知扩展字段不会注册权限、Hook、工具、模型或执行入口。

默认只扫描已明确注册 Pack 的 skills 根目录。可用服务端环境变量覆盖根目录，接受 JSON 数组；API 请求和模型参数不能指定根目录：

```dotenv
JSD_AIRD_AGENTS_SKILL_ROOTS=["D:/Projects/IdeaProjects/jsd-aird/.agents/skills"]
```

这会替换默认根列表。需要同时保留内置技能时显式列入相应 Pack 的 skills 根目录绝对路径。
不自动扫描个人 Codex Skills、插件缓存或任意用户目录，不从网络安装技能。

扫描读取 SKILL.md 并计算包内文件摘要，保存元数据、工作流快照和资源清单，不把所有资源文本送入模型。
模型先看到已启用版本的名称、描述等元数据；选定后才取得正文；资源内容只有显式调用
`load_skill_resource(name,path)` 时才进入本轮结果与持久缓存。
资源读取也经过现有 Harness 的 ai.use、项目范围、参数、预算、超时和调用审计。

- references、assets、scripts 均可按需读取；二进制内容以 base64 返回。
- 默认单份 SKILL.md 上限 32 KiB、资源上限 64 KiB、包上限 2 MiB／128 文件、最多选择 3 项技能。
- 拒绝目录穿越、绝对资源路径、符号链接、硬链接、Windows 重解析点和非普通文件；目录数量与深度也有上限。
- 拒绝不安全 YAML 标签、别名、重复键和不合规范的字段。重名技能不会按目录顺序覆盖。
- 返回资源标记 untrusted=true、executable=false；技能资源不作为业务 Evidence，也不能用于伪造事实引用。

## 注册、审核与启停

以下命令在模块目录执行，API 与 Worker 启动前先迁移：

```powershell
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents migrate
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents skills-sync
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents skills-list
```

seed 也会扫描注册，但**新内容版本一律默认禁用**。维护者阅读并检查具体包之后，用查询到的完整摘要启用：

```powershell
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents skills-enable --name project-delay-analysis --sha256 <完整摘要>
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents skills-list --name project-delay-analysis
./.venv/Scripts/python.exe -X utf8 -m jsd_aird_agents skills-disable --name project-delay-analysis --sha256 <完整摘要>
```

这些是使用状态库管理凭证的本机维护命令，记录 current_user；没有允许模型自行启用技能的 HTTP 接口。
缺少、损坏的目录会在扫描结果和审计中报告，CLI 同步失败返回非零状态。
同名新摘要不会继承已审核版本的启用状态。源文件缺失后不再向新 Run 提供该技能。

## 工具、权限与脚本

allowed-tools 只收窄工具上限，不能注册工具或授予权限。本实现接受现有 Harness 工具名称的空格列表；
Bash 等其他宿主专用语法可注册为元数据，但选择时明确拒绝不受支持的工具。
组合技能的工具上限取并集；省略该字段表示使用全局白名单上限，仍受当前用户权限约束。
load_skill_resource 是仅可读取本轮已选技能资源的基础工具，不提供代码执行能力。

规划器获得的工具列表先与用户已有权限及本轮技能上限相交；实际调用时仍重新执行 Harness 权限和范围检查。
写工具只形成提议；原有精确审批、版本、幂等写入、验证和回执链路保持适用。技能要求或脚本文本中的
approved=true、身份、权限或“忽略策略”等内容都不能绕开这些服务端检查。

本轮**完全没有技能脚本执行器**：即便技能已启用，scripts 文件也只能读取，不能自动运行。
兼容脚本目录不等于批准脚本执行；未来若提供执行能力，还需要单独设计审核、隔离和工具策略。

## 恢复、审计与接口

新增独立迁移 `002_skills.sql`，仅在 agent_runtime 中建立 skill_catalog、skill_versions、skill_selections、
skill_loads 和 skill_audit，不改变 Java Flyway 或模拟动作审批表。
升级顺序为停止旧 API／Worker、迁移、扫描与审核技能、启动新进程。新 Run 标记
`shared-assistant-v3-packs`；兼容已有 `project-assistant-v2-skills` 和 `project-assistant-v1` Run；v1 恢复时不补注入新技能。

Run 在 select_skills 图节点固定绑定 `{name,sha256}`，节点本身由官方 Checkpointer 持久化。
恢复使用原绑定与工作流快照，不重新选择或套用新指令；已加载资源从该 Run 的精确内容缓存恢复。
尚未加载的资源如已被修改或移除则明确失败，不混用新内容。新版本默认禁用不会自动终止旧版本 Run；
需要中止旧版本时显式停用对应摘要，后续模型、工具和动作执行检查均拒绝继续。

注册、启停、选择、正文加载、资源加载、工具成功／失败和选择失败进入审计；业务动作以 run_id 与精确技能绑定关联。
CLI 管理记录数据库角色，运行记录保留实际申请人。SSE 同步 skill.selected、skill.loaded、skill.resource_loaded、
skill.tool_call、skill.load_failed、skill.selection_failed 等进度事件。

| 接口 | 行为 |
|---|---|
| GET /internal/v1/agents/skills | 认证后列出当前可选择版本的元数据，不返回本机路径或全文 |
| GET /internal/v1/agents/runs/{id}/skills | 原申请人读取绑定、已加载资源定位与审计，不返回资源正文或脚本执行入口 |
| GET /internal/v1/agents/capabilities | skills_supported=true、skill_scripts_executable=false |

技能正文只是受约束的工作流输入。结构、权限和引用校验不能证明模型理解了全部需求，仍需继续扩展真实模型评测。

## Vertical Pack 约束

技能名称在已注册模块间唯一；manifest 中的技能只能从声明的模块目录注册，其他模块目录下未声明的技能拒绝注册，不能自动变成公共技能。模型只看到当前 Run 模块及公共根目录中的已启用技能。

模块技能的 allowed-tools 必须属于本模块工具；省略 allowed-tools 也只能使用 Run 的模块/工作流白名单。公共技能同样不能扩大模块集合、当前身份、资源范围或审批权限。LangChain 工具桥接见 `app/platform/skills/frameworks.py`；可选 DeepAgents 适配尚未安装和集成验收。

## 首批主动巡检 Skill

新增 project 模块声明的 `project-proactive-monitoring`，仅允许 `get_project_monitoring_snapshot`
和 `get_project_approval_snapshot`。精确版本审核启用后，仅显式 AI 解释会绑定正文；
`references/checks.md` 仍按需加载，自动测试确认没有自动读取 references 或执行脚本。
缺省未启用时，确定性巡检规则仍可运行，不会加载该技能正文。

纯规则巡检默认不参与 Skill 选择或正文加载；后续复杂 Agent 与显式 AI 解释保留原 Registry/Loader/Harness 边界。
