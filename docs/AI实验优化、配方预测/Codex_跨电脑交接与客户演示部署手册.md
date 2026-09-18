# Codex 跨电脑交接与客户演示部署手册

## 先看这里

本交付在功能分支 `feature/jsd-aird-ai` 上。远程 `main` 不包含本轮实现，另一台电脑必须先检出功能分支：

```powershell
git clone https://github.com/SSgr01/jsd-aird.git
cd jsd-aird
git remote add backup https://github.com/SSgr01/jsd-aird.git
git fetch backup
git checkout -B feature/jsd-aird-ai backup/feature/jsd-aird-ai
```

本项目使用 Windows 11、本机 PostgreSQL 和本机进程演示。不要使用当前电脑的 `.env`、数据库、`tmp`、`.runtime` 或模型文件。不要把演示库连接到测试环境数据库。

当前业务状态：R00～R09 已完成，R10 仍在进行中，R11 等待真实生产输入。演示模型和数据都标记为 `SYNTHETIC_DEMO`，即使本机启用也不代表生产模型已经验收。

客户《测试方法及结果清单.xls》对应的正式字段/Y 初始数据已随交接提交，文件为
`scripts/sql/test_field_y_formal_seed_v58.sql`。它不是独立的测试项目目录，也不导入示例结果；脚本只把客户表中的测试项目和方法写入标准字段，并创建待补充的 Y 草稿，示例结果不会被当作训练标签。脚本已做幂等处理，重复执行不会重复创建。

## 依赖

安装以下版本：

- JDK 21。
- Python 3.12。
- Node.js 22.23.2 以上和 npm 10 以上。
- PostgreSQL 16～18，推荐18，并安装对应版本的 pgvector。

LibreOffice 和 MinIO 不属于本地 Windows 演示前置条件。生产和测试环境采用 Docker 部署，必须使用受控的 PostgreSQL/pgvector、MinIO 和 AI 服务容器，并按环境配置凭据；本地演示的目录存储不能替代生产 MinIO。

检查版本：

```powershell
java -version
python --version
node --version
npm.cmd --version
psql --version
```

## 演示环境初始化

复制演示配置并填写本机数据库密码：

```powershell
Copy-Item .env.demo.example .env.demo
notepad .env.demo
```

`.env.demo` 必须连接 `127.0.0.1` 或 `localhost`，数据库名必须以 `_demo` 结尾。它使用本地对象存储和仅限演示的合成模型开关。

首次初始化依赖、演示数据库、服务和模拟输入（会交互询问本机 PostgreSQL 管理员密码）：

```powershell
.\scripts\demo\bootstrap-demo.ps1 -EnvFile .env.demo -Reset -InstallDependencies -StartServices
```

如果需要在演示库中加载客户测试项目和方法，先确认数据库已经完成迁移，再执行：

```powershell
psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 5432 -U postgres -d jsd_aird_demo `
  -f scripts/sql/test_field_y_formal_seed_v58.sql
```

执行后在“模型中心 → 建模设置”查看 Y 草稿；补齐 SOP、测试阶段、正式值域/类别、来源映射和输入方案后，才允许发布和训练。不要把这份脚本连接到测试环境或生产数据库。

首次运行会创建或重建已校验的 `_demo` 数据库、执行应用迁移，创建 Python 虚拟环境、安装 `requirements.lock.txt`、执行 Maven 编译、安装 Web 依赖并启动四个本地进程。日志和 PID 在 `.runtime/demo`。已有演示数据时不要再次使用 `-Reset`，改用下面不带 `-Reset` 的启动命令。服务探活：

```powershell
.\scripts\demo\verify-demo.ps1
```

地址：

- Web：<http://127.0.0.1:5173/login>
- API：<http://127.0.0.1:8080/actuator/health>
- Python：<http://127.0.0.1:8090/internal/v2/health/ready>

重置只允许用于演示库：

```powershell
.\scripts\demo\bootstrap-demo.ps1 -EnvFile .env.demo -Reset
```

停止脚本启动的服务：

```powershell
.\scripts\demo\stop-demo.ps1
```

## 生产/测试 Docker 部署

本机客户演示不启动 Docker；生产和测试环境使用仓库中的 `compose.yaml` 运行基础设施和 AI V2 容器。部署机先准备 Docker Engine 与 Compose v2，并在环境文件中填写非演示凭据，再校验配置：

```powershell
docker compose config
docker compose up -d postgres minio jsd-aird-ai
docker compose ps
```

AI 容器只暴露 `/internal/v2`，健康检查为 `/internal/v2/health/live` 和 `/internal/v2/health/ready`。API、Worker 和 Web 按生产编排平台的镜像与配置启动，不能把本地 `.runtime/demo/storage` 或合成模型目录挂载到生产。停止或升级前遵循环境的备份、灰度和回滚流程，不使用演示脚本的 `-Reset`。

服务已启动且客户模板已经在模板中心发布后，生成演示模型并批量导入两类客户布局：

```powershell
.\scripts\demo\bootstrap-demo.ps1 -EnvFile .env.demo -SeedModels -ImportClientTemplates
```

`-ImportClientTemplates` 会先动态读取当前组织中“已发布且允许生成实验草稿”的模板和实验分类，不使用固定 UUID。没有发布模板时会明确停止，不会把普通模板误当成实验模板。

若要手动启动，顺序为 Python AI、API、Worker、Web：

```powershell
.\scripts\dev-ai.ps1
.\scripts\dev-api.ps1
.\scripts\dev-worker.ps1
.\scripts\dev-web.ps1
```

Windows 上请使用 `npm.cmd`，不要直接把 `npm` 交给文件关联程序。

## 模拟数据

模拟数据由客户“应用测试报告”和“综合测试报告”的原生布局生成，不另造一套简单实验表。数据中心和实验记录本分别导入，记录来源、Sheet、单元格和模板版本。

演示数据包含约400个逻辑样本，覆盖：

- 100%和98.8%实际配方总量。
- PET、PC、PMMA/PC、线棒涂布、刮涂、旋涂、UV能量、温湿度和固含。
- 60°光泽、铅笔硬度、附着力、钢丝绒耐磨、RCA耐磨、UV表干、卷曲和翘曲。
- 不同Y覆盖率、缺失X、重复测量、异常值和待审查材料。
- 连续、序数、二分类和明确标记的合成多分类Y。
- 一个不使用配方组成的模型，用于演示无配方目标预测。

系统不会把演示模型标记为生产可用，也不会将模拟数据写入测试环境或生产库。

## 客户演示顺序

1. 在数据中心上传应用测试报告模板，选择已发布客户模板，查看原始工作表、字段位置和映射确认。
2. 在实验记录本上传综合测试报告模板，按同一字段映射工作台确认，进入实验草稿并完成审核。
3. 打开数据来源，验证数据中心和实验本来源互相展示，但所有权和业务数据没有复制。
4. 进入模型中心的建模设置，查看Y、X、数据来源、输入方案、覆盖率和资格漏斗。
5. 查看模型列表和训练任务，展示候选、演示启用、暂停和回退。
6. 打开性能预测，选择多个Y，填写配方和温湿度、UV能量等条件，演示全成功、部分阻断和无配方目标。
7. 打开配方预测，填写连续目标区间、序数等级或分类概率要求，展示最多4个候选和无解原因。
8. 打开实验优化，选择已完成实验、数据中心样本或配方候选作为基线，查看对照、保守、平衡和探索方案。
9. 选择候选创建实验草稿，确认客户模板原生布局和Univer工作台。
10. 在实验本补录实测结果、提交审核并完成实验。
11. 回到优化页，查看R03事实投影、逐Y预测、实测值和误差反馈。

演示时可以使用“60°光泽、铅笔硬度、UV表干、附着力、500g钢丝绒耐磨次数”作为目标；页面显示的是正常业务名称，合成性质在高级证据中标识。

## 测试命令

Python：

```powershell
Push-Location jsd-aird-ai
.\.venv\Scripts\python.exe -m pytest
Pop-Location
```

Web：

```powershell
Push-Location jsd-aird-web
npm.cmd run typecheck
npm.cmd run test -- --run
$env:NODE_OPTIONS='--max-old-space-size=4096'
npm.cmd run build
Pop-Location
```

Java定向验证：

```powershell
Push-Location jsd-aird-api
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd "-Dtest=com.jsd.aird.ai.rnd.**" test
Pop-Location
```

全量Java验证仍会报告既有的非本轮问题：部分模块边界ArchUnit违规，以及缺失的Word golden fixture。它们必须在报告中如实记录，不能写成通过，也不能与AI演示链路混为一谈。

## 常见问题

- CSRF 500/403：确认浏览器、Web和API都使用 `127.0.0.1:5173`，不要混用5173和5174；刷新登录页重新获取CSRF。
- CORS：检查 `.env.demo` 的 `JSD_AIRD_ALLOWED_ORIGINS` 同时包含实际Web地址。
- 找不到Java或psql：在 `.env.demo` 设置 `JSD_AIRD_JAVA_HOME` 和 `JSD_AIRD_PSQL_PATH`。
- Python无法读取模型：确认 `JSD_AIRD_AI_ALLOW_FILE_URLS=true`，并且不要移动 `.runtime/demo`。
- MinIO不可用：本地演示保持 `JSD_AIRD_STORAGE_PROVIDER=local`；生产环境不能把本地目录当成MinIO替代品。
- 上传失败：先检查本地对象存储目录和API日志，再检查模板是否已发布为可生成实验草稿的模板。

## 安全边界

不要提交 `.env`、数据库密码、真实客户文件、MinIO密钥、模型制品或演示库备份。演示数据只用于隔离环境，不能作为生产模型门槛、SOP、类别定义、误差阈值或适用域依据。
