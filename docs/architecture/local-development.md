# 本地开发环境

## 运行拓扑

Windows 本地开发使用系统安装的 PostgreSQL 与 pgvector；前端、后端和 Python AI 服务直接运行在宿主机。Docker Compose 不属于本地演示启动路径：

| 组件       | 地址                      | 说明            |
| ---------- | ------------------------- | --------------- |
| Web        | `http://localhost:5173`   | Vite 开发服务器 |
| API        | `http://localhost:8080`   | Spring Boot     |
| PostgreSQL | `localhost:5432/jsd_aird` | Windows 本机 PostgreSQL 16–18 + pgvector；Linux/CI 使用 PG18 容器 |

## 启动顺序

1. 安装 PostgreSQL 16、17 或 18，并安装匹配的 pgvector 扩展；推荐 PostgreSQL 18。
2. 将 `.env.example` 复制为 `.env`，按需修改本机开发账号密码。
3. 执行 `scripts/db-init.ps1`，创建本地开发账号、数据库并启用 pgvector。
4. 执行 `scripts/db-up.ps1`，检查业务账号、数据库与 pgvector 是否就绪。
5. 执行 `scripts/dev-ai.ps1` 启动 Python V2 服务。
6. 执行 `scripts/dev-api.ps1` 启动后端，Flyway 自动创建业务 Schema。
7. 另一个窗口执行 `scripts/dev-worker.ps1` 启动 Worker。
8. 执行 `scripts/dev-web.ps1` 启动前端。

前端通过 Vite 代理访问 `/api` 和 `/actuator`，本地开发不需要额外配置跨域。

## 数据清理

Windows 的 `db-down.ps1` 不会停止 PostgreSQL 服务，也不会删除数据库或数据。数据库清理属于破坏性操作，应由开发者通过 pgAdmin、psql 或企业运维流程显式执行。

Linux/CI 的容器回归不属于本地客户演示；本地演示必须使用本机 PostgreSQL、Python、Java 和 Node 进程。
