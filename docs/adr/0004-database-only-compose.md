# ADR-0004：本地Compose仅提供数据库

## 状态

已接受。

## 决策

本地Docker Compose只启动PostgreSQL/pgvector。前端和后端运行在宿主机。

## 暂不包含

Redis、MinIO、Nginx、监控、Worker、Python服务及任何消息中间件。
