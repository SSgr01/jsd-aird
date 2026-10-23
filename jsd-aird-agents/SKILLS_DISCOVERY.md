# 开发前 Skills 发现记录

日期：2026-10-22；以下记录来自实际文件系统读取，不把目录存在等同于依赖或工具已配置。

## 项目目录

开发前检查仓库及祖先目录的 AGENTS.md，并检查仓库 .agents/skills、.codex/skills、.claude/skills 等目录。未发现适用于本模块的仓库级 SKILL.md 或额外 AGENTS.md。
本轮新增的三个业务技能在 src/jsd_aird_agents/app/verticals/project/skills/，属于服务内容，不会自动导入个人技能目录。

## 用户级技能文件

| 技能名称 | 实际路径 | 访问结果 |
|---|---|---|
| `imagegen` | `C:\Users\Ssgr\.codex\skills\.system\imagegen\SKILL.md` | 可读取 |
| `openai-docs` | `C:\Users\Ssgr\.codex\skills\.system\openai-docs\SKILL.md` | 可读取 |
| `review-agent` | `C:\Users\Ssgr\.codex\skills\.system\review-agent\SKILL.md` | 可读取 |
| `skill-creator` | `C:\Users\Ssgr\.codex\skills\.system\skill-creator\SKILL.md` | 可读取 |
| `skill-installer` | `C:\Users\Ssgr\.codex\skills\.system\skill-installer\SKILL.md` | 可读取 |
| `banner-design` | `C:\Users\Ssgr\.codex\skills\banner-design\SKILL.md` | 可读取 |
| `brand` | `C:\Users\Ssgr\.codex\skills\brand\SKILL.md` | 可读取 |
| `design` | `C:\Users\Ssgr\.codex\skills\design\SKILL.md` | 可读取 |
| `design-system` | `C:\Users\Ssgr\.codex\skills\design-system\SKILL.md` | 可读取 |
| `gsap-core` | `C:\Users\Ssgr\.codex\skills\gsap-core\SKILL.md` | 可读取 |
| `gsap-frameworks` | `C:\Users\Ssgr\.codex\skills\gsap-frameworks\SKILL.md` | 可读取 |
| `gsap-performance` | `C:\Users\Ssgr\.codex\skills\gsap-performance\SKILL.md` | 可读取 |
| `gsap-plugins` | `C:\Users\Ssgr\.codex\skills\gsap-plugins\SKILL.md` | 可读取 |
| `gsap-react` | `C:\Users\Ssgr\.codex\skills\gsap-react\SKILL.md` | 可读取 |
| `gsap-scrolltrigger` | `C:\Users\Ssgr\.codex\skills\gsap-scrolltrigger\SKILL.md` | 可读取 |
| `gsap-timeline` | `C:\Users\Ssgr\.codex\skills\gsap-timeline\SKILL.md` | 可读取 |
| `gsap-utils` | `C:\Users\Ssgr\.codex\skills\gsap-utils\SKILL.md` | 可读取 |
| `hatch-pet` | `C:\Users\Ssgr\.codex\skills\hatch-pet\SKILL.md` | 可读取 |
| `ui-styling` | `C:\Users\Ssgr\.codex\skills\ui-styling\SKILL.md` | 可读取 |
| `ui-ux-pro-max` | `C:\Users\Ssgr\.codex\skills\ui-ux-pro-max\SKILL.md` | 可读取 |
| `design-taste-frontend` | `C:\Users\Ssgr\.agents\skills\design-taste-frontend\SKILL.md` | 可读取 |
| `find-skills` | `C:\Users\Ssgr\.agents\skills\find-skills\SKILL.md` | 可读取 |

合计发现 22 个技能入口。非本任务相关的技能只检查元数据与可读性，未加载全文或运行脚本。

## 本轮复用

- 完整阅读并应用 skill-creator：复用通用 SKILL.md、精确触发描述、渐进加载、按需 references/assets 及验证流程，未创建另一套技能格式。
- 实际运行其 scripts/quick_validate.py 验证三个业务技能，均返回 Skill is valid!。该开发验证器是已读取的开发技能工具，不是 Agent 服务的技能脚本执行能力。
- 完整阅读 find-skills：它面向外部技能市场搜索与安装；本轮要求为本地发现与服务能力实现，未进行市场安装。
- 没有发现可直接复用的杰事达项目延期、任务审批或项目知识研究业务技能，因此只为现有工具编写三项工作流。设计、动画、宠物等技能与本轮后端任务无关。

## 可访问性边界

本记录检查本机用户目录，不代表已验证远程、其他主机、未挂载目录或技能所需外部服务。不能访问的远程技能标记为未验证，不声称已安装。
运行时仅扫描服务端配置的 skill_roots；个人 Codex 技能、插件缓存和开发凭证不因本记录而对项目助手开放。
标准依据：[Agent Skills Specification](https://agentskills.io/specification)。支持标准元数据和目录，allowed-tools 只作工具上限，不视为企业权限或审批。

## 当前会话列出的插件技能路径核对

只核对明确列出的文件入口，不把插件缓存存在视为外部账号、工具依赖或脚本执行权限。

| 入口 | 实际文件路径 | 结果 |
|---|---|---|
| `Adobe adobe-batch-edit-photos` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-batch-edit-photos\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-create-mockups` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-create-mockups\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-create-social-variations` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-create-social-variations\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-design-from-template` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-design-from-template\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-edit-quick-cut` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-edit-quick-cut\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-fonts` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-fonts\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `Adobe adobe-retouch-portraits` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\app-69312da8e4dc81919370cb86fd172b6c\10.0.0\skills\adobe-retouch-portraits\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `computer-use` | `C:\Users\Ssgr\.codex\plugins\cache\openai-bundled\computer-use\26.1013.40934\skills\computer-use\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `documents` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\documents\26.1020.11127\skills\documents\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `pdf` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\pdf\26.1020.11127\skills\pdf\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `presentations` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\presentations\26.1020.11127\skills\presentations\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `plugin-management` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\plugin-management\0.1.0\skills\plugin-management\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `sites` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\sites\1.1.1\skills\sites\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `sites-remix` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\sites\1.1.1\skills\sites-remix\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `spreadsheets` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\spreadsheets\26.1020.11127\skills\spreadsheets\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `excel-live-control` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\spreadsheets\26.1020.11127\skills\excel-live-control\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `template-creator` | `C:\Users\Ssgr\.codex\plugins\cache\openai-primary-runtime\template-creator\26.1020.11127\skills\template-creator\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `visualize` | `C:\Users\Ssgr\.codex\plugins\cache\openai-bundled\visualize\1.0.50\skills\visualize\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `create-pet` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\work-pets\0.1.6\skills\create-pet\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `pets` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\work-pets\0.1.6\skills\pets\SKILL.md` | 可读取，未因本任务加载全文或执行 |
| `update-pet` | `C:\Users\Ssgr\.codex\plugins\cache\openai-curated-remote\work-pets\0.1.6\skills\update-pet\SKILL.md` | 可读取，未因本任务加载全文或执行 |

本次平台扩展另外在 `app/verticals/quality/skills/` 增加一个 Mock 质量技能，沿用同一标准与治理路径；没有安装新的个人开发技能。

## 2026-10-22 首批主动巡检开发补充

本轮继续复用已发现的 `C:/Users/Ssgr/.codex/skills/.system/skill-creator/SKILL.md`，阅读其渐进加载与验证要求。
新增的 `project-proactive-monitoring` 是项目助手的标准 Skill，不是安装到 Codex 用户目录的开发 Skill。
本模块现有五个内置 Skill（project 四个、quality 一个）。新 Skill 已通过 quick_validate，
资源懒加载、工具上限与版本启停继续由既有 Registry/Loader/Harness 实现。
