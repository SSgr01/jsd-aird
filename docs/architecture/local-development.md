# 本地开发环境

## 运行拓扑

Windows 本地开发使用系统安装的 PostgreSQL 与 pgvector；前端和后端直接运行在宿主机。Docker Compose 仅作为 Linux/CI 的可选 PostgreSQL 18 基线：

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
5. 执行 `scripts/dev-api.ps1` 启动后端，Flyway 自动创建业务 Schema。
6. 执行 `scripts/dev-web.ps1` 启动前端。

前端通过 Vite 代理访问 `/api` 和 `/actuator`，本地开发不需要额外配置跨域。

## 数据清理

Windows 的 `db-down.ps1` 不会停止 PostgreSQL 服务，也不会删除数据库或数据。数据库清理属于破坏性操作，应由开发者通过 pgAdmin、psql 或企业运维流程显式执行。

Linux/CI 如需使用容器基线，可运行 `docker compose up -d --wait`。该路径固定使用 PostgreSQL 18；如执行 `docker compose down --volumes` 会删除容器数据库数据，不应写入普通启动脚本。
