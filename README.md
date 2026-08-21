# CodeBecause

JetBrains plugin for evidence-backed business understanding. It analyzes a natural-language topic such as "分析聊天逻辑",
then keeps refining the generated report inside the IDE.

## What It Does

- Open the tool window, enter a business topic, and analyze the current repository snapshot.
- Present business purpose, actors, vocabulary, business domains, domain relationships, complete flows, data reads and
  writes, failure exits, observable outcomes, and auditable source locations for a newcomer to the product.
- Continue entering prompts at the bottom of a report to expand, correct, or reorganize it without losing verified context.
- Run repository discovery, evidence tracing, report generation, and self-review inside one CLI SOP session per button
  action. Unresolved links remain explicit unknowns instead of starting hidden follow-up model calls.
- Save completed and refined reports in the IDE's local system directory and reopen them from **历史报告** without
  adding files to the analyzed repository.
- Build analysis prompts only from executable source, tests, schemas, migrations, runtime configuration, and dependency manifests; exclude documentation, design notes, reports, generated knowledge, embedded page assets, lock files, vendored code, and build output locally.
- Run the selected local CLI from the working directory shown on **新建分析**, defaulting to the current repository;
  the user can browse to another directory before starting the analysis. Preserve the user's normal CLI configuration and tools;
  use one read-only repository SOP to keep the final report source-verifiable.
- Cache validated reports by the locked analysis fingerprint so an identical selection reopens without another model request.
- Ask the CLI once to locate the real entry, trace the causal path, keep an internal evidence ledger, build one typed
  end-to-end flow graph, and self-check JSON, references, enums, data lineage, and requested edit postconditions before returning.
- Reject invalid or truncated graph JSON locally without launching a second model repair turn or silently reducing it to
  disconnected prose cards.
- Keep report nodes bound to the locked target revision even when the current working tree differs.
- Deterministically assemble and validate the complete versioned report JSON without a plugin API client or plugin-managed credentials.
- Validate commit, file, node, lane, contract, flow, and evidence references locally.
- Open change location, full business flow, commit evolution, and review findings in a dedicated full-width IDE editor
  tab; the tool window remains focused on the active selection, progress, and report export.
- Open source evidence in the current working tree and export self-contained HTML/JSON.
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

For a business-topic report, the plugin locks `HEAD` and its tracked manifest, then starts exactly one CLI session. The
session follows a fixed SOP: define the actor goal, search registered entries, trace direct and proven asynchronous
continuations, build an internal evidence ledger, generate the compact report, and check and repair it before returning
once. Deterministic Java assembly then creates lanes, source nodes, edges, contracts, comparison metadata, and the final
interactive report; strict local validation never calls the model. A follow-up prompt is a separate button action with
the same one-session rule and appends a revision record.
Business flow steps preserve state effects such as created, unchanged, deferred deletion, and event emission, and show a
direct source reference on the flow card as well as in its detail panel.

The tool window opens on a per-project history list backed by local JSON and HTML archives. The `System Prompt` tab stores
one project-level system prompt for business background, repository navigation, knowledge sources, local skills, and
analysis requirements. Codex receives the system instructions and request together over standard input, while Claude
receives the system instructions through a temporary file passed with `--system-prompt-file`. The plugin requires strict compliance except where
platform safety, the report schema, or source-truth validation must take precedence.

### Model run audit log

Every Codex or Claude run writes a local audit bundle under the IDE system directory:

```text
ai-code-review-understanding/model-audit/
  latest.json
  latest-codex-local.json
  latest-claude-local.json
  <timestamp>-<provider>-<id>/
    request.json
    events.jsonl
    summary.json
```

`request.json` contains the redacted system and user input sent to the CLI. `events.jsonl` contains only tool names and
tool-call inputs extracted from the CLI event stream; tool results and source contents are not persisted. `summary.json`
distinguishes a prompt that merely mentions RepoMind from runtime evidence such as loading `repomind-query/SKILL.md`,
running `kb-migrate` or `kb-metadata`, and reading `.repomind` knowledge files. The two `latest-<provider>.json` files point
to the newest audit bundle for each adapter. At most 100 run directories are retained. Common credential shapes are
redacted before writing. Override the directory with `-Darchscope.modelAuditDir=<path>` when needed.

### Function-level business flows

In GoLand, place the caret inside a Go function or method and use **Analyze Function Business Flow** from the editor
context menu. CodeBecause recursively follows business-relevant calls until a newcomer can understand the trigger,
branches, data and state changes, external effects, errors, and outcome without reading source code. It does not stop at
a fixed numeric call depth. Trivial helpers, cycles, unavailable source, and framework or third-party boundaries are
summarized by effect instead of expanded mechanically.

Each function analysis or refinement is exactly one read-only CLI SOP session and one plugin model-client call. The SOP
targets at most 12 read-tool calls, expands only the most meaningful implementation bodies, and marks remaining calls as
expandable boundaries. Function sessions use low reasoning effort and stop after five minutes. When project guidance asks
for RepoMind, the session loads `repomind-query` for read-only routing but skips knowledge builds, findings write-back, and
`repomind-summary`.

The gutter icon beside a function opens its newest saved function report, or starts the first analysis when no history
exists. Function history is keyed by repository-relative file plus receiver/function name, so it remains discoverable
after switching Git branches. Every generated or refined version is retained in the normal report history. Calls that
still contain useful hidden business logic are marked as expandable. Select one and use **继续展开此调用**, or name it in
the prompt bar; the next version preserves the verified graph and appends the newly inspected branch.

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

### Report visual acceptance

Business diagrams are compiled into a typed, deterministic intermediate representation and must pass the built-in 9/9
semantic and geometry checks before rendering. The browser acceptance pass covers light and dark themes at desktop,
laptop, and mobile widths, and writes screenshots plus a SHA-256-bound JSON receipt:

```bash
./gradlew test --tests com.archscope.jetbrains.render.ReportPreviewTest \
  -Darchscope.previewInput="$PWD/src/test/resources/visual/business-flow-report.json" \
  -Darchscope.previewOutput=/tmp/codebecause-visual/report.html \
  -Darchscope.previewDark=false
node scripts/report-visual-check.mjs \
  /tmp/codebecause-visual/report.html \
  /tmp/codebecause-visual/acceptance
```

The visual checker requires Playwright. It checks page containment, nonblank SVG output, node and text overlap, edge-label
bounds, required controls, and the embedded 9/9 diagram receipt. Set `ARCHSCOPE_PLAYWRIGHT_ROOT` when Playwright is not
installed in this repository or `/tmp/archscope-playwright/node_modules`.

## Compatibility

The plugin is built against IntelliJ Platform 2024.3 and declares only `com.intellij.modules.platform`. Plugin Verifier is configured for:

- IntelliJ IDEA Community 2024.3.6
- GoLand 2024.3.6

Language-specific PSI support can be added as optional enhancements. Git snapshot analysis and model interpretation remain language-independent.

## Security Boundaries

- The plugin itself does not checkout, fetch, merge, commit, or push.
- Sensitive paths such as `.env`, private keys, keystores, and secret directories are excluded before patch content is read.
- Common secret shapes are redacted as a second layer.
- The selected local CLI runs in the working directory selected on **新建分析** and can inspect whatever its normal user configuration permits.
- Provider permissions, tools, skills, runtime behavior, and credentials remain owned by the user's local CLI configuration.
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
