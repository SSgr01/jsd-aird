# 工作区与续跑

系统任务只使用 source 工具已经准备好的当前任务目录，传 `--root .`；文件夹模式先读 [server-folder.md](server-folder.md)。服务器固定的 `/srv/jsd-aird/experiment-history/` 与沙箱内中文工作目录属于两层，不从沙箱直接访问服务器目录，也不把服务器目录改名成下面的中文布局。

仅本地模式从用户指定目录、当前项目祖先或项目级 skill 位置识别根目录，不根据应用安装目录或旧电脑盘符猜测。

```text
标准模板/test.xlsx、synthesis.xlsx
原始实验/测试实验/、合成实验/
转换结果/测试实验/、合成实验/
模拟数据示例/                         可选，仅解释布局
.dsh/skills/jsd-experiment-converter/  项目安装
.dsh/cache/jsd-experiment-converter/   运行状态和计划，不进入交付 Excel
```

上述任务内脚本布局用精确中文目录名，不能静默改用 `04-转换结果`；用户显式选择的不同布局可经确认后适配。缺少一种输入分类目录时，清点记录 missing_directory，不阻止另一分类。缺输出目录由执行器创建。

`workflow.py inspect --root ROOT` 清点并核对指纹；它不会扫描 `_work/`、`转换结果/` 或交付包的副本作为源。缓存以源 SHA256 为目录，保存按表提取的 JSON、verification.json、status.json。计划还绑定模板指纹；执行器改变会让已完成状态过期。映射内容改动后也不能直接覆盖已有结果。

项目里的 skill 是当前执行版本。`交付包/JSD-Experiment/.dsh/skills/…` 是分发副本，可以在发布优化时同步；正常转换不必搜索所有同名技能。跨电脑复制完整项目目录即可；缓存失效时重新核对，不假设旧绝对路径仍有效。
