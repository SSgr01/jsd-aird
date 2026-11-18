# Linux 服务器文件夹模式

适用于系统绑定的 `folder_conversion` 任务。服务器路径由部署配置确定，模型只处理已获授权的当前文件。

## 固定服务器目录与任务工作区

| 用途 | 服务器目录 |
| --- | --- |
| 历史原件 | `/srv/jsd-aird/experiment-history/originals` |
| 标准模板 | `/srv/jsd-aird/experiment-history/templates` |
| 转换结果 | `/srv/jsd-aird/experiment-history/results` |

后台默认配置文件为 `/etc/jsd-aird/experiment-conversion-roots.json`；固定资料别名为“历史实验资料”。组织、账号和目录权限由实施人员配置，不要求客户选择路径，也不由模型修改。

这些服务器目录不挂载到模型沙箱。当前任务使用相对目录：`标准模板/`、`原始实验/`、`转换结果/`、`.dsh/cache/jsd-experiment-converter/`。脚本在 `.conversion-tools/jsd-experiment-converter/scripts/`，参考文件在同目录的 `references/`，执行命令使用 `--root .`。不得从沙箱直接访问 `/srv/`、`/etc/` 或寻找其他客户资料。

## 发起和续跑批次

在当前或新建的任何获授权会话中，通过 `tool_search` 发现 `folder_conversion`：

- `action=list` 查看已获准资料别名和本人已有批次。不同会话共用目录进度，先找回已有记录，不要求用户记住批次 ID。目标明确直接处理，仅多个目录无法判断时询问。
- `action=start, alias=历史实验资料` 增量扫描，已有扫描中或运行中批次时接续。暂停批次用 continue 恢复；资源变化导致 FOLDER_ENGINE_CHANGED 时先 pause 旧批次，再 start 新批次，保留旧记录与结果。仅沿用服务端校验通过的已有结果（SKIPPED），不代表人工确认。源文件、模板、转换资源变化，或结果丢失/损坏时重新处理。
- 用 `action=status, batch_id=...` 查看分页进度，按 `next_offset` 继续；扫描中不代表文件缺失。
- 用户要求暂停或继续时调用 `action=pause` 或 `action=continue`；可提供目录别名，只有一个目录时可省略批次 ID。已结束批次继续时启动新一轮增量扫描。返回 selection_required 时先明确目录。暂停停止派发新文件，已经开始的文件可完成。
- 在当前对话汇报本次完成、沿用已有结果、排队和失败数量；直接通过原生聊天和工具结果反馈，不增加专用面板。

清点、并发、后台授权、独立会话和重启恢复由系统负责。不要自行遍历服务器、另建调度器、一次加载整批正文或再为所有文件启动其他代理。当前文件会话只处理系统绑定的一份文件，不另行启动批次。

## 每份文件的处理

1. 调用 `folder_conversion(action=source)`。成功回执包含 `folder_source=true`、当前文件路径、原件和工作副本校验值；标准模板和资源已准备好。不要改用 `experiment_conversion` 取原件。
2. 从 `source_path` 读取文件。原相对目录中已有明确测试/合成分类时作为依据；否则读文件内容判断。把工作副本复制到任务内的 `原始实验/测试实验/` 或 `原始实验/合成实验/`，保留原副本不改。无法确定类型时仅阻塞本文件，不猜类型。
3. 对当前任务执行 `workflow.py inspect --root .`，然后分表提取。每份新文件重新理解字段、条件、分组和模板，不套用其他文件映射；文件夹模式不提供 `recipes/`。同一绑定文件续跑可复用已有计划，但须核对当前源、模板、执行器及输出指纹。
4. 保存该文件自己的映射 JSON，用随包 `execute_plan.py` 执行并按 [qa.md](qa.md) 检查。保留未落位数据、源冲突、空白、零值和单位；不改脚本、不补造数据、不把数值校验当作语义已被人工确认。
5. 执行成功后调用：

   ```text
   folder_conversion(action=export,
     output_path=实际结果XLSX路径,
     plan_path=实际映射JSON路径,
     report_path=实际verification.json路径)
   ```

   三个路径都在当前任务目录内。结果必须与成功的真实沙箱执行回执匹配。源冲突稿可从 `_work/` 导出，保留 `review_required` 和冲突说明。校验报告保持脚本原始字节，不手改状态或哈希；出口处理旧报告的静态人工标记，不建立人工审核记录。
6. 仅凭 `saved=true` 的真实回执说明已保存。若文件损坏、加密、超限、缺少公式缓存、旧格式转换不保真或信息不足，用 `folder_conversion(action=fail, reason=具体原因)` 记录；其他文件由调度器继续。执行或保存结果未知时先查现有回执，不盲目重放。

禁止在此模式调用 `artifact_save`、`present`、`experiment_conversion(action=stage)` 或其他业务写入。不得确认入库、发布、登记核对人、设置人工审核状态；客户自行逐份检查结果文件。

## XLS / DOC 工作副本与来源

`source` 服务先对 XLS/DOC 执行严格规范化，再向任务提供 XLSX/DOCX 副本；不使用预览降级结果，不只改后缀。不要在模型沙箱重新启动 LibreOffice 或另装旧格式转换器。

计划的 `source`、源坐标和 `source_sha256` 绑定实际提取的工作副本。`original_sha256` 仍指向服务器原件，两者由后台独立保存；不能把原件校验值填到规范化副本的计划中。规范化副本有单独缓存和校验值，续跑复用，不反复转换。

## 服务器输出

出口按批次和原相对路径保存为：

```text
results/<批次ID>/<原相对目录>/<原文件名含扩展名>.converted/
  result.xlsx
  mapping.json
  verification.json
```

失败原因在批次 `.errors/`，旧格式固定工作副本在 `.dsh-state/`。原文件名的扩展名参与目录命名，避免同名 XLS/XLSX、DOC/DOCX 混淆。已有结果不覆盖；源、模板或执行资源变化时如实说明阻塞，不改哈希绕过。报告只说明转换结果、缺项、未落位和冲突，不宣称已人工核对或已全批次完成。
