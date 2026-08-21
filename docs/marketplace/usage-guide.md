# Usage Guide / 使用说明

> CodeBecause turns a natural-language business topic into an **evidence-backed**, source-verifiable report inside your IDE, then lets you refine it interactively — without adding any files to the analyzed repository.
>
> CodeBecause 把一句自然语言的业务主题，变成 IDE 内**有源码证据支撑**、可回溯的报告，并支持交互式持续打磨，且**不会往被分析的仓库里添加任何文件**。

---

## 1. Analyze a Business Topic / 分析业务主题

1. Open the **CodeBecause** tool window (right sidebar, or `Tools → Open CodeBecause`).
   打开 **CodeBecause** 工具窗口（右侧边栏，或 `Tools → Open CodeBecause`）。
2. Click **新建分析 (New Analysis)**. The working directory defaults to the current repository; browse to another folder if needed.
   点击 **新建分析**。分析目录默认取当前仓库，如需可浏览选择其他目录。
3. Enter a topic in natural language, e.g. `分析聊天逻辑` / `analyze the checkout flow`.
   用自然语言输入主题，例如 `分析聊天逻辑` / `analyze the checkout flow`。
4. Run the analysis. CodeBecause locks `HEAD` and runs **one** read-only CLI session that discovers entries, traces the causal path, builds a typed end-to-end flow graph, and self-checks it before returning.
   运行分析。CodeBecause 锁定 `HEAD`，只跑**一次**只读 CLI 会话：定位入口、追踪因果路径、构建端到端流程图，并在返回前自检。

The generated report covers business purpose, actors, vocabulary, business domains, domain relationships, complete flows, data reads/writes, failure exits, observable outcomes, and auditable source locations.
生成的报告涵盖：业务目的、参与者、术语、业务域、域关系、完整流程、数据读写、失败出口、可观测结果，以及可审计的源码位置。

---

## 2. Refine the Report / 打磨报告

- Type a follow-up prompt at the bottom of a report to **expand, correct, or reorganize** it.
  在报告底部输入后续提示，对报告**扩展、修正或重组**。
- Each follow-up is a separate one-session action that **preserves verified context** and appends a revision record.
  每次追问都是独立的单会话操作，**保留已验证的上下文**并追加一条修订记录。
- Unresolved links stay as explicit **unknowns** rather than triggering hidden extra model calls.
  未解析的关联会明确标记为**未知项**，而不是偷偷发起额外的模型调用。

---

## 3. Function-Level Business Flow (GoLand) / 函数级业务流程（GoLand）

1. Place the caret inside a Go function or method.
   将光标放在某个 Go 函数或方法内。
2. Right-click → **Analyze Function Business Flow**.
   右键 → **Analyze Function Business Flow**。
3. CodeBecause recursively follows business-relevant calls until a newcomer can understand the trigger, branches, data/state changes, external effects, errors, and outcome.
   CodeBecause 递归跟踪与业务相关的调用，直到新人能理解触发、分支、数据/状态变化、外部影响、错误与结果。

**Gutter icon / 侧边栏图标**：函数旁的图标可打开该函数最新的已保存报告，无历史时则发起首次分析。
**Expand a call / 展开调用**：选中标记为可展开的调用并使用 **继续展开此调用**，或在提示栏点名它；新版本会保留已验证图谱并追加新分支。

> Function history is keyed by file + receiver/function name, so it survives Git branch switches.
> 函数历史以「文件 + 接收者/函数名」为键，切换 Git 分支后仍可找到。

---

## 4. History, Reopen & Export / 历史、重开与导出

- Completed and refined reports are saved to the **IDE's local system directory**, not the analyzed repo. Reopen them from **历史报告 (History)**.
  完成与打磨后的报告保存在 **IDE 本地系统目录**，不进被分析仓库。可从 **历史报告** 重新打开。
- Identical selections reopen from cache without another model request (fingerprint-based cache).
  相同的选择会命中缓存直接重开，不再请求模型（基于指纹缓存）。
- Open change location, full business flow, commit evolution, and review findings in a **dedicated full-width editor tab**.
  在**专用的全宽编辑器标签**中查看变更位置、完整业务流程、提交演进与评审发现。
- Open source evidence in your current working tree, and **export self-contained HTML / JSON**.
  在当前工作树中打开源码证据，并**导出自包含的 HTML / JSON**。

> Source evidence opens through PSI using the report symbol, so it still resolves even when line numbers shift; it falls back to a read-only historical snapshot only when the current branch lacks the file.
> 源码证据通过 PSI + 报告符号定位，即使行号偏移也能跳转；仅当当前分支没有该文件时，才回退到只读的历史快照。

---

## 5. Model Configuration / 模型配置

- The **模型配置** tab switches the current project between the bundled `codex-local` and `claude-local` adapters.
  **模型配置** 标签在 `codex-local` 与 `claude-local` 适配器之间切换（按项目）。
- The **System Prompt** tab stores one project-level system prompt for business background, repository navigation, knowledge sources, local skills, and analysis requirements.
  **System Prompt** 标签保存一份项目级系统提示：业务背景、仓库导航、知识来源、本地技能与分析要求。

---

## 6. Privacy & Security / 隐私与安全

| Boundary / 边界 | Guarantee / 保证 |
| --- | --- |
| 🔒 Git operations | 插件自身**不** checkout / fetch / merge / commit / push |
| 🕵️ Secrets | `.env`、私钥、keystore、密钥目录等敏感路径在读取前被排除，常见密钥形态二次脱敏 |
| 🧾 Credentials | API 凭证、提供方设置、代理设置全部归属你选择的本地 CLI |
| ✅ Validation | 模型输出必须通过本地引用校验才能生成可展示报告 |
| 📡 Telemetry | 未实现，不收集遥测数据 |

> Analysis prompts are built only from executable source, tests, schemas, migrations, runtime config, and dependency manifests — docs, design notes, generated content, lock files, vendored code, and build output are excluded locally.
> 分析提示仅由可执行源码、测试、schema、迁移脚本、运行时配置与依赖清单构建；文档、设计笔记、生成内容、lock 文件、vendored 代码与构建产物在本地被排除。

---

Need setup help first? See the **Installation Guide**.
需要先完成配置？请查看 **安装指南**。
