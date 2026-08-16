package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.model.AnalysisRequest;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds report structure locally and treats model output as optional prose only. */
final class DomainTextReportAssembler {
    private static final Pattern NUMBERED_LINE = Pattern.compile("(?m)^(\\d+):");
    private static final Pattern NUMBERED_SOURCE_LINE = Pattern.compile("(?m)^(\\d+):(.*)$");
    private static final Pattern SCOPE_HEADER = Pattern.compile(
            "(?m)^(?:complete|partial)_function_scope:(\\d+)-(\\d+)[^\\n]*$"
    );
    private static final Pattern GO_FUNCTION = Pattern.compile(
            "\\bfunc\\s+(?:\\([^)]*\\)\\s*)?([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\("
    );
    private static final Pattern NAMED_FUNCTION = Pattern.compile(
            "\\b(?:def|function|fun)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\("
    );
    private static final Pattern CALLABLE = Pattern.compile(
            "([\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*\\("
    );
    private static final Pattern TYPE_DEFINITION = Pattern.compile(
            "\\b(?:class|type|interface|record|struct)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)"
    );
    private static final Set<String> UNCERTAINTY_MARKERS = Set.of(
            "待确认", "无法确认", "不能确认", "尚未确认", "未完整展示", "未知"
    );

    private final DomainReportAssembler reportAssembler = new DomainReportAssembler();

    List<String> slots(String sourceEvidence) {
        return textContract(sourceEvidence, List.of(), Set.of()).slots();
    }

    TextContract textContract(String sourceEvidence, DomainEvidencePlan plan, EvidencePack evidence) {
        return textContract(sourceEvidence, plan.candidatePaths(), Set.copyOf(evidence.targetManifest()));
    }

    private TextContract textContract(String sourceEvidence, List<String> fallbackPaths, Set<String> manifest) {
        List<Anchor> anchors = anchors(sourceEvidence, fallbackPaths, manifest);
        List<String> result = new ArrayList<>(List.of(
                "REPORT_TITLE", "REPORT_SUMMARY", "OVERVIEW_PURPOSE", "PRIMARY_ACTOR",
                "DOMAIN_NAME", "DOMAIN_PURPOSE"
        ));
        JsonArray bindings = new JsonArray();
        if (!anchors.isEmpty()) {
            List<String> flowSlots = List.of(
                    "FLOW_1_TITLE", "FLOW_1_SUMMARY", "FLOW_1_TRIGGER", "FLOW_1_OUTCOME"
            );
            result.addAll(flowSlots);
            JsonObject flowBinding = new JsonObject();
            flowBinding.addProperty("binding_kind", "complete_flow");
            JsonArray sources = new JsonArray();
            Anchor first = anchors.get(0);
            for (int index = 0; index < anchors.size(); index++) {
                Anchor anchor = anchors.get(index);
                if (first.path().equals(anchor.path()) && first.symbol().equals(anchor.symbol())) {
                    sources.add(sourceBinding(anchor, index + 1));
                }
            }
            flowBinding.add("sources", sources);
            JsonArray boundSlots = new JsonArray();
            flowSlots.forEach(boundSlots::add);
            flowBinding.add("slots", boundSlots);
            bindings.add(flowBinding);
        }
        for (int index = 0; index < anchors.size(); index++) {
            int number = index + 1;
            List<String> anchorSlots = List.of(
                    "STEP_" + number + "_TITLE", "STEP_" + number + "_SUMMARY",
                    "STEP_" + number + "_DOMAIN_ID", "STEP_" + number + "_FLOW_KEY"
            );
            result.addAll(anchorSlots);
            Anchor anchor = anchors.get(index);
            JsonObject binding = sourceBinding(anchor, number);
            binding.addProperty("binding_kind", "source_step");
            JsonArray boundSlots = new JsonArray();
            anchorSlots.forEach(boundSlots::add);
            binding.add("slots", boundSlots);
            bindings.add(binding);
        }
        return new TextContract(List.copyOf(result), bindings);
    }

    private JsonObject sourceBinding(Anchor anchor, int number) {
        JsonObject binding = new JsonObject();
        binding.addProperty("source_number", number);
        binding.addProperty("file", anchor.path());
        binding.addProperty("line", anchor.line());
        binding.addProperty("symbol", anchor.symbol());
        return binding;
    }

    JsonObject assemble(
            String raw,
            AnalysisRequest request,
            EvidencePack evidence,
            DomainEvidencePlan plan,
            String sourceEvidence
    ) throws ModelClientException {
        JsonObject legacy = legacyAnalysis(raw);
        if (legacy != null) return reportAssembler.assemble(legacy.toString(), request, evidence);

        boolean english = request.outputLanguage().isEnglish();
        List<Anchor> anchors = anchors(sourceEvidence, plan.candidatePaths(), Set.copyOf(evidence.targetManifest()));
        if (anchors.isEmpty()) {
            throw new ModelClientException(english
                    ? "No source-backed report step could be built from the collected evidence."
                    : "已收集证据中没有可由代码生成的源码步骤");
        }
        Map<String, String> text = parseTextSlots(raw);
        JsonObject compact = compactAnalysis(text, anchors, plan, request, english);
        return reportAssembler.assemble(compact.toString(), request, evidence);
    }

    private JsonObject compactAnalysis(
            Map<String, String> text,
            List<Anchor> anchors,
            DomainEvidencePlan plan,
            AnalysisRequest request,
            boolean english
    ) {
        String genericTitle = english ? "Business domain analysis" : fallback(request.focus(), "业务逻辑分析");
        String title = prose(text, "REPORT_TITLE", genericTitle, english);
        String summary = prose(text, "REPORT_SUMMARY",
                english ? "A source-backed walkthrough of the requested business behavior."
                        : "基于源码证据说明所请求的业务行为。", english);
        String actor = prose(text, "PRIMARY_ACTOR", english ? "Business caller" : "业务调用方", english);
        String firstSourceLabel = narrativeSymbol(anchors.get(0), 0, english);
        String genericTrigger = english ? "The business caller invokes this source-backed entry."
                : "业务调用方触发这个有源码依据的入口。";
        String genericOutcome = english ? "The observed source responsibility completes."
                : "已观察到的源码职责执行完成。";

        JsonObject analysis = new JsonObject();
        analysis.addProperty("schema", "closed-business-domain-analysis/v1");
        analysis.addProperty("title", title);
        analysis.addProperty("summary", summary);

        JsonObject overview = new JsonObject();
        overview.addProperty("purpose", prose(text, "OVERVIEW_PURPOSE", summary, english));
        overview.addProperty("primary_actor", actor);
        JsonArray story = new JsonArray();
        story.add(genericTrigger);
        story.add(summary);
        story.add(genericOutcome);
        overview.add("plain_story", story);
        JsonObject actorItem = new JsonObject();
        actorItem.addProperty("name", actor);
        actorItem.addProperty("goal", genericOutcome);
        actorItem.addProperty("enters_via", firstSourceLabel);
        overview.add("actors", array(actorItem));
        overview.add("domain_relationships", new JsonArray());
        overview.add("terms", new JsonArray());
        overview.add("business_objects", array(businessObject(anchors.get(0), summary, english)));
        List<PlannedDomain> plannedDomains = plannedDomains(plan, text, english);
        overview.add("reading_order", new JsonArray());
        analysis.add("business_overview", overview);

        Set<String> allowedDomainIds = plannedDomains.stream().map(PlannedDomain::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String fallbackDomainId = plannedDomains.get(0).id();
        List<String> stepDomainIds = new ArrayList<>();
        for (int index = 0; index < anchors.size(); index++) {
            String requested = text.getOrDefault("STEP_" + (index + 1) + "_DOMAIN_ID", "").strip();
            stepDomainIds.add(allowedDomainIds.contains(requested) ? requested : fallbackDomainId);
        }
        Set<String> usedDomainIds = new LinkedHashSet<>(stepDomainIds);
        JsonArray readingOrder = new JsonArray();
        plannedDomains.stream().map(PlannedDomain::id).filter(usedDomainIds::contains).forEach(readingOrder::add);
        overview.add("reading_order", readingOrder);
        JsonArray domainItems = new JsonArray();
        for (int domainIndex = 0; domainIndex < plannedDomains.size(); domainIndex++) {
            PlannedDomain planned = plannedDomains.get(domainIndex);
            if (!usedDomainIds.contains(planned.id())) continue;
            JsonObject domain = new JsonObject();
            domain.addProperty("id", planned.id());
            domain.addProperty("name", domainIndex == 0
                    ? prose(text, "DOMAIN_NAME", planned.name(), english) : planned.name());
            domain.addProperty("purpose", domainIndex == 0
                    ? prose(text, "DOMAIN_PURPOSE", planned.purpose(), english) : planned.purpose());
            domain.addProperty("why_here", planned.purpose());
            domain.add("actors", strings(actor));
            domain.add("owns", strings(planned.purpose()));
            domain.add("receives", strings(genericTrigger));
            domain.add("produces", strings(genericOutcome));
            domain.add("not_responsible", new JsonArray());
            domain.add("depends_on", new JsonArray());
            JsonArray sourceStepIds = new JsonArray();
            for (int index = 0; index < stepDomainIds.size(); index++) {
                if (planned.id().equals(stepDomainIds.get(index))) sourceStepIds.add("source-step-" + (index + 1));
            }
            domain.add("source_step_ids", sourceStepIds);
            domainItems.add(domain);
        }
        analysis.add("domains", domainItems);

        Map<String, List<Integer>> flowGroups = new LinkedHashMap<>();
        for (int index = 0; index < anchors.size(); index++) {
            Anchor anchor = anchors.get(index);
            String modelFlowKey = normalizedFlowKey(text.get("STEP_" + (index + 1) + "_FLOW_KEY"));
            String flowKey = modelFlowKey.isBlank() ? anchor.path() + '\u0000' + anchor.symbol() : modelFlowKey;
            flowGroups.computeIfAbsent(flowKey, ignored -> new ArrayList<>()).add(index);
        }
        JsonArray flows = new JsonArray();
        int flowNumber = 0;
        for (List<Integer> group : flowGroups.values()) {
            flowNumber++;
            Anchor firstAnchor = anchors.get(group.get(0));
            String groupLabel = narrativeSymbol(firstAnchor, group.get(0), english);
            String flowTitle = flowNumber == 1
                    ? prose(text, "FLOW_1_TITLE",
                    english ? "Source-backed business flow" : "源码支撑的业务流程", english)
                    : english ? groupLabel + " business flow" : groupLabel + " 业务流程";
            String flowSummary = flowNumber == 1 ? prose(text, "FLOW_1_SUMMARY", summary, english)
                    : english ? "An independently evidenced source execution." : "一个独立取证的源码执行。";
            String trigger = flowNumber == 1 ? prose(text, "FLOW_1_TRIGGER", genericTrigger, english) : genericTrigger;
            String outcome = flowNumber == 1 ? prose(text, "FLOW_1_OUTCOME", genericOutcome, english) : genericOutcome;
            String originId = "origin-" + flowNumber;
            String firstStepId = "source-step-" + (group.get(0) + 1);

            JsonObject flow = new JsonObject();
            flow.addProperty("id", "business-flow-" + flowNumber);
            JsonArray flowDomainIds = new JsonArray();
            group.stream().map(stepDomainIds::get).distinct().forEach(flowDomainIds::add);
            flow.add("domain_ids", flowDomainIds);
            flow.addProperty("title", flowTitle);
            flow.addProperty("summary", flowSummary);
            flow.addProperty("flow_type", "request");
            flow.addProperty("execution_scope", "single_trigger");
            flow.addProperty("actor", actor);
            flow.addProperty("trigger", trigger);
            flow.addProperty("routing_condition", trigger);
            flow.add("preconditions", new JsonArray());
            flow.addProperty("outcome", outcome);
            flow.addProperty("end_title", outcome);
            flow.addProperty("data_subject", english ? "Business request" : "业务请求");
            flow.addProperty("primary_origin_id", originId);
            flow.add("data_reads", new JsonArray());
            flow.add("data_writes", new JsonArray());
            flow.add("failure_paths", new JsonArray());
            flow.add("business_rules", new JsonArray());
            flow.add("consumer_targets", new JsonArray());

            JsonObject entry = source(firstAnchor);
            entry.addProperty("step_id", firstStepId);
            entry.addProperty("entry_kind", "public_caller");
            entry.addProperty("meaning", trigger);
            flow.add("entry_source", entry);

            JsonObject origin = source(firstAnchor);
            origin.addProperty("id", originId);
            origin.addProperty("role", "primary");
            origin.addProperty("data", english ? "Business request" : "业务请求");
            origin.addProperty("meaning", trigger);
            origin.addProperty("source_kind", "unknown");
            origin.addProperty("source", actor);
            origin.addProperty("entry", groupLabel);
            origin.addProperty("owner", actor);
            origin.addProperty("joins_step_id", firstStepId);
            origin.addProperty("upstream_producer_status", "confirmed");
            flow.add("data_origins", array(origin));

            JsonArray steps = new JsonArray();
            JsonArray dataFlow = new JsonArray();
            for (int groupIndex = 0; groupIndex < group.size(); groupIndex++) {
                int index = group.get(groupIndex);
                Anchor anchor = anchors.get(index);
                int number = index + 1;
            String sourceLabel = narrativeSymbol(anchor, index, english);
            String stepId = "source-step-" + number;
            String stepTitle = prose(text, "STEP_" + number + "_TITLE",
                    english ? "Source step " + number : "源码步骤 " + number, english);
            String stepSummary = prose(text, "STEP_" + number + "_SUMMARY",
                    english ? "Execute the source-backed responsibility at " + sourceLabel + "."
                            : "执行 " + sourceLabel + " 对应的源码职责。", english);

            JsonObject step = source(anchor);
            step.addProperty("id", stepId);
            step.addProperty("title", stepTitle);
            step.addProperty("summary", stepSummary);
            step.addProperty("kind", "success");
            step.addProperty("execution", "same_execution");
            step.addProperty("domain_id", stepDomainIds.get(index));
            step.addProperty("node_kind", "method");
            step.add("inputs", new JsonArray());
            step.add("outputs", new JsonArray());
            step.addProperty("relation_kind", "call");
            step.addProperty("relation_label", stepTitle);
            step.add("business_rules", new JsonArray());
            step.add("branches", new JsonArray());
            steps.add(step);

            JsonObject hop = source(anchor);
            hop.addProperty("id", "data-hop-" + number);
            hop.addProperty("lineage_id", originId);
            hop.addProperty("order", groupIndex + 1);
            hop.addProperty("step_id", stepId);
            hop.addProperty("phase", "deliver");
            hop.addProperty("timing", "same_execution");
            hop.addProperty("plain_action", stepSummary);
            hop.addProperty("data", english ? "Business request" : "业务请求");
            hop.addProperty("from", groupIndex == 0 ? actor
                    : narrativeSymbol(anchors.get(group.get(groupIndex - 1)), group.get(groupIndex - 1), english));
            hop.addProperty("to", sourceLabel);
            hop.addProperty("via", "call");
            hop.addProperty("transformation", stepSummary);
            hop.addProperty("storage", english ? "Transient execution state" : "执行过程中的临时状态");
            hop.addProperty("consumer", actor);
            dataFlow.add(hop);
            }
            flow.add("steps", steps);
            flow.add("data_flow", dataFlow);
            flows.add(flow);
        }
        analysis.add("flows", flows);
        analysis.add("unknowns", new JsonArray());
        analysis.add("revision_history", new JsonArray());
        return analysis;
    }

    private List<PlannedDomain> plannedDomains(
            DomainEvidencePlan plan,
            Map<String, String> text,
            boolean english
    ) {
        List<PlannedDomain> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonObject root = ModelJsonParser.parseObject(plan.json());
            for (JsonElement element : array(root, "likely_domains")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = stableToken(string(item, "id"));
                if (id.isBlank() || !ids.add(id)) continue;
                String name = fallback(string(item, "name"), id);
                String purpose = fallback(string(item, "purpose"),
                        english ? "Own this source-backed responsibility." : "承载这项有源码依据的业务职责。");
                result.add(new PlannedDomain(id, name, purpose));
                if (result.size() == 6) break;
            }
        } catch (RuntimeException ignored) {
            // The deterministic fallback below remains valid.
        }
        if (result.isEmpty()) {
            result.add(new PlannedDomain("domain-1",
                    prose(text, "DOMAIN_NAME", english ? "Business responsibility" : "业务职责", english),
                    prose(text, "DOMAIN_PURPOSE",
                            english ? "Own the source-backed behavior in this analysis."
                                    : "承载本次分析中有源码依据的业务行为。", english)));
        }
        return List.copyOf(result);
    }

    private String normalizedFlowKey(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.matches("[A-Za-z0-9_-]{1,80}") ? normalized : "";
    }

    private String stableToken(String value) {
        if (value == null) return "";
        String normalized = value.strip().replaceAll("[^A-Za-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private JsonObject businessObject(Anchor anchor, String meaning, boolean english) {
        JsonObject object = source(anchor);
        object.addProperty("id", "business-object-1");
        object.addProperty("name", english ? "Business request" : "业务请求");
        object.addProperty("plain_meaning", meaning);
        object.addProperty("storage_kind", "unknown");
        object.addProperty("lifecycle", meaning);
        object.add("field_groups", new JsonArray());
        object.addProperty("evidence", "inferred");
        object.addProperty("confidence", "low");
        return object;
    }

    private JsonObject source(Anchor anchor) {
        JsonObject source = new JsonObject();
        source.addProperty("file", anchor.path());
        source.addProperty("line", anchor.line());
        source.addProperty("symbol", anchor.symbol());
        source.addProperty("evidence", "direct_source");
        source.addProperty("confidence", "high");
        return source;
    }

    private List<Anchor> anchors(String rawEvidence, List<String> fallbackPaths, Set<String> manifest) {
        LinkedHashMap<String, Anchor> result = new LinkedHashMap<>();
        List<Anchor> functionScopes = new ArrayList<>();
        Set<String> scopesWithExactMatches = new LinkedHashSet<>();
        try {
            JsonObject evidence = ModelJsonParser.parseObject(rawEvidence);
            for (JsonElement controlElement : array(evidence, "control_flow_excerpts")) {
                if (!controlElement.isJsonObject()) continue;
                JsonObject control = controlElement.getAsJsonObject();
                String path = string(control, "path");
                String excerpt = string(control, "excerpt");
                Matcher scopes = SCOPE_HEADER.matcher(excerpt);
                while (scopes.find()) {
                    int start = Integer.parseInt(scopes.group(1));
                    int end = Integer.parseInt(scopes.group(2));
                    Definition definition = definition(excerpt, start, end, path);
                    addAnchor(result, path, definition.line(), end, definition.symbol(), manifest);
                    functionScopes.add(new Anchor(path, definition.line(), end, definition.symbol()));
                }
            }
            JsonArray queryResults = array(evidence, "query_results");
            for (JsonElement queryElement : queryResults) {
                if (!queryElement.isJsonObject()) continue;
                JsonObject query = queryElement.getAsJsonObject();
                String literal = string(query, "literal");
                for (JsonElement matchElement : array(query, "matches")) {
                    if (!matchElement.isJsonObject()) continue;
                    JsonObject match = matchElement.getAsJsonObject();
                    String path = string(match, "path");
                    int line = integer(match, "matched_line");
                    Anchor scope = functionScopes.stream()
                            .filter(anchor -> anchor.path().equals(path) && anchor.contains(line))
                            .min(java.util.Comparator.comparingInt(anchor -> anchor.endLine() - anchor.line()))
                            .orElse(null);
                    if (scope == null) {
                        addAnchor(result, path, line, line, literal, manifest);
                    } else {
                        scopesWithExactMatches.add(scope.path() + ':' + scope.line());
                        addAnchor(result, path, line, line, scope.symbol(), manifest);
                    }
                }
            }
            scopesWithExactMatches.forEach(result::remove);
            for (JsonElement candidateElement : array(evidence, "candidate_excerpts")) {
                if (!candidateElement.isJsonObject()) continue;
                JsonObject candidate = candidateElement.getAsJsonObject();
                String path = string(candidate, "path");
                if (result.values().stream().anyMatch(anchor -> anchor.path().equals(path))) continue;
                Matcher matcher = NUMBERED_LINE.matcher(string(candidate, "excerpt"));
                int line = matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
                addAnchor(result, path, line, line, symbolFromPath(path), manifest);
            }
        } catch (RuntimeException ignored) {
            // The local fallback below still produces a report from planned source paths.
        }
        for (String path : fallbackPaths) {
            String normalized = path == null ? "" : path.replace('\\', '/').strip();
            boolean alreadyAnchored = result.values().stream().anyMatch(anchor -> anchor.path().equals(normalized));
            if (!alreadyAnchored) addAnchor(result, normalized, 1, 1, symbolFromPath(normalized), manifest);
        }
        return result.values().stream().limit(12).toList();
    }

    private Definition definition(String excerpt, int start, int end, String path) {
        Matcher lines = NUMBERED_SOURCE_LINE.matcher(excerpt);
        Definition first = null;
        while (lines.find()) {
            int line = Integer.parseInt(lines.group(1));
            if (line < start || line > end) continue;
            String source = lines.group(2).strip();
            if (first == null) first = new Definition(line, symbolFromPath(path));
            String symbol = definitionSymbol(source);
            if (!symbol.isBlank()) return new Definition(line, symbol);
        }
        return first == null ? new Definition(start, symbolFromPath(path)) : first;
    }

    private String definitionSymbol(String source) {
        for (Pattern pattern : List.of(GO_FUNCTION, NAMED_FUNCTION, TYPE_DEFINITION)) {
            Matcher matcher = pattern.matcher(source);
            if (matcher.find()) return matcher.group(1);
        }
        Matcher callable = CALLABLE.matcher(source);
        String candidate = "";
        while (callable.find()) candidate = callable.group(1);
        return Set.of("if", "for", "while", "switch", "catch").contains(candidate) ? "" : candidate;
    }

    private void addAnchor(
            Map<String, Anchor> anchors,
            String path,
            int line,
            int endLine,
            String symbol,
            Set<String> manifest
    ) {
        String normalized = path == null ? "" : path.replace('\\', '/').strip();
        if (normalized.isEmpty() || line < 1 || (!manifest.isEmpty() && !manifest.contains(normalized))) return;
        String key = normalized + ':' + line;
        anchors.putIfAbsent(key, new Anchor(
                normalized, line, Math.max(line, endLine), fallback(symbol, symbolFromPath(normalized))
        ));
    }

    private Map<String, String> parseTextSlots(String raw) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String normalized = raw == null ? "" : raw.replace("\r", "");
        for (String original : normalized.lines().toList()) {
            String line = original.strip();
            if (line.startsWith("```") || line.isBlank()) continue;
            if (line.startsWith("- ")) line = line.substring(2).strip();
            int separator = line.indexOf('\t');
            if (separator < 1) separator = line.indexOf(':');
            if (separator < 1) continue;
            String key = line.substring(0, separator).strip().toUpperCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            if (key.matches("[A-Z0-9_]+") && !value.isBlank()) result.putIfAbsent(key, value);
        }
        if (result.isEmpty() && !normalized.isBlank()) {
            result.put("REPORT_SUMMARY", normalized.strip());
            result.put("FLOW_1_SUMMARY", normalized.strip());
        }
        return result;
    }

    private String prose(Map<String, String> text, String key, String fallback, boolean english) {
        String value = text.getOrDefault(key, "").replaceAll("\\s+", " ").strip();
        if (value.isBlank() || value.length() > 1200 || (english && ReportLanguageValidator.containsHan(value))
                || UNCERTAINTY_MARKERS.stream().anyMatch(value::contains)) {
            return fallback;
        }
        return value;
    }

    private JsonObject legacyAnalysis(String raw) {
        try {
            JsonObject object = ModelJsonParser.parseObject(raw);
            return "closed-business-domain-analysis/v1".equals(string(object, "schema")) ? object : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String narrativeSymbol(Anchor anchor, int index, boolean english) {
        if (english && ReportLanguageValidator.containsHan(anchor.symbol())) return "source step " + (index + 1);
        return anchor.symbol();
    }

    private static String symbolFromPath(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : fallback(name, "source");
    }

    private static JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name) : new JsonArray();
    }

    private static JsonArray array(JsonObject... values) {
        JsonArray result = new JsonArray();
        for (JsonObject value : values) result.add(value);
        return result;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive()
                ? object.get(name).getAsString() : "";
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object != null && object.has(name) ? object.get(name).getAsInt() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    record TextContract(List<String> slots, JsonArray bindings) {
    }

    private record PlannedDomain(String id, String name, String purpose) {
    }

    private record Definition(int line, String symbol) {
    }

    private record Anchor(String path, int line, int endLine, String symbol) {
        boolean contains(int candidateLine) {
            return candidateLine >= line && candidateLine <= endLine;
        }
    }
}
