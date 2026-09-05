# jsd-aird

杰事达研发数字化与 AI 平台基础工程。

本仓库采用“单仓库、前后端分离、后端模块化单体”：

- `jsd-aird-web`：React 管理端基础外壳。
- `jsd-aird-api`：Java/Spring Boot 模块化单体。
- `jsd-aird-ai`：T07-PRE 独立 Python 配方模型计算服务，已完成A.2～A.5算法门禁、适用域、实验优化与分类目标Pipeline。
- `compose.yaml`：提供 PostgreSQL 18/pgvector、MinIO、Worker 与 AI 计算服务的容器编排。

T07-A 仅新增算法、适用域、不可变制品和跨语言契约，不接入 T03–T06 尚未合并的客户接口和数据库结构。

## 环境要求

- Java 21
- Python 3.12（仅在本机运行 `jsd-aird-ai` 时需要）
- Node.js 20.19 或更高的 Node 20 版本
- npm 10+
- Windows：本机 PostgreSQL 16、17 或 18，以及匹配版本的 pgvector 扩展（推荐 PostgreSQL 18）
- Linux/CI：Docker Engine 与 Docker Compose v2（可选，不是 Windows 本地开发前置条件）

项目包含 only-script 模式的 Maven Wrapper，不要求预先安装全局 Maven。首次执行时会从 Maven Central 下载并缓存 Maven 3.9.11。

## 快速开始

复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

初始化 Windows 本机数据库。脚本会要求输入 PostgreSQL 管理员账号与密码；管理员密码只用于当前进程，不会写入文件：

```powershell
.\scripts\db-init.ps1
```

检查本机 PostgreSQL、pgvector 与业务账号是否就绪：

```powershell
.\scripts\db-up.ps1
```

启动后端（Flyway 会自动创建 pgvector 扩展与业务 Schema）：

```powershell
.\scripts\dev-api.ps1
```

在另一个 PowerShell 窗口启动前端：

```powershell
.\scripts\dev-web.ps1
```

访问地址：

- 前端：http://localhost:5173
- 后端健康检查：http://localhost:8080/actuator/health
- Swagger UI：http://localhost:8080/swagger-ui.html
- AI 服务探活：http://localhost:8090/internal/v1/health/ready

## 校验

```powershell
.\scripts\verify.ps1
```

Linux/macOS 可使用同名 `.sh` 脚本。

## Windows 本机 PostgreSQL

Windows 上 PostgreSQL 服务由操作系统管理，本工程不会启动、停止、删除或重建该服务。开发机可使用 PostgreSQL 16、17 或 18；CI/Compose 固定使用 PostgreSQL 18。无论使用哪个主版本，都必须安装与其匹配的 pgvector 扩展。

首次初始化后，后端默认连接如下数据源：

```text
jdbc:postgresql://localhost:5432/jsd_aird
用户名：jsd_aird
密码：jsd_aird_dev
```

仅限本机开发时使用默认账号。若需要修改连接信息，请修改根目录 `.env` 中的 `JSD_AIRD_DATASOURCE_*` 变量，再运行 `dev-api.ps1`。如果 PostgreSQL 的 `bin` 目录未加入 PATH，可在 `.env` 设置 `JSD_AIRD_PSQL_PATH` 指向 `psql.exe`；Java 21 未加入 PATH 时，可设置 `JSD_AIRD_JAVA_HOME` 指向 JDK 根目录。

Windows 开发不需要运行 `db-down.ps1`；该命令只输出说明并且不会删除数据。Docker Compose 是 Linux/CI 的可选工具，固定使用 PostgreSQL 18 基线。

## IntelliJ IDEA

1. 打开仓库根目录，并将 `jsd-aird-api/pom.xml` 添加为 Maven 项目。
2. 在 `Project Structure` 中将 Project SDK 设置为 Java 21。
3. 在 `JsdAirdApplication` 运行配置中设置 Active profiles 为 `local`。
4. 如未通过脚本启动，请在运行配置的 Environment variables 中填入 `.env` 对应的 `JSD_AIRD_DATASOURCE_*` 变量。
5. 先运行 `db-init.ps1` 与 `db-up.ps1`，再启动 `JsdAirdApplication`；后端健康检查地址为 `http://localhost:8080/actuator/health`。

## 初始化验证状态

生成日期：2026-07-30。

- 前端TypeScript检查、ESLint、2个Vitest测试和Vite生产构建已通过。
- `npm audit`没有高危或严重漏洞；React Router上游仍报告2个与重定向/SSR有关的中危公告。当前脚手架不接受外部跳转且不使用SSR，升级到无公告的兼容版本后应及时更新。
- 初始化工程时缺少 Java 21 和 Docker，因此后端 Maven 测试、Spring Modulith/ArchUnit 运行校验、Flyway 集成测试和 Compose 数据库验证尚未执行；后续应在 PostgreSQL 16、17、18 本机环境及 PostgreSQL 18 Compose 环境分别验证。
- 后端POM/XML、Java包路径、显式模块声明、Flyway Schema集合及前端JSON/YAML已完成静态校验。

## 模块约定

Spring Modulith 使用显式注解检测。业务模块只有在根包的 `package-info.java` 上声明 `@ApplicationModule` 后才会被识别。

模块内部层次不是由 Spring Modulith 强制，使用 ArchUnit 校验。详细规则见：

- `docs/architecture/module-boundaries.md`
- `docs/architecture/layering-rules.md`
- `docs/architecture/module-template.md`

## 当前边界

- T07-A Python 服务不能访问 PostgreSQL，也不对浏览器暴露客户接口。
- T07 只冻结 `formula-model.v1` 传输契约；正式持久化、异步任务和 T06 降级集成属于 T07-B。
- Python 返回的候选必须在 T07-B 由 Java 规则引擎终审，不能自动发布配方或修改正式实验。
