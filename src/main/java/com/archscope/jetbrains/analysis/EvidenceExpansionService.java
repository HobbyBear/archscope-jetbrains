package com.archscope.jetbrains.analysis;

import com.archscope.jetbrains.git.CodexWorkspaceService;
import com.archscope.jetbrains.git.GitCli;
import com.archscope.jetbrains.git.GitCommandException;
import com.archscope.jetbrains.git.SensitiveTextSanitizer;
import com.archscope.jetbrains.model.EvidencePack;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EvidenceExpansionService {
    private static final Logger LOG = Logger.getInstance(EvidenceExpansionService.class);
    private static final Gson GSON = new Gson();
    private static final int CONTEXT_LINES = 8;

    public String expand(
            EvidencePlan plan,
            EvidencePack evidence,
            CodexWorkspaceService.Workspace workspace,
            ProgressIndicator indicator
    ) throws GitCommandException, IOException {
        long startedAt = System.nanoTime();
        GitCli git = new GitCli(evidence.repositoryRoot());
        JsonObject result = new JsonObject();
        result.addProperty("schema", "closed-source-evidence/v1");
        JsonArray groups = new JsonArray();
        int queryCount = 0;
        Set<String> changedPaths = Set.copyOf(evidence.aggregateChangedPaths());

        for (EvidencePlan.ChangeGroup group : plan.groups()) {
            indicator.checkCanceled();
            indicator.setText("补全改动主题证据 · " + group.title());
            JsonObject groupResult = new JsonObject();
            groupResult.addProperty("group_id", group.id());
            JsonArray queryResults = new JsonArray();
            for (EvidencePlan.EvidenceQuery query : group.evidenceQueries()) {
                queryCount++;
                String output = git.grep(indicator, evidence.targetCommit(), query.literal());
                List<Match> matches = selectMatches(parseMatches(output, evidence.targetCommit(), changedPaths));
                workspace.materialize(matches.stream().map(Match::path).distinct().toList(), indicator);

                JsonObject queryResult = new JsonObject();
                queryResult.addProperty("literal", query.literal());
                queryResult.addProperty("reason", query.reason());
                JsonArray snippets = new JsonArray();
                for (Match match : matches) {
                    String snippet = snippet(workspace.repository().resolve(match.path()), match.line());
                    if (snippet.isBlank()) continue;
                    JsonObject source = new JsonObject();
                    source.addProperty("path", match.path());
                    source.addProperty("matched_line", match.line());
                    source.addProperty("snippet", snippet);
                    snippets.add(source);
                }
                queryResult.add("matches", snippets);
                queryResults.add(queryResult);
                LOG.info("Closed evidence query: literal=" + query.literal()
                        + ", selectedMatches=" + matches.size()
                        + ", snippetChars=" + snippets.toString().length());
            }
            groupResult.add("query_results", queryResults);
            groups.add(groupResult);
        }
        result.add("groups", groups);
        LOG.info("Closed evidence expanded: groups=" + plan.groups().size()
                + ", queries=" + queryCount
                + ", chars=" + result.toString().length()
                + ", elapsedMs=" + ((System.nanoTime() - startedAt) / 1_000_000));
        return GSON.toJson(result);
    }

    private List<Match> parseMatches(String output, String revision, Set<String> changedPaths) {
        Map<String, Match> unique = new LinkedHashMap<>();
        String prefix = revision + ":";
        for (String line : output.lines().toList()) {
            if (!line.startsWith(prefix)) continue;
            String remainder = line.substring(prefix.length());
            int pathEnd = remainder.indexOf(':');
            int lineEnd = pathEnd < 0 ? -1 : remainder.indexOf(':', pathEnd + 1);
            if (pathEnd <= 0 || lineEnd <= pathEnd + 1) continue;
            String path = remainder.substring(0, pathEnd).replace('\\', '/');
            if (!isAuthoredSource(path) || SensitiveTextSanitizer.isSensitivePath(path)) continue;
            int number;
            try {
                number = Integer.parseInt(remainder.substring(pathEnd + 1, lineEnd));
            } catch (NumberFormatException ignored) {
                continue;
            }
            Match match = new Match(path, number, changedPaths.contains(path));
            unique.putIfAbsent(path + ":" + number, match);
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(Match::changed).reversed().thenComparing(Match::path).thenComparingInt(Match::line))
                .toList();
    }

    private List<Match> selectMatches(List<Match> matches) {
        Map<String, Integer> perFile = new LinkedHashMap<>();
        List<Match> selected = new ArrayList<>();
        for (Match match : matches) {
            int count = perFile.getOrDefault(match.path(), 0);
            if (count >= 1) continue;
            if (!perFile.containsKey(match.path()) && perFile.size() >= 6) continue;
            selected.add(match);
            perFile.put(match.path(), count + 1);
        }
        return selected;
    }

    private String snippet(Path path, int line) throws IOException {
        if (!Files.isRegularFile(path)) return "";
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int start = Math.max(1, line - CONTEXT_LINES);
        int end = Math.min(lines.size(), line + CONTEXT_LINES);
        StringBuilder result = new StringBuilder();
        for (int index = start; index <= end; index++) {
            String sourceLine = lines.get(index - 1);
            if (sourceLine.length() > 500) sourceLine = sourceLine.substring(0, 500) + " ...[line truncated]";
            result.append(index).append(':').append(sourceLine).append('\n');
        }
        return result.toString();
    }

    private boolean isAuthoredSource(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("/.repomind/") || lower.contains("/vendor/") || lower.contains("/node_modules/")
                || lower.contains("/dist/") || lower.contains("/build/") || lower.contains("/generated/")
                || lower.contains("/static/") || lower.contains("/public/") || lower.endsWith(".min.js")
                || lower.endsWith(".pb.go") || lower.contains("generated.")) {
            return false;
        }
        int slash = lower.lastIndexOf('/');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.contains(".") && !name.endsWith(".md") && !name.endsWith(".json")
                && !name.endsWith(".lock") && !name.endsWith(".sum");
    }

    private record Match(String path, int line, boolean changed) {
    }
}
