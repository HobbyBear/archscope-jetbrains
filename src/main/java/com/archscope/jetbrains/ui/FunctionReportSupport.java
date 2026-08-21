package com.archscope.jetbrains.ui;

import com.archscope.jetbrains.analysis.ReportArchive;
import com.archscope.jetbrains.model.AnalysisResult;
import com.archscope.jetbrains.model.FunctionTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

final class FunctionReportSupport {
    private FunctionReportSupport() {
    }

    static Optional<ReportArchive.Entry> latest(ReportArchive archive, Path repositoryRoot, String stableId)
            throws IOException {
        for (ReportArchive.Entry entry : archive.list(repositoryRoot)) {
            if (entry.mode() != com.archscope.jetbrains.model.AnalysisRequest.Mode.FUNCTION_FLOW) continue;
            try {
                AnalysisResult result = archive.load(entry);
                JsonObject report = JsonParser.parseString(result.reportJson()).getAsJsonObject();
                JsonObject target = report.getAsJsonObject("function_target");
                if (target != null && target.has("stable_id") && stableId.equals(target.get("stable_id").getAsString())) {
                    return Optional.of(entry);
                }
            } catch (RuntimeException ignored) {
                // A malformed historical report must not hide other valid versions.
            }
        }
        return Optional.empty();
    }

    static FunctionTarget target(String reportJson, Path repositoryRoot) {
        JsonObject report = JsonParser.parseString(reportJson).getAsJsonObject();
        JsonObject target = report.getAsJsonObject("function_target");
        if (target == null) throw new IllegalArgumentException("Function report is missing function_target");
        int line = integer(target, "line", 1);
        return new FunctionTarget(
                repositoryRoot,
                string(target, "file"),
                string(target, "symbol"),
                string(target, "signature"),
                line,
                integer(target, "end_line", line)
        );
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static int integer(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
