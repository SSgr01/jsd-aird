# Hindsight 长期记忆服务

选型已确定为 Hindsight，自托管并复用现有 PostgreSQL 实例。记忆使用独立的 `jsd_aird_hindsight` 数据库和 `jsd_hindsight` 账号，不接管业务表，不改 Java Flyway。dsh 继续负责模型循环、会话和文件，Hindsight 提供派生记忆。

服务端镜像固定为 `0.8.6` 及 OCI 摘要，避免随 `latest` 更新。官方 coding-agents 插件版本与服务版本分别管理，不能按版本号相同推断接口完全一致。

## 配置与启动

需要 Docker Desktop Linux 容器或 Linux Docker、Compose 2.30+、Python 和 `psql`。现有 PG 要求 15+、已安装 pgvector 0.5.0+。脚本不新建 PostgreSQL 服务。

把本目录 `.env.example` 的模型配置写入本机私有环境文件。模型路由由部署者显式指定；Hindsight 不会自动继承 dsh Provider，也不自动把 Anthropic 地址改成 OpenAI 地址。企业网关支持 OpenAI 兼容协议时使用 `openai`，填写该协议的实际地址和模型 ID。中文向量模型首次启动会下载，模型缓存使用独立持久卷。

使用能建库、建角色和安装扩展的 PG 管理账号执行一次准备：

```powershell
# 管理员密码通过当前进程 PGADMIN_PASSWORD 注入，不写入代码或命令行。
python deployment/hindsight/manage.py prepare --env-file .env --admin-user postgres --pg-port 5432
python deployment/hindsight/manage.py up
python deployment/hindsight/manage.py status
```

Docker Desktop 中 PG 位于宿主机时，容器连接其内置的 `host.docker.internal`；Linux 或其他服务器通过 `--pg-host` 和 `--container-pg-host` 指定实际可达地址。PG 需允许容器网络访问这个专用库和账号，使用密码认证；不要扩大业务库的授权范围。脚本不会修改现有 PG 的监听地址或 `pg_hba.conf`。

重复准备会回读角色与数据库，不重置已有密码，不接管其他账号拥有的数据库。创建响应丢失后使用同一份 `.runtime/hindsight/credentials.json` 对账。独立数据库由 Hindsight 官方启动过程初始化自己的表结构；Java 的 `SPRING_FLYWAY_ENABLED=false` 保持不变。

API 为 `http://127.0.0.1:8888`，管理页面为 `http://127.0.0.1:9999`，只监听本机回环地址。管理页面面向运维，不嵌入客户工作台。生产部署将 API 放在内部网络并配置服务鉴权。

```powershell
python deployment/hindsight/manage.py logs
python deployment/hindsight/manage.py stop
```

`stop` 只停止记忆服务，不删除数据库或模型缓存。升级前备份专用数据库，显式更新镜像版本和摘要；服务自带的 schema 初始化与业务迁移分开管理。

默认连接池为 2–16，后台最多四个并发作业；部署保留稳定的 `HINDSIGHT_API_WORKER_ID=jsd-aird-memory`，让容器重建后能恢复本实例的未完成作业。多实例时为每个实例分配独立且稳定的 worker ID。容器停止留出 40 秒，使用官方 `/health` 检查 API 与数据库连接。

## 接入 dsh 的边界

个人 dsh 可按官方方式安装 `@vectorize-io/hindsight-coding-agents@0.8.6`，指定 `self-hosted` 和上述 API。企业工作台通过企业插件与 Python Control 调用官方 HTTP API，不安装全局 coding-agent 插件，不采集代码库。组织、用户绑定稳定私有 bank，授权版本单独校验，模型与执行目录不能改变归属。

仅部署成功不表示企业长期记忆已经启用。企业入口由 Control 的 `JSD_AIRD_HARNESS_MEMORY_ENABLED` 控制，示例默认关闭；隔离验收后启用并重启 Java、Control、网关及用户 Host。当前接入、开关及回执见 [个人记忆一期](../../jsd-aird-agent/docs/personal-memory.md)。本部署不导入已有业务数据或历史会话。

Compose 设置 `HINDSIGHT_API_LLM_TRACE_DELETE_WITH_DOCUMENT=true`，使遗忘时文档关联调用正文一并删除；不要只更新文件而不重建容器。升级后按 `/health` 和隔离往返核对，保持原镜像摘要和数据库。Control 在删除前取得实际用量，保留计数及操作身份，不保留已遗忘正文。

密钥、数据库凭据、日志和实际运行材料均在忽略目录 `.runtime/hindsight/`，不提交或推送。数据库备份由现有 PG 运维策略覆盖该专用库。

## 页面检查结果（2026-11-09）

已在内置浏览器实际完成记忆库选择、中文筛选、记忆与原始文档对应、文本写入、文档更正、文件导入、Recall、Reflect、实体图、语言及主题保存，以及容器重启后的页面召回。保留了三个独立偏好文档；这些内容不代表项目或实验的真实业务结果。

本次使用固定的 `0.8.6` 官方管理页面，桌面主要操作可用，但页面尚未全面通过：

- 记忆筛选无结果后，搜索框会被“暂无记忆”状态替换，需刷新页面才能清除筛选。
- 平板宽度 768 下顶部操作被裁切；手机宽度 390 下顶部按钮不可见，Recall 结果被评分栏挤成单字竖排，影响阅读。
- 中文文档列表将字符数标成 `B`，与详情中的 UTF-8 字节数不一致；原文内容完整。

以上是实际页面缺陷，未通过注入脚本、替换原生页面或修改官方镜像来掩盖。完整步骤、截图、实际模型用量及未覆盖事项保存在忽略目录 `.runtime/hindsight/ui-acceptance/report.md`。语言、主题和浏览器尺寸已恢复。

上述 2026-11-09 结果只覆盖运维管理页。2026-11-10 的客户工作台生命周期、原生设置、来源跳转、后台整理及更正遗忘结果另见 [一期验收记录](../../jsd-aird-agent/docs/personal-memory-acceptance.md)，不能混用两份验收范围。
