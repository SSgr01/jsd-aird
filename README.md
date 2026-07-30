# jsd-aird

杰事达研发数字化与 AI 平台基础工程。

本仓库采用“单仓库、前后端分离、后端模块化单体”：

- `jsd-aird-web`：React 管理端基础外壳。
- `jsd-aird-api`：Java/Spring Boot 模块化单体。
- `compose.yaml`：仅提供本地 PostgreSQL/pgvector。

当前版本是基础设施可运行脚手架，不包含登录、权限、模板、实验、生产、库存、知识库、AI、文件存储或异步任务等业务功能。

## 环境要求

- Java 21
- Node.js 20.19 或更高的 Node 20 版本
- npm 10+
- Docker Engine 与 Docker Compose v2

项目包含 only-script 模式的 Maven Wrapper，不要求预先安装全局 Maven。首次执行时会从 Maven Central 下载并缓存 Maven 3.9.9。

## 快速开始

复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

启动数据库：

```powershell
.\scripts\db-up.ps1
```

启动后端：

```powershell
.\scripts\dev-api.ps1
```

启动前端：

```powershell
.\scripts\dev-web.ps1
```

访问地址：

- 前端：http://localhost:5173
- 后端健康检查：http://localhost:8080/actuator/health
- Swagger UI：http://localhost:8080/swagger-ui.html

## 校验

```powershell
.\scripts\verify.ps1
```

Linux/macOS 可使用同名 `.sh` 脚本。

## 初始化验证状态

生成日期：2026-07-30。

- 前端TypeScript检查、ESLint、2个Vitest测试和Vite生产构建已通过。
- `npm audit`没有高危或严重漏洞；React Router上游仍报告2个与重定向/SSR有关的中危公告。当前脚手架不接受外部跳转且不使用SSR，升级到无公告的兼容版本后应及时更新。
- 当前生成环境缺少Java 21和Docker，因此后端Maven测试、Spring Modulith/ArchUnit运行校验、Flyway集成测试和Compose数据库验证尚未执行。
- 后端POM/XML、Java包路径、显式模块声明、Flyway Schema集合及前端JSON/YAML已完成静态校验。

## 模块约定

Spring Modulith 使用显式注解检测。业务模块只有在根包的 `package-info.java` 上声明 `@ApplicationModule` 后才会被识别。

模块内部层次不是由 Spring Modulith 强制，使用 ArchUnit 校验。详细规则见：

- `docs/architecture/module-boundaries.md`
- `docs/architecture/layering-rules.md`
- `docs/architecture/module-template.md`

## 当前边界

- PostgreSQL/Flyway 仅创建 `vector` 扩展和 11 个 Schema。
- `export` 是 Java 模块，但没有独立数据库 Schema。
- 不提供自定义业务 HTTP API，系统探活使用 Spring Boot Actuator。
- 不创建 Worker、Python 服务、RabbitMQ、Redis、MinIO、Kubernetes 或 Helm。
