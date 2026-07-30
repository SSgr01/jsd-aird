# 本地开发环境

## 运行拓扑

本地默认只用 Docker Compose 启动 PostgreSQL/pgvector。前端和后端直接运行在宿主机：

| 组件       | 地址                      | 说明            |
| ---------- | ------------------------- | --------------- |
| Web        | `http://localhost:5173`   | Vite 开发服务器 |
| API        | `http://localhost:8080`   | Spring Boot     |
| PostgreSQL | `localhost:5432/jsd_aird` | pgvector 镜像   |

## 启动顺序

1. 将 `.env.example` 复制为 `.env`，按需修改开发密码。
2. 执行 `scripts/db-up` 启动数据库。
3. 执行 `scripts/dev-api` 启动后端，Flyway 自动创建扩展和 Schema。
4. 执行 `scripts/dev-web` 启动前端。

前端通过 Vite 代理访问 `/api` 和 `/actuator`，本地开发不需要额外配置跨域。

## 数据清理

`db-down` 默认只停止容器，不删除数据卷。需要清空本地数据时由开发者明确执行：

```bash
docker compose down --volumes
```

该命令会删除本地数据库数据，不应写入普通启动脚本。
