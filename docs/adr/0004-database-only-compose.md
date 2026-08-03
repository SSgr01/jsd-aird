# ADR-0004：本地 Compose 提供 PostgreSQL 与 MinIO

## 状态

已修订并接受。

## 决策

本地 Docker Compose 启动 PostgreSQL/pgvector 与 MinIO。前端和后端运行在宿主机。

模板中心异步任务使用 PostgreSQL `ops.async_job`，事务后事件使用 PostgreSQL
`ops.outbox_event`。当前不引入 Redis、RabbitMQ、Kafka 或 Temporal。

## 理由

- JSONB、任务队列、Outbox 与业务写入共享同一数据库事务边界。
- MinIO 保存 Office 原件、Univer 原生快照与发布/提交导出件；PostgreSQL 只保存状态、哈希和引用。
- 当前没有多人实时协作或高吞吐流式消费场景。Redis 不提供不可替代能力，却会增加一致性和运维边界。

## 暂不包含

Redis、Nginx、监控、Python 服务及任何独立消息中间件。
