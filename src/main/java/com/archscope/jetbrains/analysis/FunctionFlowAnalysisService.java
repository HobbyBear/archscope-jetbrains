package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.i18n.PluginLanguage;
import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.EvidencePack;
import com.archscope.jetbrains.model.FunctionTarget;
import com.archscope.jetbrains.render.FunctionFlowRenderer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.ui.JBColor;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class FunctionFlowAnalysisService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Set<String> NODE_KINDS = Set.of(
            "entry", "function", "decision", "storage", "external", "return", "error");
    private static final Set<String> EDGE_KINDS = Set.of("call", "branch", "data", "return", "error");
    private static final Set<String> EDGE_EXECUTIONS = Set.of("next", "alternative", "parallel", "async");
    private final ModelClient modelClient;
    private final FunctionFlowRenderer renderer = new FunctionFlowRenderer();

    public FunctionFlowAnalysisService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            FunctionTarget target,
            String additionalPrompt,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        return analyze(request, evidence, target, additionalPrompt, indicator, statusListener, ignored -> {});
    }

    public AnalysisResult analyze(
            AnalysisRequest request,
            EvidencePack evidence,
            FunctionTarget target,
            String additionalPrompt,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            Consumer<ModelStreamEvent> streamListener
    ) throws Exception {
        PluginLanguage.use(request.outputLanguage());
        long startedAt = System.nanoTime();
        String tracingStatus = text("正在追踪函数与子调用", "Tracing the function and callees");
        publish(indicator, statusListener, tracingStatus, 0.18);
        streamListener.accept(ModelStreamEvent.status(tracingStatus));
        String response = modelClient.complete(
                systemPrompt(request),
                initialPrompt(target, evidence, additionalPrompt, request),
                workingDirectory(request, evidence),
                indicator,
                text("执行函数业务流程分析", "Run function-flow analysis"),
                statusListener,
                ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY,
                streamListener
        );
        String validationStatus = text("正在校验函数、分支和源码锚点", "Validating functions, branches, and source anchors");
        publish(indicator, statusListener, validationStatus, 0.88);
        streamListener.accept(ModelStreamEvent.status(validationStatus));
        JsonObject report = normalize(ModelJsonParser.parseObject(response), target, evidence, request);
        addDiagnostics(report, request, startedAt, "initial");
        String json = GSON.toJson(report);
        String html = renderer.render(report, !JBColor.isBright());
        indicator.setFraction(1.0);
        return new AnalysisResult(json, html, evidence.fingerprint(), evidence.targetCommit());
    }

    public AnalysisResult refine(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            ProgressIndicator indicator,
            Consumer<String> statusListener
    ) throws Exception {
        return refine(request, evidence, currentReportJson, instruction, indicator, statusListener, ignored -> {});
    }

    public AnalysisResult refine(
            AnalysisRequest request,
            EvidencePack evidence,
            String currentReportJson,
            String instruction,
            ProgressIndicator indicator,
            Consumer<String> statusListener,
            Consumer<ModelStreamEvent> streamListener
    ) throws Exception {
        PluginLanguage.use(request.outputLanguage());
        String normalizedInstruction = instruction == null ? "" : instruction.strip();
        if (normalizedInstruction.isBlank()) {
            throw new ModelClientException(text("请输入需要补充或修改的内容", "Enter what should be expanded or changed"));
        }
        JsonObject current = ModelJsonParser.parseObject(currentReportJson);
        FunctionTarget target = relocate(target(current, request.repositoryRoot()), evidence.repositoryRoot());
        long startedAt = System.nanoTime();
        String tracingStatus = text("正在按补充要求重新追踪函数流程", "Retracing the function flow");
        publish(indicator, statusListener, tracingStatus, 0.15);
        streamListener.accept(ModelStreamEvent.status(tracingStatus));
        String response = modelClient.complete(
                systemPrompt(request),
                refinementPrompt(target, evidence, current, normalizedInstruction, request),
                workingDirectory(request, evidence),
                indicator,
                text("修改函数业务流程", "Refine function-flow analysis"),
                statusListener,
                ModelClient.WorkspaceAccess.READ_ONLY_REPOSITORY,
                streamListener
        );
        JsonObject report = normalize(ModelJsonParser.parseObject(response), target, evidence, request);
        verifyExpansion(current, report, normalizedInstruction);
        JsonArray revisions = array(current, "revision_history");
        if (revisions == null) revisions = new JsonArray();
        else revisions = revisions.deepCopy();
        JsonObject revision = new JsonObject();
        revision.addProperty("instruction", normalizedInstruction);
        revision.addProperty("created_at", Instant.now().toString());
        revisions.add(revision);
        report.add("revision_history", revisions);
        addDiagnostics(report, request, startedAt, "refinement");
        String json = GSON.toJson(report);
        String html = renderer.render(report, !JBColor.isBright());
        indicator.setFraction(1.0);
        return new AnalysisResult(json, html, evidence.fingerprint(), evidence.targetCommit());
    }

    private JsonObject normalize(
            JsonObject report,
            FunctionTarget target,
            EvidencePack evidence,
            AnalysisRequest request
    ) throws ModelClientException {
        JsonArray nodes = array(report, "nodes");
        JsonArray edges = array(report, "edges");
        if (nodes == null || nodes.isEmpty()) throw invalid("没有返回函数流程节点");
        if (nodes.size() > 120) throw invalid("函数流程节点超过 120 个，请指定一个调用分支继续分析");
        if (edges == null) edges = new JsonArray();

        Set<String> manifest = new HashSet<>(evidence.targetManifest());
        Set<String> nodeIds = new HashSet<>();
        boolean rootFound = false;
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) throw invalid("nodes 必须全部为对象");
            JsonObject node = element.getAsJsonObject();
            String id = string(node, "id");
            if (id.isBlank() || !nodeIds.add(id)) throw invalid("节点 id 为空或重复：" + id);
            if ("root".equals(id)) rootFound = true;
            String kind = string(node, "kind").toLowerCase(Locale.ROOT);
            if (!NODE_KINDS.contains(kind)) node.addProperty("kind", "function");
            int depth = Math.max(0, integer(node, "depth", 0));
            node.addProperty("depth", depth);
            String file = normalizeModelPath(string(node, "file"), request, evidence, manifest);
            if (file.isBlank()) file = target.relativeFile();
            if (!manifest.contains(file)) throw invalid("节点引用了当前提交不存在的文件：" + file);
            node.addProperty("file", file);
            int line = Math.max(1, integer(node, "line", 1));
            int endLine = Math.max(line, integer(node, "end_line", line));
            try (java.util.stream.Stream<String> source = Files.lines(evidence.repositoryRoot().resolve(file))) {
                long sourceLines = source.count();
                if (line > sourceLines || endLine > sourceLines) {
                    throw invalid("节点源码行号超出文件范围：" + file + ":" + line + "-" + endLine);
                }
            } catch (ModelClientException exception) {
                throw exception;
            } catch (Exception ignored) {
                // The manifest remains authoritative when the current working tree cannot expose the target file.
            }
            node.addProperty("line", line);
            node.addProperty("end_line", endLine);
            ensureArray(node, "inputs");
            ensureArray(node, "outputs");
            ensureArray(node, "conditions");
            ensureArray(node, "side_effects");
            if (!node.has("expandable") || !node.get("expandable").isJsonPrimitive()) {
                node.addProperty("expandable", false);
            }
        }
        if (!rootFound) throw invalid("根函数节点必须使用 id=root");

        Set<String> edgeIds = new HashSet<>();
        for (JsonElement element : edges) {
            if (!element.isJsonObject()) throw invalid("edges 必须全部为对象");
            JsonObject edge = element.getAsJsonObject();
            String id = string(edge, "id");
            String from = string(edge, "from");
            String to = string(edge, "to");
            if (id.isBlank() || !edgeIds.add(id)) throw invalid("边 id 为空或重复：" + id);
            if (!nodeIds.contains(from) || !nodeIds.contains(to) || from.equals(to)) {
                throw invalid("边引用了无效节点：" + from + " -> " + to);
            }
            String kind = string(edge, "kind").toLowerCase(Locale.ROOT);
            if (!EDGE_KINDS.contains(kind)) edge.addProperty("kind", "call");
            String execution = string(edge, "execution").toLowerCase(Locale.ROOT);
            if (!EDGE_EXECUTIONS.contains(execution)) {
                execution = "branch".equals(kind) || "error".equals(kind) ? "alternative" : "next";
            }
            edge.addProperty("execution", execution);
            edge.addProperty("order", Math.max(0, integer(edge, "order", 0)));
            edge.addProperty("line", Math.max(1, integer(edge, "line", integer(findById(nodes, from), "line", 1))));
        }

        JsonObject targetJson = new JsonObject();
        targetJson.addProperty("stable_id", target.stableId());
        targetJson.addProperty("file", target.relativeFile());
        targetJson.addProperty("symbol", target.symbol());
        targetJson.addProperty("signature", target.signature());
        targetJson.addProperty("line", target.startLine());
        targetJson.addProperty("end_line", target.endLine());
        targetJson.addProperty("expansion_policy", "novice_complete");
        report.add("function_target", targetJson);
        report.addProperty("schema", "codebecause-function-flow/v1");
        report.addProperty("report_type", "function_flow");
        report.addProperty("output_language", request.outputLanguage().code());
        report.addProperty("target_commit", evidence.targetCommit());
        report.addProperty("fingerprint", evidence.fingerprint());
        if (string(report, "title").isBlank()) report.addProperty("title", target.symbol() + " 函数业务流程");
        if (string(report, "summary").isBlank()) report.addProperty("summary", "函数内部逻辑与子调用关系");
        report.add("nodes", nodes);
        report.add("edges", edges);
        normalizeCoverage(report, nodes);
        if (array(report, "unknowns") == null) report.add("unknowns", new JsonArray());
        if (array(report, "revision_history") == null) report.add("revision_history", new JsonArray());
        return report;
    }

    private void normalizeCoverage(JsonObject report, JsonArray nodes) {
        JsonObject coverage = object(report, "coverage");
        if (coverage == null) coverage = new JsonObject();
        JsonArray expandableIds = new JsonArray();
        for (JsonElement element : nodes) {
            JsonObject node = element.getAsJsonObject();
            if (bool(node, "expandable")) expandableIds.add(string(node, "id"));
        }
        boolean modelComplete = coverage.has("novice_complete")
                ? bool(coverage, "novice_complete") : expandableIds.isEmpty();
        coverage.addProperty("novice_complete", modelComplete && expandableIds.isEmpty());
        coverage.add("expandable_node_ids", expandableIds);
        if (string(coverage, "stopping_reason").isBlank()) {
            coverage.addProperty("stopping_reason", expandableIds.isEmpty()
                    ? text("所有业务相关分支均已解释到无需阅读源码", "All business-relevant branches are understandable without source")
                    : text("仍有可按需继续展开的业务调用", "Some business calls can still be expanded on demand"));
        }
        report.add("coverage", coverage);
    }

    private void verifyExpansion(JsonObject current, JsonObject candidate, String instruction) throws ModelClientException {
        if (!isExpansionInstruction(instruction)) return;
        JsonArray oldNodes = array(current, "nodes");
        JsonArray newNodes = array(candidate, "nodes");
        JsonArray oldEdges = array(current, "edges");
        JsonArray newEdges = array(candidate, "edges");
        Set<String> newNodeIds = ids(newNodes);
        Set<String> newEdgeIds = ids(newEdges);
        for (String id : ids(oldNodes)) {
            if (!newNodeIds.contains(id)) throw invalid("继续展开时丢失了原节点：" + id);
        }
        for (String id : ids(oldEdges)) {
            if (!newEdgeIds.contains(id)) throw invalid("继续展开时丢失了原连线：" + id);
        }
        boolean addedStructure = newNodeIds.size() > ids(oldNodes).size() || newEdgeIds.size() > ids(oldEdges).size();
        boolean resolvedBoundary = oldNodes != null && oldNodes.asList().stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(oldNode -> bool(oldNode, "expandable")
                        && !bool(findById(newNodes, string(oldNode, "id")), "expandable"));
        if (!addedStructure && !resolvedBoundary) {
            throw invalid("继续展开没有新增调用/逻辑节点，也没有解释完原有边界节点");
        }
    }

    private static boolean isExpansionInstruction(String instruction) {
        String value = instruction.toLowerCase(Locale.ROOT);
        return value.contains("展开") || value.contains("深入") || value.contains("继续追踪")
                || value.contains("往下追") || value.contains("expand") || value.contains("drill down")
                || value.contains("trace deeper");
    }

    private static Set<String> ids(JsonArray values) {
        Set<String> result = new HashSet<>();
        if (values == null) return result;
        for (JsonElement element : values) {
            if (element.isJsonObject()) result.add(string(element.getAsJsonObject(), "id"));
        }
        return result;
    }

    private static JsonObject findById(JsonArray values, String id) {
        if (values == null) return null;
        for (JsonElement element : values) {
            if (element.isJsonObject() && id.equals(string(element.getAsJsonObject(), "id"))) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private String systemPrompt(AnalysisRequest request) {
        String language = request.outputLanguage().isEnglish() ? "Write all reader-facing text in English."
                : "所有面向读者的说明必须使用简体中文。";
        String guidance = request.guidance().systemPrompt().isBlank() ? "" : "\nPROJECT SYSTEM PROMPT (MANDATORY):\n"
                + request.guidance().systemPrompt();
        boolean repoMindRequested = request.guidance().systemPrompt().toLowerCase(Locale.ROOT).contains("repomind");
        String repoMindRule = repoMindRequested ? """

                REQUIRED REPOMIND STEP FOR THIS ACTION:
                - Invoke the repomind-query Skill exactly once before source inspection so the audit proves it was loaded.
                - From that skill, use only read-only routing/query guidance. Run at most one `repomind kb-search` and read at
                  most one matching knowledge document. Do not run kb-build because it can update generated knowledge files.
                - Skip findings write-back and the mandatory summary gate from that skill. Never invoke repomind-summary.
                """ : """

                REPOMIND STEP FOR THIS ACTION:
                - Project guidance did not request RepoMind. Do not invoke RepoMind skills or commands.
                """;
        return """
                You are producing a function-level business execution flow, not a high-level domain overview.
                This entire action is one single-session SOP and one model-client call. Do not delegate to subagents, start a
                second analysis/summary phase, ask a follow-up question, or defer work. Return the final JSON in this session.
                Inspect the exact target function and selectively inspect business-relevant reachable callees. Represent internal
                decisions, early returns, error paths, persistence, external calls, and meaningful data transformations as
                separate nodes. Every node must bind to a real repository-relative file and source line. Never invent code.
                There is no fixed numeric call-depth limit, but there is a strict evidence budget. Explain the selected scope so
                a newcomer understands its business purpose, material conditions, data/state changes, external effects, and
                outcome without reading source. Mark other meaningful calls as expandable boundaries instead of chasing them.

                STRICT READ-ONLY OVERRIDE:
                - Never create, edit, delete, move, or generate any repository or temporary file. Do not use shell redirection.
                - Never invoke repomind-summary or write .repomind/.query-findings.json.
                - Use at most 12 tool calls total. Once the budget is sufficient, stop browsing and produce the JSON.
                - Return exactly one JSON object and no Markdown. Self-check it mentally; do not launch a repair model call.
                """ + repoMindRule + language + guidance + """

                FUNCTION-FLOW SAFETY RULES OVERRIDE PROJECT GUIDANCE WHEN THEY CONFLICT: repository access remains
                read-only, the 12-tool budget remains fixed, and RepoMind write-back/summary remains forbidden.
                """;
    }

    private String initialPrompt(
            FunctionTarget target,
            EvidencePack evidence,
            String additionalPrompt,
            AnalysisRequest request
    ) {
        return commonPrompt(target, evidence, request) + "\nANALYSIS REQUEST:\n"
                + (additionalPrompt == null || additionalPrompt.isBlank() ? "完整分析该函数的业务调用与内部逻辑。" : additionalPrompt.strip());
    }

    private String refinementPrompt(
            FunctionTarget target,
            EvidencePack evidence,
            JsonObject current,
            String instruction,
            AnalysisRequest request
    ) {
        return commonPrompt(target, evidence, request)
                + "\nCURRENT VERIFIED REPORT (return a complete replacement JSON, but preserve its verified structure):\n"
                + GSON.toJson(current)
                + "\nREFINEMENT RULES:\n"
                + "- Reuse every existing node id and edge id unless the instruction explicitly asks to correct or remove it.\n"
                + "- For an expansion request, locate the named node by id, symbol, or label; keep every existing node and edge; "
                + "append the newly inspected callees and logic to that branch.\n"
                + "- Continue that branch within this action's evidence budget. Mark the next useful boundary expandable rather "
                + "than exceeding the budget. Do not reapply an old numeric depth limit.\n"
                + "\nFOLLOW-UP INSTRUCTION:\n" + instruction;
    }

    private String commonPrompt(
            FunctionTarget target,
            EvidencePack evidence,
            AnalysisRequest request
    ) {
        return """
                TARGET FUNCTION:
                - file relative to CLI working directory: %s
                - file relative to Git repository root: %s
                - symbol: %s
                - signature: %s
                - declaration lines: %d-%d
                - target commit: %s
                - CLI working directory: %s
                - Git repository root: %s
                - expansion policy: novice_complete (no fixed numeric depth)

                SINGLE-SESSION SOP:
                1. Trust the supplied commit and paths. Do not run pwd, git status, git log, cat-file, ls-tree, or checkout.
                2. Follow the REQUIRED REPOMIND STEP from the system prompt when present, including its strict read-only limits.
                3. Read the target function from the target commit with one `git show <commit>:<Git-root-relative-file>` command,
                   piped through `nl -ba` before selecting the declaration lines and small necessary context. Do not copy it to
                   a temporary file. Every displayed number must remain the original full-file line number.
                4. Identify direct calls in that function. Batch searches where possible, then inspect no more than two callee
                   implementation bodies that carry the most business meaning for this action. Depth is not numerically capped;
                   the implementation-body budget is. Framework, utility, already-understood, and remaining calls become concise
                   boundary nodes with expandable=true when deeper business logic may be useful.
                5. Build the graph, check IDs/edges/paths/line anchors against gathered evidence, and immediately return JSON.
                   "Detailed" or "尽可能详细" means richer explanation inside this verified scope, never a larger tool budget.

                SOURCE-ANCHOR RULES:
                - For every expanded callee, use `git show <commit>:<path> | nl -ba` and preserve full-file line numbers.
                - Never derive line/end_line from an unnumbered sed/grep excerpt or from the excerpt's relative position.
                - If exact callee declaration/end lines were not verified, represent the call as an expandable boundary anchored
                  to its verified call-site lines in the caller. Never guess a source range.

                OUTPUT SCHEMA:
                {
                  "schema":"codebecause-function-flow/v1",
                  "title":"...", "summary":"...",
                  "nodes":[{
                    "id":"root", "symbol":"Receiver.Method", "label":"...",
                    "kind":"entry|function|decision|storage|external|return|error", "depth":0,
                    "file":"repository/relative.go", "line":1, "end_line":10,
                    "business_role":"...", "logic":"...",
                    "inputs":["..."], "outputs":["..."], "conditions":["..."], "side_effects":["..."],
                    "expandable":false, "expansion_reason":"..."
                  }],
                  "edges":[{"id":"e1","from":"root","to":"n2","kind":"call|branch|data|return|error",
                    "execution":"next|alternative|parallel|async","order":1,"line":1,"label":"...","condition":"..."}],
                  "coverage":{"novice_complete":true,"stopping_reason":"...","expandable_node_ids":[]},
                  "unknowns":["..."]
                }

                RULES:
                - The selected function is node id "root" and depth 0.
                - A callee is depth parent+1. Internal decisions may share their enclosing function depth.
                - Do not stop at a fixed numeric depth. Within the implementation-body budget, choose the calls that are necessary
                  to explain the trigger and inputs, material branches and errors, data/state mutations, external effects, and outcome.
                - A branch may stop at a standard-library/framework/third-party boundary, a trivial helper, a cycle, unavailable
                  source, or when deeper code adds no business meaning. Describe the effect in plain language.
                - If a business-relevant call remains unexpanded because of the evidence budget, retain it as a boundary node
                  with expandable=true and explain why in expansion_reason. Set coverage.novice_complete=false and list its id.
                - Keep distinct success, rejection, error, retry, and early-return branches.
                - Edges describe runtime execution, not visual grouping. For sequential A then B then C, emit A->B and B->C
                  with execution=next; never attach B and C as sibling edges to A. A node may have at most one next edge.
                - Multiple alternative edges leave a decision and are mutually exclusive: one invocation follows only one.
                  Use parallel only when source proves concurrent execution, and async only when source launches background work
                  without waiting for it in this flow. A shared visual row never proves parallelism.
                - Set edge line to the caller's exact call/condition line and order to source execution order starting at 1.
                  If sharing one operation node across alternative branches would lose each branch's order, create branch-scoped
                  nodes instead. Do not merge repeated calls merely because they invoke the same function.
                - Do not collapse functions into broad business phases. Prefer a compact graph, but completeness for a novice wins.
                - Use only paths present at target commit. Keep the graph readable and omit trivial language/runtime helpers.
                - The report should explain what the code means for the business, while source anchors prove every claim.
                """.formatted(
                target.relativeFile(), repositoryTargetPath(target, evidence, request), target.symbol(), target.signature(),
                target.startLine(), target.endLine(), evidence.targetCommit(), workingDirectory(request, evidence),
                evidence.repositoryRoot());
    }

    private void addDiagnostics(JsonObject report, AnalysisRequest request, long startedAt, String operation) {
        JsonObject diagnostics = new JsonObject();
        diagnostics.addProperty("operation", operation);
        diagnostics.addProperty("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000L);
        diagnostics.addProperty("model_calls", 1);
        diagnostics.addProperty("sop_sessions", 1);
        diagnostics.addProperty("tool_call_budget", 12);
        diagnostics.addProperty("workspace_access", "read_only_repository");
        diagnostics.addProperty("model_provider_id", modelClient.id());
        diagnostics.addProperty("model_provider_name", modelClient.displayName());
        diagnostics.addProperty("system_prompt_applied", !request.guidance().systemPrompt().isBlank());
        diagnostics.addProperty("output_language", request.outputLanguage().code());
        diagnostics.addProperty("cli_working_directory_path", workingDirectory(request, null).toString());
        report.add("analysis_diagnostics", diagnostics);
    }

    private Path workingDirectory(AnalysisRequest request, EvidencePack evidence) {
        if (request.cliWorkingDirectory() != null) return request.cliWorkingDirectory().toAbsolutePath().normalize();
        if (evidence != null && evidence.repositoryRoot() != null) return evidence.repositoryRoot().toAbsolutePath().normalize();
        return request.repositoryRoot().toAbsolutePath().normalize();
    }

    private String repositoryTargetPath(
            FunctionTarget target,
            EvidencePack evidence,
            AnalysisRequest request
    ) {
        return normalizeModelPath(target.relativeFile(), request, evidence, new HashSet<>(evidence.targetManifest()));
    }

    private FunctionTarget target(JsonObject report, Path repositoryRoot) throws ModelClientException {
        JsonObject target = object(report, "function_target");
        if (target == null) throw invalid("当前报告缺少函数目标");
        return new FunctionTarget(
                repositoryRoot,
                string(target, "file"),
                string(target, "symbol"),
                string(target, "signature"),
                integer(target, "line", 1),
                integer(target, "end_line", integer(target, "line", 1))
        );
    }

    private FunctionTarget relocate(FunctionTarget target, Path repositoryRoot) {
        try {
            Path file = repositoryRoot.resolve(target.relativeFile()).normalize();
            if (!file.startsWith(repositoryRoot.toAbsolutePath().normalize()) || !Files.isRegularFile(file)) return target;
            java.util.List<String> lines = Files.readAllLines(file);
            String leaf = target.symbol().contains(".")
                    ? target.symbol().substring(target.symbol().lastIndexOf('.') + 1)
                    : target.symbol();
            java.util.regex.Pattern declaration = java.util.regex.Pattern.compile("^\\s*func(?:\\s*\\([^)]*\\))?\\s+"
                    + java.util.regex.Pattern.quote(leaf) + "\\s*\\(");
            for (int index = 0; index < lines.size(); index++) {
                if (!declaration.matcher(lines.get(index)).find()) continue;
                int span = Math.max(0, target.endLine() - target.startLine());
                return new FunctionTarget(repositoryRoot, target.relativeFile(), target.symbol(),
                        lines.get(index).strip(), index + 1, Math.min(lines.size(), index + 1 + span));
            }
        } catch (Exception ignored) {
            // The saved anchor remains useful when the current branch cannot be read or parsed.
        }
        return target;
    }

    private void publish(ProgressIndicator indicator, Consumer<String> listener, String text, double fraction) {
        indicator.checkCanceled();
        indicator.setText(text);
        indicator.setFraction(fraction);
        listener.accept(text);
    }

    private ModelClientException invalid(String detail) {
        return new ModelClientException(text("函数流程报告校验失败：", "Function-flow report validation failed: ") + detail);
    }

    private String text(String chinese, String english) {
        return PluginLanguage.text(chinese, english);
    }

    private static void ensureArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) object.add(name, new JsonArray());
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.replace('\\', '/').strip();
        while (value.startsWith("./")) value = value.substring(2);
        return value;
    }

    private static String normalizeModelPath(
            String path,
            AnalysisRequest request,
            EvidencePack evidence,
            Set<String> manifest
    ) {
        String normalized = normalizePath(path);
        if (manifest.contains(normalized) || normalized.isBlank()) return normalized;
        Path workingDirectory = request.cliWorkingDirectory();
        if (workingDirectory == null || evidence.repositoryRoot() == null) return normalized;
        try {
            Path root = evidence.repositoryRoot().toAbsolutePath().normalize();
            Path working = workingDirectory.toAbsolutePath().normalize();
            if (!working.startsWith(root)) return normalized;
            String prefix = normalizePath(root.relativize(working).toString());
            String candidate = prefix.isBlank() ? normalized : prefix + "/" + normalized;
            return manifest.contains(candidate) ? candidate : normalized;
        } catch (RuntimeException ignored) {
            return normalized;
        }
    }

    private static JsonObject object(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonObject() ? owner.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonArray() ? owner.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject owner, String name) {
        return owner != null && owner.has(name) && owner.get(name).isJsonPrimitive() ? owner.get(name).getAsString() : "";
    }

    private static int integer(JsonObject owner, String name, int fallback) {
        try {
            return owner != null && owner.has(name) ? owner.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject owner, String name) {
        try {
            return owner != null && owner.has(name) && owner.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
