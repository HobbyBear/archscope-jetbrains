package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.CodexWorkspaceService;
import com.archscope.jetbrains.git.GitCli;
import com.archscope.jetbrains.git.GitCommandException;
import com.archscope.jetbrains.git.SensitiveTextSanitizer;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.progress.ProgressIndicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DomainEvidenceExpansionService {
    private static final Gson GSON = new Gson();
    private static final int MAX_SNIPPETS = 20;
    private static final int MAX_EVIDENCE_CHARS = 190_000;
    private static final int MAX_CANDIDATE_CHARS = 24_000;
    private static final int MAX_QUERY_CHARS = 12_000;
    private static final int MAX_CONTROL_FLOW_CHARS = 160_000;
    private static final int MAX_CONTROL_FLOW_EXCERPT_CHARS = 90_000;
    private static final int CONTEXT_LINES = 16;
    private static final int PARTIAL_SCOPE_HEAD_LINES = 24;
    private static final int PARTIAL_SCOPE_TAIL_LINES = 48;

    public String expand(
            DomainEvidencePlan plan,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace,
            ProgressIndicator indicator
    ) throws GitCommandException, IOException {
        GitCli git = new GitCli(evidence.repositoryRoot());
        JsonObject result = new JsonObject();
        result.addProperty("schema", "business-domain-source-evidence/v1");
        JsonArray queryResults = new JsonArray();
        Set<String> selectedPaths = new LinkedHashSet<>(plan.candidatePaths());
        Map<String, LinkedHashSet<Integer>> matchedLinesByPath = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> matchedQueriesByPath = new LinkedHashMap<>();
        int snippets = 0;
        int chars = 0;
        int queryChars = 0;

        workspace.materialize(plan.candidatePaths(), indicator);
        JsonArray candidateExcerpts = new JsonArray();
        int candidateChars = 0;
        List<String> queryLiterals = plan.queries().stream().map(DomainEvidencePlan.Query::literal).toList();
        for (String path : plan.candidatePaths()) {
            if (candidateChars >= MAX_CANDIDATE_CHARS) break;
            String excerpt = beginning(workspace.repository().resolve(path), queryLiterals);
            if (excerpt.isBlank()) continue;
            int remaining = MAX_CANDIDATE_CHARS - candidateChars;
            if (excerpt.length() > remaining) excerpt = excerpt.substring(0, remaining);
            JsonObject source = new JsonObject();
            source.addProperty("path", path);
            source.addProperty("excerpt", excerpt);
            candidateExcerpts.add(source);
            candidateChars += excerpt.length();
        }
        chars += candidateChars;

        for (DomainEvidencePlan.Query query : plan.queries()) {
            indicator.checkCanceled();
            indicator.setText("检索业务证据 · " + query.literal());
            List<Match> rawMatches = new ArrayList<>();
            for (String literal : queryVariants(query.literal())) {
                rawMatches.addAll(parseMatches(
                        git.grep(indicator, evidence.targetCommit(), literal),
                        evidence.targetCommit()
                ));
            }
            List<Match> matches = selectMatches(rawMatches, Set.copyOf(plan.candidatePaths()));
            selectedPaths.addAll(matches.stream().map(Match::path).toList());
            Set<String> matchedPaths = matches.stream().map(Match::path).collect(java.util.stream.Collectors.toSet());
            for (String path : matchedPaths) {
                List<Integer> allLines = rawMatches.stream()
                        .filter(match -> path.equals(match.path()))
                        .map(Match::line)
                        .distinct()
                        .sorted()
                        .toList();
                matchedLinesByPath.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                        .addAll(evenlySample(allLines, 24));
                matchedQueriesByPath.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                        .add(query.literal());
            }
            workspace.materialize(matches.stream().map(Match::path).toList(), indicator);

            JsonObject queryResult = new JsonObject();
            queryResult.addProperty("literal", query.literal());
            queryResult.addProperty("role", query.role());
            queryResult.addProperty("reason", query.reason());
            JsonArray sources = new JsonArray();
            for (Match match : matches) {
                if (snippets >= MAX_SNIPPETS || queryChars >= MAX_QUERY_CHARS) break;
                String snippet = snippet(workspace.repository().resolve(match.path()), match.line());
                if (snippet.isBlank() || queryChars + snippet.length() > MAX_QUERY_CHARS) continue;
                JsonObject source = new JsonObject();
                source.addProperty("path", match.path());
                source.addProperty("matched_line", match.line());
                source.addProperty("snippet", snippet);
                sources.add(source);
                snippets++;
                chars += snippet.length();
                queryChars += snippet.length();
                if (snippets >= MAX_SNIPPETS) break;
            }
            queryResult.add("matches", sources);
            queryResults.add(queryResult);
        }

        workspace.materialize(selectedPaths, indicator);
        JsonArray controlFlowExcerpts = new JsonArray();
        int controlFlowChars = 0;
        for (Map.Entry<String, LinkedHashSet<Integer>> entry : matchedLinesByPath.entrySet()) {
            if (controlFlowChars >= MAX_CONTROL_FLOW_CHARS || chars >= MAX_EVIDENCE_CHARS) break;
            int remaining = Math.min(
                    MAX_CONTROL_FLOW_EXCERPT_CHARS,
                    Math.min(MAX_CONTROL_FLOW_CHARS - controlFlowChars, MAX_EVIDENCE_CHARS - chars)
            );
            String excerpt = controlFlowExcerpt(
                    workspace.repository().resolve(entry.getKey()), entry.getValue(), remaining
            );
            if (excerpt.isBlank()) continue;
            JsonObject source = new JsonObject();
            JsonArray literals = new JsonArray();
            matchedQueriesByPath.getOrDefault(entry.getKey(), new LinkedHashSet<>()).forEach(literals::add);
            source.add("literals", literals);
            source.addProperty("path", entry.getKey());
            JsonArray seedLines = new JsonArray();
            entry.getValue().forEach(seedLines::add);
            source.add("matched_lines", seedLines);
            source.addProperty("excerpt", excerpt);
            controlFlowExcerpts.add(source);
            controlFlowChars += excerpt.length();
            chars += excerpt.length();
        }
        result.add("query_results", queryResults);
        result.add("candidate_excerpts", candidateExcerpts);
        result.add("control_flow_excerpts", controlFlowExcerpts);
        DeduplicationStats deduplication = deduplicateCoveredSourceLines(
                queryResults, candidateExcerpts, controlFlowExcerpts
        );
        result.addProperty("original_evidence_chars", chars);
        result.addProperty("evidence_chars", Math.max(0, chars - deduplication.deduplicatedChars()));
        result.addProperty("deduplicated_chars", deduplication.deduplicatedChars());
        result.addProperty("unique_source_chars", deduplication.uniqueSourceChars());
        result.addProperty("materialized_file_count", selectedPaths.size());
        return GSON.toJson(result);
    }

    private DeduplicationStats deduplicateCoveredSourceLines(
            JsonArray queryResults,
            JsonArray candidateExcerpts,
            JsonArray controlFlowExcerpts
    ) {
        Map<String, ControlCoverage> coverageByPath = controlCoverage(controlFlowExcerpts);
        int deduplicatedChars = 0;

        for (com.google.gson.JsonElement resultElement : queryResults) {
            if (!resultElement.isJsonObject()) continue;
            JsonArray matches = resultElement.getAsJsonObject().getAsJsonArray("matches");
            if (matches == null) continue;
            for (com.google.gson.JsonElement matchElement : matches) {
                if (!matchElement.isJsonObject()) continue;
                JsonObject match = matchElement.getAsJsonObject();
                String path = string(match, "path");
                String snippet = string(match, "snippet");
                ControlCoverage coverage = coverageByPath.get(path);
                int matchedLine = integer(match, "matched_line");
                if (coverage == null || coverage.omittedScopes().stream().anyMatch(scope -> scope.contains(matchedLine))) {
                    continue;
                }
                FilteredExcerpt filtered = filterCoveredLines(snippet, coverage);
                if (filtered.deduplicatedChars() == 0) continue;
                match.addProperty("snippet", filtered.excerpt());
                match.add("source_refs", sourceRefs(path, filtered.coveredScopes()));
                deduplicatedChars += filtered.deduplicatedChars();
            }
        }

        for (com.google.gson.JsonElement candidateElement : candidateExcerpts) {
            if (!candidateElement.isJsonObject()) continue;
            JsonObject candidate = candidateElement.getAsJsonObject();
            String path = string(candidate, "path");
            ControlCoverage coverage = coverageByPath.get(path);
            if (coverage == null) continue;
            FilteredExcerpt filtered = filterCoveredLines(string(candidate, "excerpt"), coverage);
            if (filtered.deduplicatedChars() == 0) continue;
            candidate.addProperty("excerpt", filtered.excerpt());
            candidate.add("source_refs", sourceRefs(path, filtered.coveredScopes()));
            deduplicatedChars += filtered.deduplicatedChars();
        }

        return new DeduplicationStats(
                deduplicatedChars,
                uniqueSourceChars(queryResults, candidateExcerpts, controlFlowExcerpts)
        );
    }

    private Map<String, ControlCoverage> controlCoverage(JsonArray controlFlowExcerpts) {
        Map<String, ControlCoverage> coverageByPath = new LinkedHashMap<>();
        for (com.google.gson.JsonElement element : controlFlowExcerpts) {
            if (!element.isJsonObject()) continue;
            JsonObject source = element.getAsJsonObject();
            String path = string(source, "path");
            if (path.isBlank()) continue;
            Map<Integer, CoveredSourceLine> lines = new LinkedHashMap<>();
            List<Range> omittedScopes = new ArrayList<>();
            Range currentScope = null;
            for (ExcerptChunk chunk : excerptChunks(string(source, "excerpt"))) {
                Range included = scope(chunk.text(), "complete_function_scope:");
                if (included != null) {
                    currentScope = included;
                    continue;
                }
                Range omitted = scope(chunk.text(), "scope_omitted_by_evidence_budget:");
                if (omitted != null) {
                    currentScope = null;
                    omittedScopes.add(omitted);
                    continue;
                }
                NumberedSourceLine numbered = numberedSourceLine(chunk);
                if (numbered != null && currentScope != null && currentScope.contains(numbered.line())) {
                    lines.putIfAbsent(numbered.line(), new CoveredSourceLine(numbered.text(), currentScope));
                }
            }
            coverageByPath.put(path, new ControlCoverage(lines, omittedScopes));
        }
        return coverageByPath;
    }

    private FilteredExcerpt filterCoveredLines(String excerpt, ControlCoverage coverage) {
        if (excerpt.isBlank() || coverage.lines().isEmpty()) {
            return new FilteredExcerpt(excerpt, 0, List.of());
        }
        StringBuilder retained = new StringBuilder();
        int deduplicatedChars = 0;
        LinkedHashSet<Range> coveredScopes = new LinkedHashSet<>();
        for (ExcerptChunk chunk : excerptChunks(excerpt)) {
            NumberedSourceLine numbered = numberedSourceLine(chunk);
            CoveredSourceLine covered = numbered == null ? null : coverage.lines().get(numbered.line());
            if (covered != null && covered.text().equals(numbered.text())) {
                deduplicatedChars += chunk.raw().length();
                coveredScopes.add(covered.scope());
            } else {
                retained.append(chunk.raw());
            }
        }
        return new FilteredExcerpt(retained.toString(), deduplicatedChars, List.copyOf(coveredScopes));
    }

    private JsonArray sourceRefs(String path, List<Range> scopes) {
        JsonArray refs = new JsonArray();
        for (Range scope : scopes) {
            JsonObject ref = new JsonObject();
            ref.addProperty("kind", "control_flow_scope");
            ref.addProperty("path", path);
            ref.addProperty("start_line", scope.start());
            ref.addProperty("end_line", scope.end());
            refs.add(ref);
        }
        return refs;
    }

    private int uniqueSourceChars(
            JsonArray queryResults,
            JsonArray candidateExcerpts,
            JsonArray controlFlowExcerpts
    ) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int chars = 0;
        for (com.google.gson.JsonElement resultElement : queryResults) {
            if (!resultElement.isJsonObject()) continue;
            JsonArray matches = resultElement.getAsJsonObject().getAsJsonArray("matches");
            if (matches == null) continue;
            for (com.google.gson.JsonElement matchElement : matches) {
                if (!matchElement.isJsonObject()) continue;
                JsonObject match = matchElement.getAsJsonObject();
                chars += addUniqueSourceLines(seen, string(match, "path"), string(match, "snippet"));
            }
        }
        for (com.google.gson.JsonElement candidateElement : candidateExcerpts) {
            if (!candidateElement.isJsonObject()) continue;
            JsonObject candidate = candidateElement.getAsJsonObject();
            chars += addUniqueSourceLines(seen, string(candidate, "path"), string(candidate, "excerpt"));
        }
        for (com.google.gson.JsonElement controlElement : controlFlowExcerpts) {
            if (!controlElement.isJsonObject()) continue;
            JsonObject control = controlElement.getAsJsonObject();
            chars += addUniqueSourceLines(seen, string(control, "path"), string(control, "excerpt"));
        }
        return chars;
    }

    private int addUniqueSourceLines(Set<String> seen, String path, String excerpt) {
        int chars = 0;
        for (ExcerptChunk chunk : excerptChunks(excerpt)) {
            NumberedSourceLine numbered = numberedSourceLine(chunk);
            if (numbered == null) continue;
            String identity = path + '\u0000' + numbered.line() + '\u0000' + numbered.text();
            if (seen.add(identity)) chars += chunk.raw().length();
        }
        return chars;
    }

    private List<ExcerptChunk> excerptChunks(String excerpt) {
        if (excerpt == null || excerpt.isEmpty()) return List.of();
        List<ExcerptChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < excerpt.length()) {
            int newline = excerpt.indexOf('\n', start);
            int end = newline < 0 ? excerpt.length() : newline + 1;
            String raw = excerpt.substring(start, end);
            String text = newline < 0 ? raw : raw.substring(0, raw.length() - 1);
            chunks.add(new ExcerptChunk(raw, text));
            start = end;
        }
        return chunks;
    }

    private NumberedSourceLine numberedSourceLine(ExcerptChunk chunk) {
        int separator = chunk.text().indexOf(':');
        if (separator <= 0) return null;
        String prefix = chunk.text().substring(0, separator);
        if (!prefix.chars().allMatch(Character::isDigit)) return null;
        try {
            return new NumberedSourceLine(
                    Integer.parseInt(prefix), chunk.text().substring(separator + 1)
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Range scope(String value, String prefix) {
        if (!value.startsWith(prefix)) return null;
        String range = value.substring(prefix.length());
        int separator = range.indexOf('-');
        if (separator <= 0) return null;
        try {
            int start = Integer.parseInt(range.substring(0, separator));
            int end = Integer.parseInt(range.substring(separator + 1));
            return start > 0 && end >= start ? new Range(start, end) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private int integer(JsonObject object, String name) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private List<Match> parseMatches(String output, String revision) {
        List<Match> matches = new ArrayList<>();
        String prefix = revision + ":";
        for (String line : output.lines().toList()) {
            if (!line.startsWith(prefix)) continue;
            String remainder = line.substring(prefix.length());
            int pathEnd = remainder.indexOf(':');
            int lineEnd = pathEnd < 0 ? -1 : remainder.indexOf(':', pathEnd + 1);
            if (pathEnd <= 0 || lineEnd <= pathEnd + 1) continue;
            String path = remainder.substring(0, pathEnd).replace('\\', '/');
            if (!DomainEvidencePlan.isAnalyzablePath(path) || SensitiveTextSanitizer.isSensitivePath(path)) continue;
            try {
                matches.add(new Match(path, Integer.parseInt(remainder.substring(pathEnd + 1, lineEnd))));
            } catch (NumberFormatException ignored) {
                // Ignore malformed grep lines.
            }
        }
        return matches;
    }

    private List<Match> selectMatches(List<Match> matches, Set<String> candidatePaths) {
        List<Match> ordered = matches.stream().sorted(
                java.util.Comparator.comparing((Match match) -> !candidatePaths.contains(match.path()))
                        .thenComparing(match -> isTestPath(match.path()))
                        .thenComparing(Match::path)
                        .thenComparingInt(Match::line)
        ).toList();
        Map<String, List<Match>> byFile = new LinkedHashMap<>();
        for (Match match : ordered) {
            byFile.computeIfAbsent(match.path(), ignored -> new ArrayList<>()).add(match);
        }
        List<Match> selected = new ArrayList<>();
        int files = 0;
        for (List<Match> fileMatches : byFile.values()) {
            if (files++ == 8) break;
            selected.add(fileMatches.get(0));
            Match last = fileMatches.get(fileMatches.size() - 1);
            if (last.line() != fileMatches.get(0).line()) selected.add(last);
            if (selected.size() >= 16) break;
        }
        return selected.stream().limit(16).toList();
    }

    private boolean isTestPath(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.contains("_test.") || lower.contains(".spec.");
    }

    private List<String> queryVariants(String literal) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(literal);
        if (literal.contains("_")) {
            StringBuilder camel = new StringBuilder();
            boolean upper = false;
            for (char value : literal.toCharArray()) {
                if (value == '_') {
                    upper = true;
                } else if (upper) {
                    camel.append(Character.toUpperCase(value));
                    upper = false;
                } else {
                    camel.append(value);
                }
            }
            if (!camel.isEmpty()) variants.add(camel.toString());
        }
        return List.copyOf(variants);
    }

    private String snippet(Path path, int line) throws IOException {
        if (!Files.isRegularFile(path)) return "";
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int start = Math.max(1, line - CONTEXT_LINES);
        int end = Math.min(lines.size(), line + CONTEXT_LINES);
        return numbered(lines, start, end);
    }

    private String controlFlowExcerpt(Path path, Set<Integer> seedLines, int maxChars) throws IOException {
        if (!Files.isRegularFile(path) || seedLines.isEmpty()) return "";
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Range> scopes = seedLines.stream()
                .map(line -> enclosingDefinition(lines, line))
                .distinct()
                .toList();
        StringBuilder result = new StringBuilder();
        for (Range scope : scopes) {
            String completeScope = numbered(lines, scope.start(), scope.end());
            String header = "complete_function_scope:" + scope.start() + '-' + scope.end() + '\n';
            if (result.length() + header.length() + completeScope.length() > maxChars) {
                int remaining = maxChars - result.length();
                String partial = partialScope(lines, scope, seedLines, remaining);
                if (!partial.isBlank()) {
                    result.append(partial);
                } else {
                    result.append("scope_omitted_by_evidence_budget:")
                            .append(scope.start()).append('-').append(scope.end()).append('\n');
                }
                continue;
            }
            result.append(header).append(completeScope);
        }
        return result.toString();
    }

    private String partialScope(List<String> lines, Range scope, Set<Integer> seedLines, int maxChars) {
        String header = "partial_function_scope:" + scope.start() + '-' + scope.end()
                + " (head + matched contexts + tail)\n";
        String head = "function_head:\n" + numbered(
                lines,
                scope.start(),
                Math.min(scope.end(), scope.start() + PARTIAL_SCOPE_HEAD_LINES - 1)
        );
        String tail = "function_tail:\n" + numbered(
                lines,
                Math.max(scope.start(), scope.end() - PARTIAL_SCOPE_TAIL_LINES + 1),
                scope.end()
        );
        if (header.length() + head.length() + tail.length() > maxChars) return "";

        StringBuilder result = new StringBuilder(header).append(head);
        int reservedTailChars = tail.length();
        for (int seed : seedLines.stream().filter(scope::contains).sorted().toList()) {
            if (seed <= scope.start() + PARTIAL_SCOPE_HEAD_LINES
                    || seed >= scope.end() - PARTIAL_SCOPE_TAIL_LINES) continue;
            int start = Math.max(scope.start(), seed - CONTEXT_LINES);
            int end = Math.min(scope.end(), seed + CONTEXT_LINES);
            String context = "matched_context:" + start + '-' + end + '\n' + numbered(lines, start, end);
            if (result.length() + context.length() + reservedTailChars > maxChars) break;
            result.append(context);
        }
        return result.append(tail).toString();
    }

    private Range enclosingDefinition(List<String> lines, int requestedLine) {
        int line = Math.max(1, Math.min(requestedLine, lines.size()));
        int start = line;
        for (int index = line; index >= 1; index--) {
            if (isDefinition(lines.get(index - 1).stripLeading())) {
                start = index;
                break;
            }
        }
        int openingLine = -1;
        for (int index = start; index <= Math.min(lines.size(), start + 12); index++) {
            if (lines.get(index - 1).indexOf('{') >= 0) {
                openingLine = index;
                break;
            }
        }
        if (openingLine < 0) return indentationRange(lines, start);

        int balance = 0;
        boolean opened = false;
        BraceState state = new BraceState();
        for (int index = openingLine; index <= lines.size(); index++) {
            int delta = braceDelta(lines.get(index - 1), state);
            if (delta > 0) opened = true;
            balance += delta;
            if (opened && balance <= 0) return new Range(start, index);
        }
        return new Range(start, Math.min(lines.size(), line + CONTEXT_LINES));
    }

    private Range indentationRange(List<String> lines, int start) {
        String definition = lines.get(start - 1);
        int indent = definition.length() - definition.stripLeading().length();
        for (int index = start + 1; index <= lines.size(); index++) {
            String value = lines.get(index - 1);
            if (value.isBlank()) continue;
            int currentIndent = value.length() - value.stripLeading().length();
            if (currentIndent <= indent && isDefinition(value.stripLeading())) return new Range(start, index - 1);
        }
        return new Range(start, Math.min(lines.size(), start + CONTEXT_LINES));
    }

    private int braceDelta(String value, BraceState state) {
        int delta = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : 0;
            if (state.blockComment) {
                if (current == '*' && next == '/') {
                    state.blockComment = false;
                    index++;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && quote != '`') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') break;
            if (current == '/' && next == '*') {
                state.blockComment = true;
                index++;
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '{') {
                delta++;
            } else if (current == '}') {
                delta--;
            }
        }
        return delta;
    }

    private List<Integer> evenlySample(List<Integer> values, int limit) {
        if (values.size() <= limit) return values;
        LinkedHashSet<Integer> sampled = new LinkedHashSet<>();
        for (int index = 0; index < limit; index++) {
            int selected = (int) Math.round(index * (values.size() - 1.0) / (limit - 1.0));
            sampled.add(values.get(selected));
        }
        return List.copyOf(sampled);
    }

    private String beginning(Path path, List<String> literals) throws IOException {
        if (!Files.isRegularFile(path)) return "";
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int firstEnd = Math.min(lines.size(), 40);
        StringBuilder result = new StringBuilder(numbered(lines, 1, firstEnd));
        Set<Integer> included = new LinkedHashSet<>();
        for (int index = 1; index <= firstEnd; index++) included.add(index);
        List<String> tokens = queryTokens(literals);
        for (int index = firstEnd + 1; index <= lines.size() && result.length() < MAX_CANDIDATE_CHARS; index++) {
            String value = lines.get(index - 1).stripLeading();
            if (!isDefinition(value)) continue;
            String normalized = value.toLowerCase(java.util.Locale.ROOT).replace("_", "");
            boolean relevant = tokens.stream().anyMatch(normalized::contains);
            if (!relevant) continue;
            Range scope = enclosingDefinition(lines, index);
            for (int line = scope.start(); line <= scope.end() && result.length() < MAX_CANDIDATE_CHARS; line++) {
                if (!included.add(line)) continue;
                String source = SensitiveTextSanitizer.redact(lines.get(line - 1));
                if (source.length() > 500) source = source.substring(0, 500) + " ...[line truncated]";
                result.append(line).append(':').append(source).append('\n');
            }
        }
        int definitions = 0;
        for (int index = firstEnd + 1; index <= lines.size() && definitions < 30
                && result.length() < MAX_CANDIDATE_CHARS; index++) {
            String value = lines.get(index - 1).stripLeading();
            if (isDefinition(value) && included.add(index)) {
                result.append(index).append(':').append(SensitiveTextSanitizer.redact(value)).append('\n');
                definitions++;
            }
        }
        return result.toString();
    }

    private boolean isDefinition(String value) {
        return value.startsWith("func ") || value.startsWith("type ") || value.startsWith("class ")
                || value.startsWith("def ") || value.startsWith("function ") || value.startsWith("public ")
                || value.startsWith("private ") || value.startsWith("protected ");
    }

    private List<String> queryTokens(List<String> literals) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Set<String> ignored = Set.of("after", "before", "complete", "completed", "content", "common", "runtime");
        for (String literal : literals) {
            String split = literal.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
            for (String token : split.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() >= 4 && !ignored.contains(token)) tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private String numbered(List<String> lines, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index <= end; index++) {
            appendNumberedLine(result, lines, index);
        }
        return result.toString();
    }

    private void appendNumberedLine(StringBuilder result, List<String> lines, int index) {
        String line = SensitiveTextSanitizer.redact(lines.get(index - 1));
        if (line.length() > 500) line = line.substring(0, 500) + " ...[line truncated]";
        result.append(index).append(':').append(line).append('\n');
    }

    private record Match(String path, int line) {
    }

    private record DeduplicationStats(int deduplicatedChars, int uniqueSourceChars) {
    }

    private record FilteredExcerpt(String excerpt, int deduplicatedChars, List<Range> coveredScopes) {
    }

    private record ControlCoverage(
            Map<Integer, CoveredSourceLine> lines,
            List<Range> omittedScopes
    ) {
    }

    private record CoveredSourceLine(String text, Range scope) {
    }

    private record ExcerptChunk(String raw, String text) {
    }

    private record NumberedSourceLine(int line, String text) {
    }

    private record Range(int start, int end) {
        boolean contains(int line) {
            return line >= start && line <= end;
        }
    }

    private static final class BraceState {
        private boolean blockComment;
    }
}
