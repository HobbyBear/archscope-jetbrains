# AI Code Review & Understanding

JetBrains plugin for evidence-backed change review and business understanding. It can analyze selected Git commits or a
natural-language topic such as "分析聊天逻辑", then keep refining the generated report inside the IDE.

## What It Does

- Select one or more rows in the IDE's native Git Log and start analysis from its context menu.
- Open the tool window without selecting commits, enter a business topic, and analyze the locked current `HEAD` snapshot.
- Present business purpose, actors, vocabulary, business domains, domain relationships, complete flows, data reads and
  writes, failure exits, observable outcomes, and auditable source locations for a newcomer to the product.
- Continue entering prompts at the bottom of a report to expand, correct, or reorganize it without losing verified context.
- Automatically run follow-up evidence rounds for unresolved business links, stopping on confirmation or when the
  current report exposes no unsearched exact query or no new enclosing-function evidence.
- Save completed and refined reports in the IDE's local system directory and reopen them from **历史报告** without
  adding files to the analyzed repository.
- Resolve the baseline and final snapshot from Git ancestry, never author timestamps.
- Combine a continuous multi-commit selection into one net `base..target` change while retaining per-commit attribution.
- Materialize only changed target files and the exact caller/outcome evidence requested for unresolved explanation gaps.
- Keep generated `.repomind` knowledge in coverage statistics but omit its duplicate patch text when runtime or specification changes exist.
- Analyze bounded changes in one closed-evidence model turn; use planning plus targeted evidence expansion only for larger changes.
- Cache validated reports by the locked analysis fingerprint so an identical selection reopens without another model request.
- Plan business-topic evidence from a locked path index, then run precise local Git searches and provide the complete
  enclosing function scopes as redacted, line-numbered evidence. Exact hits are grouped by file so distant setup, early
  returns, deferred cleanup, settlement, and event calls can be evaluated as one control-flow path.
- Use two closed-evidence provider turns: change grouping, then concise entry/change/outcome analysis with no autonomous repository search.
- Deterministically assemble and validate the complete versioned report JSON without a plugin API client or plugin-managed credentials.
- Validate commit, file, node, lane, contract, flow, and evidence references locally.
- Open change location, full business flow, commit evolution, and review findings in a dedicated full-width IDE editor
  tab; the tool window remains focused on the active selection, progress, and report export.
- Open source evidence from the historical target commit and export self-contained HTML/JSON.
- Open source evidence through PSI in the user's current working tree when the path still exists, using the report symbol
  to survive shifted line numbers; fall back to a read-only historical snapshot only when the current branch has no file.

## Model Providers

Analysis depends on the `ModelClient` interface rather than a concrete vendor. The `模型配置` tab switches the current
project between the bundled `codex-local` and `claude-local` adapters. The selected CLI must already be installed and
signed in on the same machine as the IDE; credentials and model settings remain owned by that CLI.

Additional adapters can implement `ModelClient`, register through Java `ServiceLoader` under
`META-INF/services/com.archscope.jetbrains.analysis.ModelClient`, and expose a stable provider ID. Tests and headless
benchmarks can still select an adapter with the `archscope.modelProvider` system property. Analysis, evidence collection,
caching, validation, rendering, and refinement are provider-neutral.

For a selected-change report, the plugin:

1. Locks the selected commits, target commit, target tree, and analysis fingerprint.
2. Verifies that multiple selections form one continuous first-parent chain, then creates a single aggregate diff from
   the parent of the oldest selected commit to the selected chain tip. Current staged, unstaged, and untracked changes are not included.
3. Excludes sensitive paths such as `.env`, private keys, keystores, and secret directories before Codex starts.
4. Writes one aggregate redacted diff; commit/path metadata retains attribution without resending duplicate per-commit patches.
5. Runs ephemeral closed-evidence provider turns without changing the configured model, provider, proxy, approval, sandbox,
   MCP, skills, or project instructions. The plugin bounds reasoning effort to `medium` for report synthesis and `low`
   for scope planning so deterministic JSON work does not inherit an unnecessarily expensive global setting.
6. Expands the concise model result into deterministic lanes, contracts, nodes, edges, comparison metadata, and commit evolution.
7. Validates every reported commit, tree, fingerprint, repository-relative path, line number, and reference locally,
   then deletes the temporary workspace.

For a business-topic report, the plugin first locks `HEAD` and its tracked manifest. A low-cost planning turn selects at
most eight candidate files and eight precise search literals. The plugin performs those searches locally, groups every hit
by its enclosing function, supplies the complete relevant function bodies, and follows newly exposed producer, caller,
callee, persistence, and consumer symbols in later evidence rounds. It stops when the questions are confirmed or a round
finds no new plan or source evidence, rather than after a fixed time window. Secrets are redacted before model analysis. Deterministic Java
assembly creates lanes, source nodes, edges, contracts, comparison metadata, and the final interactive report. Follow-up
prompts use the same source-evidence process and append a revision record.
Business flow steps preserve state effects such as created, unchanged, deferred deletion, and event emission, and show a
direct source reference on the flow card as well as in its detail panel.

The tool window opens on a per-project history list backed by local JSON and HTML archives. The `自定义提示词` tab stores
one optional prompt for business background and code-reading preferences, with additional system guidance collapsed under
advanced settings. Empty input shows an example. A user can mention a project skill directly in this prompt when the
selected CLI supports it. Guidance can steer analysis depth and business context, but cannot override closed-evidence,
source-validation, secret-redaction, or report-schema rules.

## Build

Requirements:

- Java 21
- Git available on `PATH`
- Codex CLI or Claude CLI installed, authenticated, and available to the IDE process

```bash
./gradlew clean test verifyPluginProjectConfiguration verifyPluginStructure buildPlugin
```

The installable archive is generated under `build/distributions/`.

Install it with `Settings | Plugins | Install Plugin from Disk` in IntelliJ IDEA or GoLand.

## Compatibility

The plugin is built against IntelliJ Platform 2024.3 and declares only `com.intellij.modules.platform`. Plugin Verifier is configured for:

- IntelliJ IDEA Community 2024.3.6
- GoLand 2024.3.6

Language-specific PSI support can be added as optional enhancements. Git snapshot analysis and model interpretation remain language-independent.

## Security Boundaries

- The plugin itself does not checkout, fetch, merge, commit, or push.
- Sensitive paths such as `.env`, private keys, keystores, and secret directories are excluded before patch content is read.
- Common secret shapes are redacted as a second layer.
- The selected provider receives redacted Git patches or bounded source snippets from a locked commit, never untracked files.
- Provider runtime behavior and credentials remain owned by the selected adapter.
- The model cannot produce a displayable report unless local reference validation passes.
- API credentials, provider settings, and proxy settings remain owned by the selected local CLI.
- Telemetry is not implemented.

## Paid Marketplace Release

This repository currently builds an installable development version. Before publishing it as a paid JetBrains Marketplace plugin:

1. Register the plugin ID and request a JetBrains-approved Product Code.
2. Add the approved `product-descriptor` with matching paid-plugin versioning.
3. Integrate JetBrains' official license-checking code and define trial behavior.
4. Add the Developer EULA, privacy policy, support URL, trader status, signing certificate, and publishing token.
5. Run Plugin Verifier against every advertised IDE release.

Do not add a placeholder Product Code. JetBrains ties it to sales and fallback-license history, so changing it later is costly.
