# 知识库批量导入修复与验收

本次代码修改尚未自动部署到生产服务器。服务器没有 SSH 连接，以下操作由运维执行。

## 修复范围

- 每轮领取任务前，把租约已过期且重试耗尽的 RUNNING 任务收尾为 FAILED，保留原错误，清空租约并递增代数，拒绝旧实例回写。不自动增加重试次数。
- 新增 JSD_AIRD_WORKER_MAX_CONCURRENT_JOBS，生产默认 4，与全局 heavy 容量共同限制实际 Worker 执行数；claims-per-poll 不是执行并发。
- 正文分块每批最多 128 行、检索词条每批最多 512 行多行 INSERT，保留发布事务的原子性。词条同批重复键采用最后一个值。
- 同版本同任务键在同一 MinerU 批次中合并提交，为每个消费者复制独立结果文件。
- 续跑遇到明确的 upload_expired 结果时重新创建上传；正在运行、已完成的任务继续复用，不因批次中其他文件失败而提前失败。

## 部署

1. 停止导入脚本，保留原 manifest。停止 API、Worker，保留 PostgreSQL、MinIO 和 MinerU 运行及其检查点。
2. 把更新后的源代码和导入脚本同步到 /opt/source/jsd-aird。
3. 在 jsd-aird-api 下使用 JDK21、Maven3.9.9 构建，成功后备份运行 JAR，再 install 替换。API 与 Worker 共用同一个 JAR。
4. Worker 实际并发生产默认 4，无需覆盖现有环境配置。若需先单任务验收，可配置 JSD_AIRD_WORKER_MAX_CONCURRENT_JOBS=1；修改 Supervisor environment 后需 reread/update 生效，必须保留原有数据库及服务连接配置。
5. 启动 API、Worker，确认 worker_online。旧的 18 个耗尽任务会被标为 FAILED，并不会自动重新执行。
6. 先选择一个已知远程状态的失败任务恢复，观察成功后再扩大。导入脚本使用原 manifest、--max-in-flight 8、--retry-failed，不删除检查点。

## 单任务恢复模板

先停止 Worker（以及其他具有 worker profile 的实例），确认没有对应任务的实际执行。保留任务行与相关 MinerU 检查点的备份。下面 SQL 必须传入已核对的一个任务 ID，不是批量重置脚本。

```bash
cd /tmp
runuser -u postgres -- /usr/pgsql-15/bin/psql \
  -X -v ON_ERROR_STOP=1 -v job_id='已核对的任务UUID' -d jsd_ai -P pager=off <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '10s';
UPDATE ops.async_job j
SET status='READY', current_stage='PREPARING', max_attempts=attempt_count+1, next_attempt_at=now(),
    lease_owner=NULL, lease_token=NULL, lease_expires_at=NULL,
    lease_generation=lease_generation+1, finished_at=NULL, updated_at=now()
WHERE j.id=:'job_id'::uuid
  AND j.organization_id='00000000-0000-0000-0000-000000000001'::uuid
  AND j.job_type IN ('KB_BUILD_KNOWLEDGE_VECTOR','KB_INGEST_DOCUMENT')
  AND j.attempt_count=5 AND j.max_attempts=5
  AND (j.status='FAILED' OR (j.status='RUNNING' AND j.lease_expires_at < now()-interval '5 minutes'))
  AND NOT EXISTS (
      SELECT 1 FROM kb.mineru_task_checkpoint c
      WHERE c.organization_id=j.organization_id
        AND c.document_version_id::text=j.payload_jsonb->>'versionId'
        AND c.remote_state IN ('submitting','submission-unknown')
  )
RETURNING j.id,j.job_type,j.status,j.attempt_count,j.max_attempts;
COMMIT;
SQL
```

UPDATE 0 时停止并核对，不能去掉保护条件。该模板只适用于此次已核对的 5/5 耗尽任务，不适用于所有失败文件。对已明确完成的任务不要重复恢复。

## 验收与限制

- 先恢复一个任务，确认 SUCCEEDED 后再恢复下一批；不允许再出现租约过期且耗尽次数后长期 RUNNING。
- 大书发布索引应持续推进，观察事务超时和 PostgreSQL 心跳异常，不以一次低负载 iostat 代替验收。
- 服务重启后复用已保存的 MinerU 任务 ID，已完成文档不重复提交。
- PDFium 格式错误、图片解码失败和无可检索内容是独立文件失败；这些文件不能凭本次调度修复保证解析成功。
- 创建响应丢失且无远程任务 ID 的 submitting/submission-unknown 仍需核对，不能自动盲目重提。本次不删除这类检查点。
- 秒级磁盘停顿、RAID 降级和 gateway FATAL 不会被这次代码修改修好，需要各自处理。
- 完成小批量验收后再逐步扩大至 2000/25000 份；并发调整以吞吐与超时、租约指标为依据。
