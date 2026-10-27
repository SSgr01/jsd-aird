# 统一实验模板部署

先部署本次后端和前端代码，执行到 V83 数据库迁移，再用 UTF-8 执行同目录 `install.sql`。脚本包含 V82 的幂等建表与安装函数；V83 的 `data.import_workbook_draft` 由增量迁移创建，用于两个导入核对入口的完整工作簿草稿。

```powershell
psql -X -v ON_ERROR_STOP=1 -f deployment/unified-experiments/install.sql
```

连接信息通过 PostgreSQL 标准环境变量或部署环境提供。脚本为已有组织安装两套已发布的 V9 模板；不导入示例实验、不调用 AI、不需要上传 Excel、不依赖本机路径或对象存储。

初始化包保持 V1，包含修正后的合成表标题定位、中文显示路径和配方字段定义。V1 是模板版本号，V9 是导入契约版本号。原有实验记录及其保存快照不随模板安装改写。

| 固定编码 | 模板 |
| --- | --- |
| EXP_TEST_UNIFIED | 测试实验 |
| EXP_SYNTHESIS_UNIFIED | 合成实验 |

同一包可重复安装；相同编码首版内容不同会中止事务，必须使用公共模板修订发布流程。已有实验固定引用原版本。新建实验和选模板导入只提供这两套实验模板；其他业务模板、历史实验和历史模板版本继续保留。

新组织建立用户后调用：

```sql
SELECT tpl.install_unified_lab_templates('组织UUID'::uuid, '该组织管理员UUID'::uuid);
```

从实验另存为模板时，统一模板创建的是原模板的修订草稿，清除填写值并带入已确认的新增项目，按现有审核流程发布。

字段、实例、接口和验收细节见 [实施与验证记录](IMPLEMENTATION.md)。

模板编辑、实验填写、数据中心及实验导入核对、生产单共用结构迁移；实验中的三个专用扩展入口已由原生行列操作及既有“新增字段”取代。验证方式及覆盖范围见 [实施与验证记录](IMPLEMENTATION.md#验证命令与范围)。本地运行日志、页面截图和生成的验收草稿不作为部署依赖。

填写和导出手写空白表显示全量。结果预览及默认导出移除未测、不适用、空物料行和空列。系统中保留完整工作簿及原始输入。导出的隐藏定位页仅包含可见字段定义和坐标，不携带被移除的结果。

重新编译初始化 SQL（不修改四份原 Excel）：编译前停止使用构建目录的 API 和 worker，完成后重新启动并检查健康状态。

```powershell
cd jsd-aird-api
mvn "-Dtest=UnifiedLabSeedGeneratorTest" "-Dlab.seed.output=../deployment/unified-experiments/install.sql" test
```

SQL 安装验证使用独立临时 PostgreSQL 集群，不连接业务库：

```powershell
python deployment/unified-experiments/verify_sql.py --postgres-bin "D:/PostgreSQL/18/bin"
```
