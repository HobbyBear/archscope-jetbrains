# Installation Guide / 安装指南

> CodeBecause runs model analysis through a **local CLI on your own machine**. It does **not** bundle any AI model or manage any API credentials. You must install and sign in to a supported CLI first, otherwise analysis will not start.
>
> CodeBecause 通过你**本机的命令行工具（CLI）**运行模型分析，插件本身**不内置任何 AI 模型、也不托管任何 API 凭证**。你需要先安装并登录受支持的 CLI，否则分析无法启动。

---

## 1. Requirements / 环境要求

| Requirement / 要求 | Detail / 说明 |
| --- | --- |
| 🧩 IDE | IntelliJ IDEA / GoLand **2024.3** or later（基于 IntelliJ Platform 243+） |
| 🌿 Git | Must be installed and available on `PATH` / 需安装并在 `PATH` 中可用 |
| 🤖 Local CLI | **Codex CLI** or **Claude CLI**, installed, authenticated, and reachable by the IDE process / 已安装、已登录，且 IDE 进程能调用 |

> The CLI must be signed in on the **same machine** as the IDE. Credentials, model choice, tools, and permissions all stay owned by your CLI configuration.
> CLI 必须在与 IDE **同一台机器**上登录。凭证、模型选择、工具与权限均由你的 CLI 配置掌管，插件不接管。

---

## 2. Install the Plugin / 安装插件

**A. From JetBrains Marketplace（推荐）**

1. Open `Settings/Preferences → Plugins → Marketplace`
2. Search **CodeBecause**
3. Click **Install**, then restart the IDE / 点击安装后重启 IDE

**B. From Disk / 本地安装**

1. Download the `.zip` from the plugin page / 从插件页下载 `.zip`
2. `Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk...`
3. Select the `.zip` and restart / 选择 zip 并重启

---

## 3. Prepare the Local CLI / 准备本机 CLI

Pick **one** provider. Make sure the command works in your terminal **before** using the plugin.
任选**一个**提供方，并在使用插件**之前**确认命令能在终端正常运行。

- **Codex CLI** — install and sign in per its official instructions / 按官方说明安装并登录。
- **Claude CLI** — install and sign in per its official instructions / 按官方说明安装并登录。

Verify / 验证：

```bash
git --version        # Git 可用
# then confirm your chosen CLI launches and is authenticated
# 然后确认你选择的 CLI 能启动且已登录
```

> If the IDE was launched before the CLI was installed/authenticated, restart the IDE so it picks up the updated `PATH`.
> 如果 IDE 在安装/登录 CLI 之前就已启动，请重启 IDE，让它读取到更新后的 `PATH`。

---

## 4. First-Time Setup / 首次配置

1. Open the **CodeBecause** tool window from the right sidebar (or `Tools → Open CodeBecause`).
   从右侧边栏打开 **CodeBecause** 工具窗口（或 `Tools → Open CodeBecause`）。
2. Go to the **模型配置 (Model)** tab and select your provider: `codex-local` or `claude-local`.
   进入 **模型配置** 标签，选择提供方：`codex-local` 或 `claude-local`。
3. (Optional) In the **System Prompt** tab, add project-level background, navigation hints, and analysis requirements.
   （可选）在 **System Prompt** 标签填写项目级背景、导航提示与分析要求。

You're ready to analyze. / 配置完成，可以开始分析。

---

## 5. Troubleshooting / 常见问题

| Symptom / 现象 | Likely cause / 可能原因 | Fix / 解决 |
| --- | --- | --- |
| Analysis never starts / 分析不启动 | CLI 未安装或未登录 | 在终端确认 CLI 可运行并已登录，重启 IDE |
| "CLI not found" / 找不到 CLI | CLI 不在 IDE 的 `PATH` 中 | 从终端启动 IDE，或将 CLI 加入系统 `PATH` 后重启 |
| Git-related errors / Git 报错 | Git 未安装或不在 `PATH` | 安装 Git 并确认 `git --version` 可用 |
| Wrong working directory / 分析目录不对 | 默认取当前仓库 | 在 **新建分析** 页浏览选择正确的目录 |

Still stuck? See the Usage Guide, or contact the vendor via the plugin page.
仍有问题？请查看使用说明，或通过插件页联系作者。
